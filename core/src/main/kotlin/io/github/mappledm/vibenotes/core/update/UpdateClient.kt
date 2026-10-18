package io.github.mappledm.vibenotes.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What CI publishes next to the APK as `version.json` on each versioned release.
 * [versionCode] grows with every build; [sha256] lets the app check the download.
 */
@Serializable
data class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long = 0,
    val commit: String = "",
    /** "What's new" in Russian, from the top section of CHANGELOG.md. */
    val notes: String = "",
)

class UpdateException(message: String) : Exception(message)

/**
 * Reads the latest release manifest from GitHub and downloads its APK. Nothing about the
 * user is sent: two plain GET requests to public files.
 */
class UpdateClient(
    private val manifestUrl: String = MANIFEST_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException(httpError(response.code))
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw UpdateException("Нет соединения с GitHub")
        }
        try {
            json.decodeFromString(ReleaseInfo.serializer(), body)
        } catch (e: SerializationException) {
            throw UpdateException("Не удалось прочитать сведения о версии")
        } catch (e: IllegalArgumentException) {
            throw UpdateException("Не удалось прочитать сведения о версии")
        }
    }

    /**
     * Downloads the APK of [release] into [target]. The file appears only after its SHA-256
     * matches the manifest, so a broken or half-replaced download is never handed to the installer.
     */
    suspend fun download(release: ReleaseInfo, target: File, onProgress: (Float?) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val partial = File(target.parentFile, target.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val request = Request.Builder().url(release.apkUrl).header("Cache-Control", "no-cache").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw UpdateException(httpError(response.code))
                    val body = response.body ?: throw UpdateException("Пустой ответ GitHub")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size.takeIf { it > 0 }
                    var done = 0L
                    var reported = -1
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                val percent = total?.let { (done * 100 / it).toInt() } ?: -1
                                if (percent != reported) {
                                    reported = percent
                                    onProgress(total?.let { (done.toFloat() / it).coerceIn(0f, 1f) })
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                partial.delete()
                throw UpdateException("Загрузка прервалась. Проверьте интернет и попробуйте ещё раз")
            } catch (e: Throwable) {
                partial.delete()
                throw e
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(release.sha256.trim(), ignoreCase = true)) {
                partial.delete()
                throw UpdateException("Файл не совпал с опубликованным — возможно, GitHub ещё выкладывает новую сборку. Попробуйте через пару минут")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw UpdateException("Не удалось сохранить файл обновления")
            }
            target
        }

    private fun httpError(code: Int): String = when (code) {
        404 -> "На GitHub пока нет опубликованной сборки"
        403, 429 -> "GitHub временно ограничил запросы. Попробуйте позже"
        in 500..599 -> "GitHub сейчас недоступен ($code)"
        else -> "Ошибка GitHub ($code)"
    }

    companion object {
        const val MANIFEST_URL = "https://github.com/MAppleDm/VibeNotes/releases/latest/download/version.json"
    }
}
