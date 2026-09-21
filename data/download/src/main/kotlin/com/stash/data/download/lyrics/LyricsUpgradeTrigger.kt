package com.stash.data.download.lyrics

import kotlinx.coroutines.flow.Flow

/**
 * Seam so `:data:download` (retag) and the settings UI can drive the lyrics catch-up work without
 * depending on `:data:lyrics`. Same pattern as [LyricsFetchTrigger]; binding lives in `:app`.
 */
interface LyricsUpgradeTrigger {
    /** Auto run: upgrade stored lyrics only. Unique work, KEEP: no-op while one is queued/active. */
    fun enqueueTtmlUpgrade()

    /**
     * Manual run (Library Health button): also fetches missing lyrics, on any connected network.
     * REPLACES a queued/active auto run so a run stuck waiting for unmetered Wi-Fi can be forced.
     */
    fun enqueueManualFetch()

    /** Live state of the (auto or manual) lyrics work, for the Library Health card. */
    val status: Flow<LyricsFetchStatus>
}

sealed interface LyricsFetchStatus {
    data object Idle : LyricsFetchStatus
    /** Enqueued but waiting on constraints (network / battery). */
    data object Queued : LyricsFetchStatus
    data class Running(val done: Int, val total: Int) : LyricsFetchStatus
    data class Done(
        val upgraded: Int,
        val fetched: Int,
        val notFound: Int,
        val failed: Int,
        /** Stopped early because the lyrics service kept failing. */
        val bailed: Boolean,
    ) : LyricsFetchStatus
}