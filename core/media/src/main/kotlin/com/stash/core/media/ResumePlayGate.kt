package com.stash.core.media

/**
 * Decides whether a Bluetooth / Android Auto playback-resumption should
 * actually start playing once the restored queue lands on the player.
 *
 * Media3 is supposed to auto-play after
 * [androidx.media3.session.MediaLibraryService.MediaLibrarySession.Callback.onPlaybackResumption]
 * on a real play request, but from a warm process that auto-play doesn't
 * reliably take and the queue lands paused. The host service arms this gate
 * when resumption returns items and consults it on the next timeline change
 * to force playback.
 *
 * The decision logic lives here (free of Android `Player`/`Timeline` types)
 * so it can be unit-tested on the JVM, mirroring the [PlaybackResumer]
 * extraction. The service maps the returned [Action] onto real player calls.
 *
 * Not thread-safe: arm + consume both happen on the player's application
 * thread.
 */
class ResumePlayGate {

    enum class Action {
        /** Do nothing. */
        NONE,

        /** Player already has media prepared — just start playback. */
        PLAY,

        /** Player is idle — prepare first, then start playback. */
        PREPARE_THEN_PLAY,
    }

    private var armed = false

    /**
     * Arms (or disarms) the gate from `onPlaybackResumption`.
     *
     * @param isForPlayback Media3's flag: `true` for a genuine play request
     *   (should end in playback), `false` for boot-time notification
     *   population (must never start playing on its own). Passing `false`
     *   leaves the gate closed.
     */
    fun arm(isForPlayback: Boolean) {
        armed = isForPlayback
    }

    /**
     * Consulted on every timeline change. Returns the play action to apply
     * and consumes the latch (one-shot) only once the restored queue has
     * actually landed ([windowCount] > 0); an empty intermediate timeline
     * leaves the gate armed so the real queue still triggers playback.
     */
    fun onTimelineChanged(windowCount: Int, isIdle: Boolean): Action {
        if (!armed || windowCount == 0) return Action.NONE
        armed = false
        return if (isIdle) Action.PREPARE_THEN_PLAY else Action.PLAY
    }
}
