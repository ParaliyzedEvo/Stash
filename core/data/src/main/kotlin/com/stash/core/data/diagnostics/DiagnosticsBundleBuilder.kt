package com.stash.core.data.diagnostics

import android.content.Context
import android.net.Uri
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncStepResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a single, self-contained diagnostics bundle for the user to share
 * when reporting a problem. Pulls a snapshot from every relevant subsystem
 * (auth, sync history, downloads, blocklist, crash reports, logs), assembles
 * them into a plain-text report, then runs the WHOLE thing through
 * [DiagnosticsRedactor] as a final secret-scrubbing pass.
 *
 * PRIVACY: auth is reported as connected/not-connected booleans plus
 * connection timestamps — never tokens, emails, display names, or avatar URLs.
 * The redactor is a backstop for secrets that leak through error strings and
 * log lines; the assembler itself is responsible for not emitting PII in the
 * first place.
 *
 * RESILIENCE: every section is wrapped in its own [runCatching] so that a
 * single failing data source (e.g. a locked DB) degrades to an inline
 * "[<section> unavailable: …]" note instead of sinking the entire bundle.
 */
@Singleton
class DiagnosticsBundleBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncHistoryDao: SyncHistoryDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackBlocklistDao: TrackBlocklistDao,
    private val sourceAccountDao: SourceAccountDao,
    private val tokenManager: TokenManager,
    private val crashFileStore: CrashFileStore,
    private val logcatCapture: LogcatCapture,
) {

    /** A built bundle: the redacted report text, the on-disk file, and a shareable URI. */
    data class DiagnosticsBundle(
        val text: String,
        val file: File,
        val contentUri: Uri,
    )

    /**
     * Assemble the full report and return it redacted. Each section is
     * independently fault-isolated; the final pass redacts the assembled text.
     */
    internal suspend fun buildText(): String {
        val sections = listOf(
            section("Header") { crashFileStore.deviceMetadataBlock() },
            section("Connection") { connectionSection() },
            section("Recent sync history") { syncHistorySection() },
            section("Downloads") { downloadsSection() },
            section("Counts") { countsSection() },
            section("Recent crash reports") { crashReportsSection() },
            section("Recent logs") { "== Recent logs ==\n" + logcatCapture.recentLogs(1500) },
        )
        val assembled = sections.joinToString("\n\n")
        return DiagnosticsRedactor.redact(assembled)
    }

    private inline fun section(name: String, block: () -> String): String =
        runCatching { block() }.getOrElse { "[$name unavailable: ${it.message}]" }

    // ── Section 2: Connection ───────────────────────────────────────────────

    private suspend fun connectionSection(): String {
        val accounts = sourceAccountDao.getAll().first()
        return buildString {
            appendLine("== Connection ==")
            appendLine(connectionLine("Spotify", tokenManager.spotifyAuthState.value, accounts, MusicSource.SPOTIFY))
            append(connectionLine("YouTube", tokenManager.youTubeAuthState.value, accounts, MusicSource.YOUTUBE))
        }
    }

    private fun connectionLine(
        label: String,
        authState: AuthState,
        accounts: List<SourceAccountEntity>,
        source: MusicSource,
    ): String {
        val connected = authState is AuthState.Connected
        // PRIVACY: from the row read ONLY source/connectedAt/lastSyncAt; never
        // email/displayName/avatarUrl. From AuthState.Connected never the user.
        val row = accounts.firstOrNull { it.source == source }
        return buildString {
            append("$label: ${if (connected) "Connected" else "Not connected"}")
            if (row != null) {
                append(" (connected_at=${row.connectedAt}, last_sync_at=${row.lastSyncAt})")
            }
        }
    }

    // ── Section 3: Recent sync history ──────────────────────────────────────

    private suspend fun syncHistorySection(): String {
        val rows = syncHistoryDao.getRecentSyncs(10).first()
        return buildString {
            appendLine("== Recent sync history ==")
            if (rows.isEmpty()) {
                append("none")
                return@buildString
            }
            rows.forEachIndexed { index, row ->
                appendLine(
                    "- ${row.startedAt} | ${row.status} | ${row.trigger} | " +
                        "checked=${row.playlistsChecked} found=${row.newTracksFound} " +
                        "downloaded=${row.tracksDownloaded} failed=${row.tracksFailed}",
                )
                row.errorMessage?.let { appendLine("    error: $it") }
                val diagnostics = row.diagnostics
                if (!diagnostics.isNullOrBlank()) {
                    val steps = runCatching {
                        Json.decodeFromString<List<SyncStepResult>>(diagnostics)
                    }.getOrElse {
                        appendLine("    [steps unavailable: ${it.message}]")
                        emptyList()
                    }
                    steps.forEach { step ->
                        append(
                            "    ${step.service} · ${step.step} · ${step.status} · " +
                                "http=${step.httpCode} · items=${step.itemCount}",
                        )
                        step.errorMessage?.let { append(" · $it") }
                        appendLine()
                    }
                }
                // Trim trailing newline only on the very last row.
                if (index == rows.lastIndex) deleteCharAt(length - 1)
            }
        }
    }

    // ── Section 4: Downloads ────────────────────────────────────────────────

    private suspend fun downloadsSection(): String {
        val counts = downloadQueueDao.getStatusCounts()
        val failed = downloadQueueDao.getFailedDownloads().first().take(20)
        val unmatched = downloadQueueDao.getUnmatchedCount().first()
        return buildString {
            appendLine("== Downloads ==")
            if (counts.isEmpty()) {
                appendLine("Status counts: none")
            } else {
                counts.forEach { appendLine("${it.status}: ${it.count}") }
            }
            appendLine("Unmatched: $unmatched")
            if (failed.isEmpty()) {
                append("Failed: none")
            } else {
                appendLine("Failed:")
                failed.forEachIndexed { index, f ->
                    append(
                        "- ${f.artist} - ${f.title} [${f.failureType}] " +
                            "retries=${f.retryCount} ${f.errorMessage.orEmpty()}",
                    )
                    if (index != failed.lastIndex) appendLine()
                }
            }
        }
    }

    // ── Section 5: Counts ───────────────────────────────────────────────────

    private suspend fun countsSection(): String {
        val blocklist = trackBlocklistDao.observeCount().first()
        return buildString {
            appendLine("== Counts ==")
            append("Blocklist: $blocklist")
        }
    }

    // ── Section 6: Recent crash reports ─────────────────────────────────────

    private fun crashReportsSection(): String {
        val files = crashFileStore.allCrashFiles().take(2)
        return buildString {
            appendLine("== Recent crash reports ==")
            if (files.isEmpty()) {
                append("none")
                return@buildString
            }
            files.forEachIndexed { index, file ->
                appendLine("--- ${file.name} ---")
                append(file.readText())
                if (index != files.lastIndex) appendLine()
            }
        }
    }

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Build the bundle and persist it to a single rotating file under
     * `cacheDir/diagnostics`, deleting any older `stash-diagnostics-*.txt`
     * first so only the newest remains. Returns a shareable [DiagnosticsBundle].
     */
    suspend fun build(): DiagnosticsBundle {
        val text = buildText()
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles { f -> f.isFile && f.name.startsWith("stash-diagnostics-") && f.name.endsWith(".txt") }
            ?.forEach { runCatching { it.delete() } }
        val file = File(dir, "stash-diagnostics-${System.currentTimeMillis()}.txt")
        file.writeText(text)
        val uri = crashFileStore.shareUriFor(file)
        return DiagnosticsBundle(text = text, file = file, contentUri = uri)
    }
}
