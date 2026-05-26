package com.stash.core.data.sync.classifier

import com.stash.core.model.DownloadFailureType
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureClassifierTest {

    private val classifier = DownloadFailureClassifier()

    @Test fun `401 maps to AUTH_EXPIRED regardless of phase`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "HTTP Error 401: Unauthorized",
            httpStatus = 401,
        ))
        assertEquals(DownloadFailureType.AUTH_EXPIRED, result)
    }

    @Test fun `login-required text maps to AUTH_EXPIRED`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "Sign in to confirm you're not a bot. Use --cookies",
        ))
        assertEquals(DownloadFailureType.AUTH_EXPIRED, result)
    }

    @Test fun `video unavailable maps to PROVIDER_UNAVAILABLE`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ERROR: Video unavailable. This video is no longer available.",
        ))
        assertEquals(DownloadFailureType.PROVIDER_UNAVAILABLE, result)
    }

    @Test fun `socket timeout cause maps to NETWORK`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = null,
            causeChain = listOf("SocketTimeoutException"),
        ))
        assertEquals(DownloadFailureType.NETWORK, result)
    }

    @Test fun `unknown host text maps to NETWORK`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.MATCHING,
            errorText = "Unable to resolve host \"i.ytimg.com\": No address associated with hostname",
        ))
        assertEquals(DownloadFailureType.NETWORK, result)
    }

    @Test fun `ffmpeg exit code at PROCESSING maps to FFMPEG_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.PROCESSING,
            errorText = "ffmpeg exited with code 234: muxer not found",
        ))
        assertEquals(DownloadFailureType.FFMPEG_ERROR, result)
    }

    @Test fun `ffmpeg exit code at DOWNLOADING maps to FFMPEG_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ffmpeg exited with code 234: muxer not found",
        ))
        assertEquals(DownloadFailureType.FFMPEG_ERROR, result)
    }

    @Test fun `ENOSPC at STORAGE phase maps to STORAGE_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.STORAGE,
            errorText = "ENOSPC: no space left on device",
        ))
        assertEquals(DownloadFailureType.STORAGE_ERROR, result)
    }

    @Test fun `unrecognised text maps to UNKNOWN`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "some weird error nobody has ever seen",
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }

    @Test fun `null errorText with no cause and no status maps to UNKNOWN`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = null,
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }

    // ── isTerminal ─────────────────────────────────────────────────────
    // Terminal == the track itself is broken (provider refused / ffmpeg
    // refuses). Non-terminal == the sync got interrupted; the next sync
    // will pick the row back up at PENDING.

    @Test fun `isTerminal PROVIDER_UNAVAILABLE is true`() {
        assertEquals(true, DownloadFailureClassifier.isTerminal(DownloadFailureType.PROVIDER_UNAVAILABLE))
    }

    @Test fun `isTerminal FFMPEG_ERROR is true`() {
        assertEquals(true, DownloadFailureClassifier.isTerminal(DownloadFailureType.FFMPEG_ERROR))
    }

    @Test fun `isTerminal NETWORK is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.NETWORK))
    }

    @Test fun `isTerminal AUTH_EXPIRED is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.AUTH_EXPIRED))
    }

    @Test fun `isTerminal STORAGE_ERROR is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.STORAGE_ERROR))
    }

    @Test fun `isTerminal UNKNOWN is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.UNKNOWN))
    }

    @Test fun `isTerminal NO_MATCH is false (lane separation - owned by FailedMatchesScreen)`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.NO_MATCH))
    }

    @Test fun `isTerminal NONE is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.NONE))
    }
}
