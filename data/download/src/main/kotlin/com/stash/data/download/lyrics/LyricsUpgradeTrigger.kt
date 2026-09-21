package com.stash.data.download.lyrics

/**
 * Seam so `:data:download` (retag) can kick the word-synced lyrics upgrade without depending on
 * `:data:lyrics`. Same pattern as [LyricsFetchTrigger]; binding lives in `:app`.
 * Idempotent: unique work, KEEP, so calling it while a run is queued/active is a no-op.
 */
interface LyricsUpgradeTrigger {
    fun enqueueTtmlUpgrade()
}