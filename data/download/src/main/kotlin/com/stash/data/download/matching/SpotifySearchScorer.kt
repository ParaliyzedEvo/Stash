package com.stash.data.download.matching

import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.spotify.SpotifyTrackCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Pure, bulletproof scorer for the Spotify-URI resolver. Given our local
 * [TrackQuery] and a list of Spotify `/search` candidates, it returns the ONE
 * candidate that is *safe* to accept (route to antra), or null.
 *
 * Correctness philosophy: NEVER accept a wrong recording (live / remaster /
 * cover / sped-up / karaoke / alt-mix). A missed match is fine; a wrong match
 * is unacceptable. When unsure, return null.
 */
@Singleton
class SpotifySearchScorer @Inject constructor(private val matcher: TrackMatcher) {

    data class Decision(val accepted: SpotifyTrackCandidate?, val reason: String)

    fun pick(track: TrackQuery, candidates: List<SpotifyTrackCandidate>): Decision {
        val passers = candidates.filter { passes(track, it) }
        val best = passers.minWithOrNull(
            compareBy<SpotifyTrackCandidate> { durDeltaSec(track, it) }
                .thenByDescending { titleSim(track, it) },
        ) ?: return Decision(null, "no candidate passed gates")

        // Abstain when another passer is genuinely indistinguishable from the
        // best — within AMBIGUOUS_TITLE_SIM and AMBIGUOUS_DUR_SEC — UNLESS it
        // is the SAME RECORDING (identical canonical title + artist line-up,
        // duration within SAME_RECORDING_DUR_SEC). Popular tracks appear on
        // the album plus N compilations as literal duplicates of one master
        // (device-confirmed 2026-06-10: "Stairway to Heaven - Remaster" on
        // two IV editions, "That's All I Ask" on three albums 80ms apart);
        // accepting any duplicate is risk-free, so duplicates never abstain.
        val bestDur = durDeltaSec(track, best)
        val bestSim = titleSim(track, best)
        val ambiguous = passers.any { other ->
            other !== best &&
                abs(titleSim(track, other) - bestSim) <= AMBIGUOUS_TITLE_SIM &&
                abs(durDeltaSec(track, other) - bestDur) <= AMBIGUOUS_DUR_SEC &&
                !sameRecording(best, other)
        }
        if (ambiguous) return Decision(null, "ambiguous")

        return Decision(best, "accepted")
    }

    /**
     * Two candidates are the same recording when their canonical titles match
     * exactly, their durations are within [SAME_RECORDING_DUR_SEC], and their
     * artist line-ups are identical as canonical SETS (set equality — NOT the
     * per-part gate used against our track, so "Artist" vs "Artist Tribute"
     * or an added feat. credit stays distinct and still abstains).
     */
    private fun sameRecording(a: SpotifyTrackCandidate, b: SpotifyTrackCandidate): Boolean {
        if (matcher.canonicalTitle(a.name) != matcher.canonicalTitle(b.name)) return false
        if (abs(a.durationMs - b.durationMs) / 1000 > SAME_RECORDING_DUR_SEC) return false
        val aArtists = a.artists.map { matcher.canonicalArtist(it) }.toSet()
        val bArtists = b.artists.map { matcher.canonicalArtist(it) }.toSet()
        return aArtists.isNotEmpty() && aArtists == bArtists
    }

    private fun passes(track: TrackQuery, cand: SpotifyTrackCandidate): Boolean {
        val durKnown = track.durationMs != null && track.durationMs!! > 0 && cand.durationMs > 0
        if (!durKnown) return false
        if (durDeltaSec(track, cand) > DUR_TOLERANCE_SEC) return false
        if (titleSim(track, cand) < TITLE_SIM_THRESHOLD) return false
        if (!artistOk(track, cand)) return false
        if (versionConflict(track.title, cand.name)) return false
        return true
    }

