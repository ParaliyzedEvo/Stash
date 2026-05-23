package com.stash.core.common

/**
 * Read-only accessor for the binary's identity. Lives in `:core:common`
 * so modules that can't depend on `:app` (and therefore can't see
 * the generated `BuildConfig`) — like `:data:download` — can still
 * read the version name + code via Hilt injection.
 *
 * Generalises the existing `versionCodeProvider: () -> Int` lambda
 * pattern from `YouTubeHistoryScrobbler` to also surface the version
 * name string used in tag-writing.
 */
interface AppVersionProvider {
    /** Human-readable version, e.g. `"0.9.35"`. Matches `app/build.gradle.kts` versionName. */
    val versionName: String

    /** Monotonically-increasing integer version. Matches `app/build.gradle.kts` versionCode. */
    val versionCode: Int
}
