package com.stash.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [classifyPlaybackError] — the rule that decides whether a
 * [androidx.media3.common.PlaybackException] on the current item is a
 * per-track local skip or a streaming-source failure that must go through
 * the [StreamErrorCascadeGuard].
 *
 * Regression context: malformed/empty streaming responses surface as
 * ERROR_CODE_PARSING_CONTAINER_MALFORMED (a "non-IO" code). The old handler
 * treated every non-IO error as an unconditional per-track skip, bypassing
 * the cascade guard — so a degraded source machine-gunned the whole queue
 * (hundreds of skip-nexts in seconds). The fix keys the decision on the
 * item's URI scheme: only genuinely LOCAL files are per-track skips; anything
 * streamed (or with no resolvable URI) is a source failure that the guard
 * counts and can halt.
 */
class PlaybackErrorPolicyTest {

    @Test fun `https stream uses cascade policy`() =
        assertEquals(PlaybackErrorPolicy.STREAMING_CASCADE, classifyPlaybackError("https"))

    @Test fun `http stream uses cascade policy`() =
        assertEquals(PlaybackErrorPolicy.STREAMING_CASCADE, classifyPlaybackError("http"))

    @Test fun `local file uri is a per-track skip`() =
        assertEquals(PlaybackErrorPolicy.LOCAL_SKIP, classifyPlaybackError("file"))

    @Test fun `content uri is a per-track skip`() =
        assertEquals(PlaybackErrorPolicy.LOCAL_SKIP, classifyPlaybackError("content"))

    @Test fun `android resource uri is a per-track skip`() =
        assertEquals(PlaybackErrorPolicy.LOCAL_SKIP, classifyPlaybackError("android.resource"))

    @Test fun `scheme match is case-insensitive`() =
        assertEquals(PlaybackErrorPolicy.LOCAL_SKIP, classifyPlaybackError("FILE"))

    @Test fun `null scheme (URI-less streaming item) cascades, never machine-guns`() =
        // A streaming-only track that reached the player without a resolved
        // URI must HALT after the threshold, not skip-storm the queue.
        assertEquals(PlaybackErrorPolicy.STREAMING_CASCADE, classifyPlaybackError(null))
}