    /**
     * The PRIMARY track-artist part must match some `cand.artists[]` element
     * (Jaro-Winkler on canonicalArtist >= 0.85, or token-run containment), AND
     * every ADDITIONAL track-artist part must likewise be a member of
     * `cand.artists`. Order-insensitive, per-element — never join cand.artists.
     */
    private fun artistOk(track: TrackQuery, cand: SpotifyTrackCandidate): Boolean {
        val parts = ArtistMatching.artistParts(track.artist)
        if (parts.isEmpty()) return false
        return parts.all { part -> cand.artists.any { artistPartMatches(part, it) } }
    }

    private fun artistPartMatches(trackPart: String, candArtist: String): Boolean {
        val jw = matcher.jaroWinklerSimilarity(
            matcher.canonicalArtist(trackPart),
            matcher.canonicalArtist(candArtist),
        )
        if (jw >= ARTIST_SIM_THRESHOLD) return true
        // Token-run containment: the candidate artist's tokens contain the
        // track-part's tokens as a contiguous run (handles "feat."-style
        // sub-credits and minor word-order/punctuation drift).
        val candTokens = candArtist.lowercase().split(Regex("""\W+""")).filter { it.isNotEmpty() }
        val partTokens = trackPart.lowercase().split(Regex("""\W+""")).filter { it.isNotEmpty() }
        return ArtistMatching.containsRun(candTokens, partTokens)
    }

    /**
     * Version veto over RAW lowercased titles (never canonical). For each
     * disqualifying token, its presence as a whole-word / contiguous
     * word-sequence must be the SAME in both titles. Present in one but not
     * the other → conflict. (Symmetric: both or neither is fine.)
     */
    private fun versionConflict(trackTitle: String, candName: String): Boolean {
        val a = trackTitle.lowercase()
        val b = candName.lowercase()
        return VERSION_TOKENS.any { token ->
            containsWord(a, token) != containsWord(b, token)
        }
    }

    /** True when [token] appears as a whole word / contiguous word-sequence in [haystack]. */
    private fun containsWord(haystack: String, token: String): Boolean {
        val pattern = """(?<![\p{L}\p{N}])${Regex.escape(token)}(?![\p{L}\p{N}])"""
        return Regex(pattern).containsMatchIn(haystack)
    }

    private fun titleSim(track: TrackQuery, cand: SpotifyTrackCandidate): Double =
        matcher.jaroWinklerSimilarity(
            matcher.canonicalTitle(track.title),
            matcher.canonicalTitle(cand.name),
        )

    private fun durDeltaSec(track: TrackQuery, cand: SpotifyTrackCandidate): Long =
        abs(track.durationMs!! - cand.durationMs) / 1000

    private companion object {
        /**
         * 10s (was 4s): YouTube-sourced durations carry video padding —
         * official-video rips run several seconds longer than the album audio
         * (device-confirmed 2026-06-10: "Hurricane" video 190s vs album 181.2s,
         * "Venus" rip 182s vs canonical 187.5s). Wrong-version protection
         * stays with the title gate, artist gate, version-token veto, and the
         * ambiguity abstain.
         */
        const val DUR_TOLERANCE_SEC = 10L
        const val TITLE_SIM_THRESHOLD = 0.92
        const val ARTIST_SIM_THRESHOLD = 0.85

        /** Two passers within these deltas of the best are treated as ambiguous. */
        const val AMBIGUOUS_TITLE_SIM = 0.02
        const val AMBIGUOUS_DUR_SEC = 2L

        /** Duration window within which two same-title/same-artist candidates
         * count as one recording (edition-to-edition drift is sub-second). */
        const val SAME_RECORDING_DUR_SEC = 2L

        /**
         * Disqualifying version markers. If any of these is present (as a whole
         * word / contiguous word-sequence) in one title but not the other, the
         * candidate is a different recording and is vetoed. Multi-word entries
         * ("sped up", "radio edit", "taylor's version") match contiguously.
         */
        val VERSION_TOKENS = listOf(
            "live", "concert", "unplugged", "session", "acoustic", "instrumental",
            "karaoke", "remaster", "remastered", "sped up", "spedup", "slowed",
            "nightcore", "cover", "remix", "rework", "edit", "radio edit",
            "extended", "demo", "mono", "re-recorded", "rerecorded",
            "taylor's version", "commentary", "mtv",
        )
    }
}
