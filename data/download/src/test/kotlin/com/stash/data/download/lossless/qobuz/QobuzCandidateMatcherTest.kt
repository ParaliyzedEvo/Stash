package com.stash.data.download.lossless.qobuz

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.TrackQuery
import org.junit.Test

/**
 * Characterization tests for [QobuzCandidateMatcher] — the scorer extracted
 * (behavior-preserving) out of [QobuzSource]. These pin the EXACT scoring the
 * old `QobuzSource.confidence` produced so the qbdlx source can reuse it.
 *
 * The helper primitives (`normalize`/`jaccard`/`artistSimilarity`) keep their
 * own coverage in [QobuzSourceTest] via the delegating shims, so they aren't
 * re-tested here.
 */
class QobuzCandidateMatcherTest {

    @Test fun `ISRC match short-circuits to 0_95`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                isrc = "USWB10003085",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = "USWB10003085",
            candDurationSec = 160,
            candStreamable = true,
        )
        assertThat(score).isEqualTo(0.95f)
    }

    @Test fun `ISRC match is case-insensitive`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = "x", title = "y", isrc = "uswb10003085"),
            candTitle = "totally different",
            candArtist = "someone else",
            candIsrc = "USWB10003085",
            candDurationSec = 0,
            candStreamable = true,
        )
        assertThat(score).isEqualTo(0.95f)
    }

    @Test fun `perfect title+artist+duration agreement scores 1_0`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = null,
            candDurationSec = 160,
            candStreamable = true,
        )
        // titleSim 1.0 * artistSim 1.0 * durationFactor 1.0
        assertThat(score).isEqualTo(1.0f)
    }

    @Test fun `dramatic duration mismatch downweights to 0_3`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = null,
            candDurationSec = 200, // 25% drift → 0.3 factor
            candStreamable = true,
        )
        assertThat(score).isWithin(0.0001f).of(0.3f)
    }

    @Test fun `non-streamable candidate scores 0`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                isrc = "USWB10003085",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = "USWB10003085",
            candDurationSec = 160,
            candStreamable = false,
        )
        assertThat(score).isEqualTo(0f)
    }

    @Test fun `unknown query duration skips the duration penalty`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = "John Frusciante", title = "Murderers", durationMs = null),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = null,
            candDurationSec = 999,
            candStreamable = true,
        )
        // durationFactor forced to 1.0 → 1.0 * 1.0 * 1.0
        assertThat(score).isEqualTo(1.0f)
    }

    @Test fun `MIN_CONFIDENCE threshold value is preserved`() {
        assertThat(QobuzCandidateMatcher.MIN_CONFIDENCE).isEqualTo(0.5f)
    }
}
