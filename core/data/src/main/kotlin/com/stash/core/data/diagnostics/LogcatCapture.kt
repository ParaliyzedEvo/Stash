package com.stash.core.data.diagnostics

import android.content.Context
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuously tails THIS app's own logcat (no READ_LOGS needed — the log daemon
 * returns the caller's own UID lines) into a rotating file under
 * cacheDir/diagnostics, so a diagnostics bundle can include the lead-up to a
 * failure even after a crash/restart. Best-effort: if the OEM blocks the spawn,
 * it logs a warning and the bundle simply omits logs.
 */
@Singleton
open class LogcatCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Overridable in tests for a small rotation cap.
    internal open val maxBytes: Long = 512L * 1024

    private val dir: File get() = File(context.cacheDir, "diagnostics")
    private val active: File get() = File(dir, ACTIVE)
    private val rotated: File get() = File(dir, ROTATED)

    @Volatile private var started = false

    /** Start the background tail. Idempotent. Call once at app init. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Thread {
            runCatching {
                dir.mkdirs()
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "threadtime", "--pid", Process.myPid().toString()),
                )
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        append(line)
                        line = reader.readLine()
                    }
                }
            }.onFailure { Log.w(TAG, "logcat capture unavailable; diagnostics will omit logs", it) }
        }.apply { isDaemon = true; name = "stash-logcat-capture"; start() }
    }

    /** Append one line; rotate active->rotated when the active file passes [maxBytes]. */
    @Synchronized
    internal fun append(line: String) {
        runCatching {
            dir.mkdirs()
            if (active.exists() && active.length() >= maxBytes) {
                rotated.delete()
                active.renameTo(rotated)
            }
            active.appendText(line + "\n")
        }
    }

    /** Return the last [maxLines] lines across the rotated + active files. */
    fun recentLogs(maxLines: Int = 1500): String = runCatching {
        val all = buildList {
            if (rotated.exists()) addAll(rotated.readLines())
            if (active.exists()) addAll(active.readLines())
        }
        all.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    companion object {
        private const val TAG = "LogcatCapture"
        private const val ACTIVE = "applog.txt"
        private const val ROTATED = "applog.1.txt"
    }
}
