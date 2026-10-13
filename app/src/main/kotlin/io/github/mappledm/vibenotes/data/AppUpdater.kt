package io.github.mappledm.vibenotes.data

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.mappledm.vibenotes.core.update.ReleaseInfo
import io.github.mappledm.vibenotes.core.update.UpdateClient
import io.github.mappledm.vibenotes.core.update.UpdateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState

    /** Downloaded and verified; [waitingForPermission] while the user allows installs from VibeNotes. */
    data class Ready(val release: ReleaseInfo, val apk: File, val waitingForPermission: Boolean = false) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

/**
 * Updates from the "latest-build" GitHub release: check, download, verify, then hand the APK
 * to the system installer, which asks the user to confirm. Data is kept: same package, same key.
 */
class AppUpdater(
    private val app: Application,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val client: UpdateClient = UpdateClient(),
) {
    private val installed: PackageInfo? = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
    val currentVersionCode: Long = installed?.let(::versionCodeOf) ?: 0L
    val currentVersionName: String = installed?.versionName ?: "—"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private val busy get() = job?.isActive == true

    private val json = Json { ignoreUnknownKeys = true }
    private val updatesDir get() = File(app.cacheDir, UPDATES_DIR)

    init {
        scope.launch { restoreDownloaded() }
    }

    /**
     * Android may restart the app while the user allows installs from VibeNotes. A verified
     * download for a newer version is offered again; anything else (e.g. already installed) is removed.
     */
    private fun restoreDownloaded() {
        runCatching {
            val release = File(updatesDir, RELEASE_FILE).takeIf { it.exists() }
                ?.let { json.decodeFromString(ReleaseInfo.serializer(), it.readText()) }
            val apk = release?.let { apkFile(it) }
            if (release != null && apk != null && apk.exists() && release.versionCode > currentVersionCode) {
                verifyPackage(apk)
                _state.compareAndSet(UpdateState.Idle, UpdateState.Ready(release, apk))
            } else {
                updatesDir.listFiles()?.forEach { it.delete() }
            }
        }.onFailure { updatesDir.listFiles()?.forEach { it.delete() } }
    }

    private fun apkFile(release: ReleaseInfo) = File(updatesDir, "VibeNotes-${release.versionCode}.apk")

    /** Called whenever the app comes to the foreground. */
    fun onAppResumed() {
        val current = _state.value
        if (current is UpdateState.Ready && current.waitingForPermission && canInstall()) {
            install()
            return
        }
        val s = settings.current
        if (s.autoUpdateCheck && System.currentTimeMillis() - s.lastUpdateCheck > CHECK_INTERVAL_MS) check(quiet = true)
    }

    /** [quiet] checks run in the background: failures are not shown, there is nothing to fix. */
    fun check(quiet: Boolean = false) {
        if (busy) return
        val previous = _state.value
        if (previous is UpdateState.Downloading || previous is UpdateState.Ready) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val latest = client.latest()
                settings.setLastUpdateCheck(System.currentTimeMillis())
                if (latest.versionCode > currentVersionCode) UpdateState.Available(latest) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quiet) previous else UpdateState.Failed(message(e), null)
            }
        }
    }

    fun download(release: ReleaseInfo) {
        if (busy) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, null)
            try {
                val dir = updatesDir.apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val apk = client.download(release, apkFile(release)) { progress ->
                    _state.value = UpdateState.Downloading(release, progress)
                }
                verifyPackage(apk)
                File(dir, RELEASE_FILE).writeText(json.encodeToString(ReleaseInfo.serializer(), release))
                _state.value = UpdateState.Ready(release, apk)
                install()
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(message(e), release)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss(release: ReleaseInfo) = settings.setDismissedUpdate(release.versionCode)

    /**
     * Opens the system installer for the downloaded APK. The first time Android asks to allow
     * installs from VibeNotes; after the user returns, [onAppResumed] continues.
     */
    fun install() {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!ready.apk.exists()) {
            _state.value = UpdateState.Available(ready.release)
            return
        }
        if (!canInstall()) {
            _state.value = ready.copy(waitingForPermission = true)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return
        }
        _state.value = ready.copy(waitingForPermission = false)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Failed("На телефоне не нашлось установщика приложений", ready.release)
        }
    }

    private fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** Only an APK of this very app, and newer than the installed one, goes to the installer. */
    private fun verifyPackage(apk: File) {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw UpdateException("Скачанный файл не похож на приложение")
        if (info.packageName != app.packageName) {
            apk.delete()
            throw UpdateException("Скачанный файл — другое приложение (${info.packageName})")
        }
        if (versionCodeOf(info) <= currentVersionCode) {
            apk.delete()
            throw UpdateException("На GitHub та же или более старая версия")
        }
    }

    private fun message(e: Exception): String = when (e) {
        is UpdateException -> e.message ?: "Ошибка обновления"
        else -> "Ошибка обновления: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    companion object {
        private const val UPDATES_DIR = "updates"
        private const val RELEASE_FILE = "release.json"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
