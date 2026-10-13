package io.github.mappledm.vibenotes

import android.app.Application
import android.os.Build
import java.io.File
import java.time.ZonedDateTime

class VibeNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        container = AppContainer(this)
    }

    /**
     * Keeps the stack trace of a crash on the device (nothing is sent anywhere).
     * On the next start the app shows it so it can be copied into a bug report.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                val report = buildString {
                    appendLine("VibeNotes $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine(ZonedDateTime.now().toString())
                    appendLine()
                    append(error.stackTraceToString())
                }
                File(filesDir, CRASH_FILE).writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
