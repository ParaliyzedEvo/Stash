package com.stash.core.data.diagnostics

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleBuilderTest {
    private val crashFileStore: CrashFileStore = mockk(relaxed = true) {
        every { deviceMetadataBlock() } returns "App version:    0.9.42 (versionCode 78)\n"
        every { allCrashFiles() } returns emptyList()
    }
    private val logcatCapture: LogcatCapture = mockk { every { recentLogs(any()) } returns "log line one" }
    private val syncHistoryDao: SyncHistoryDao = mockk { every { getRecentSyncs(any()) } returns flowOf(emptyList()) }
    private val downloadQueueDao: DownloadQueueDao = mockk {
        coEvery { getStatusCounts() } returns emptyList()
        every { getFailedDownloads() } returns flowOf(emptyList())
        every { getUnmatchedCount() } returns flowOf(0)
    }
    private val trackBlocklistDao: TrackBlocklistDao = mockk { every { observeCount() } returns flowOf(3) }
    private val sourceAccountDao: SourceAccountDao = mockk { every { getAll() } returns flowOf(emptyList()) }
    private val tokenManager: TokenManager = mockk {
        every { spotifyAuthState } returns MutableStateFlow(AuthState.NotConnected)
        every { youTubeAuthState } returns MutableStateFlow(AuthState.NotConnected)
    }

    private fun builder() = DiagnosticsBundleBuilder(
        mockk(relaxed = true), syncHistoryDao, downloadQueueDao, trackBlocklistDao,
        sourceAccountDao, tokenManager, crashFileStore, logcatCapture,
    )

    @Test fun `bundle text includes header, sections, and logs`() = runTest {
        val text = builder().buildText()
        assertTrue(text.contains("App version:"))
        assertTrue(text.contains("log line one"))
        assertTrue(text.contains("Blocklist: 3"))
    }

    @Test fun `secrets in captured logs are redacted in the final bundle`() = runTest {
        io.mockk.every { logcatCapture.recentLogs(any()) } returns
            "12:00 I OkHttp: Cookie: sp_dc=TOPSECRETVALUE; user=alice@example.com"
        val text = builder().buildText()
        assertTrue(text.contains("[REDACTED")) // redaction fired
        org.junit.Assert.assertFalse(text.contains("TOPSECRETVALUE"))
        org.junit.Assert.assertFalse(text.contains("alice@example.com"))
    }

    @Test fun `a failing data source degrades to an unavailable note, not a crash`() = runTest {
        coEvery { downloadQueueDao.getStatusCounts() } throws RuntimeException("db locked")
        val text = builder().buildText()
        assertTrue(text.contains("unavailable"))
        assertTrue(text.contains("log line one")) // rest still built
    }
}
