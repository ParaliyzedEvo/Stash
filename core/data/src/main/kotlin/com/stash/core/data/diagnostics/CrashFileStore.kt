package com.stash.core.data.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk store for uncaught-exception crash reports.
 *
 * Reports live in `cacheDir/crashes/` and are written by [CrashReporter]'s
 * global handler. Each file is a self-contained UTF-8 text dump: app
 * version + build/device metadata + full stack trace (including the
 * `Caused by` chain). The store keeps the most recent [MAX_FILES] and
 * deletes older ones — privacy-respecting (files never leave the device
 * unless the user explicitly shares one through Settings).
 *
 * The FileProvider authority is `${applicationId}.fileprovider`,
 * declared in `app/src/main/AndroidManifest.xml` with `file_paths.xml`
 * exposing `crashes/` under the cache root. The grant flag in the
 * share Intent (added by callers) makes the resulting `content://`
 * URI readable by the chosen target app for one consumption.
 */
@Singleton
open class CrashFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Lazy directory handle — created on first write. cacheDir is always
     * present on Android, so the only failure mode here is a full
     * filesystem, which would surface as IOException at write time.
     */
    private val crashDir: File
        get() = File(context.cacheDir, "crashes")

    /**
     * Format a report for [throwable] (raised on [thread]) and write it to
     * a new file, then rotate. All file IO is wrapped — if anything
     * fails (cache full, permission revoked) we Log.w but never throw,
     * because we're being invoked from the uncaught-exception handler and
     * raising here would mask the original crash from the OS.
     */
    open fun writeCrash(thread: Thread, throwable: Throwable) {
        runCatching {
            if (!crashDir.exists()) crashDir.mkdirs()
            val timestamp = TIMESTAMP_FORMAT.format(Date())
            val file = File(crashDir, "crash-$timestamp.txt")
            file.writeText(formatReport(thread, throwable))
            rotate()
        }.onFailure { e ->
            Log.w(TAG, "writeCrash failed", e)
        }
    }

    /** Newest crash file, or null if none exist. */
    fun latestCrashFile(): File? = allCrashFiles().firstOrNull()

    /** All crash files, newest first. */
    fun allCrashFiles(): List<File> {
        val files = crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    /**
     * Build a `content://` URI for [file] that can be attached to a share
     * Intent. Caller is responsible for adding [android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION].
     */
    fun shareUriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Keep the most recent [keepCount] files; delete the rest. Failures
     * are best-effort — a stuck delete doesn't matter beyond a few extra
     * KB of cache, and the next rotate pass picks up the slack.
     */
    fun rotate(keepCount: Int = MAX_FILES) {
        runCatching {
            val files = allCrashFiles()
            if (files.size <= keepCount) return
            files.drop(keepCount).forEach { f ->
                runCatching { f.delete() }
            }
        }
    }

    /**
     * Synchronous + side-effect-free. Exposed for tests.
     */
    internal fun formatReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("Stash crash report")
            appendLine("==================")
            append(deviceMetadataBlock())
            appendLine("Thread:         ${thread.name}")
            appendLine()
            appendLine("Stack trace")
            appendLine("-----------")
            append(sw.toString())
        }
    }

    /**
     * Single source for the device/app metadata header (Time → Build).
     * Reused by the crash report and by the diagnostics bundle so both
     * render an IDENTICAL block. Excludes crash-specific lines (e.g.
     * `Thread:`), which callers append themselves.
     */
    internal fun deviceMetadataBlock(): String {
        val versionInfo = appVersionInfo()
        return buildString {
            appendLine("Time:           ${TIMESTAMP_FORMAT.format(Date())} UTC")
            appendLine("App version:    ${versionInfo.versionName} (versionCode ${versionInfo.versionCode})")
            appendLine("Device:         ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Manufacturer:   ${Build.MANUFACTURER}")
            appendLine("Android:        ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            // One UI tag is non-public — only emit a generic Build line. The
            // build display already encodes the OEM build ID, which is enough
            // to spot Samsung-specific behaviour when triaging.
            appendLine("Build:          ${Build.DISPLAY}")
        }
    }

    private fun appVersionInfo(): VersionInfo {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            VersionInfo(info.versionName ?: "unknown", code)
        }.getOrDefault(VersionInfo("unknown", "unknown"))
    }

    private data class VersionInfo(val versionName: String, val versionCode: String)

    companion object {
        private const val TAG = "CrashFileStore"
        const val MAX_FILES = 10
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val SHARE_MIME_TYPE = "text/plain"

        // SimpleDateFormat is NOT thread-safe, but the crash handler is the
        // only writer and the test process is single-threaded. If we ever
        // surface multi-writer pressure, swap for ThreadLocal<SimpleDateFormat>.
        private val TIMESTAMP_FORMAT: SimpleDateFormat = SimpleDateFormat(
            "yyyy-MM-dd_HH-mm-ss",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
