package com.stash.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * No-UI trampoline that resumes the last-played queue and exits immediately.
 *
 * Surfaced as an app shortcut ("Resume playback") so a Samsung Bixby Routine
 * (or the launcher long-press menu) can target it. Unlike the generic "play
 * music" media-button action, launching an explicit activity cold-starts the
 * app and works behind the lock screen — which is exactly why the equivalent
 * VLC shortcut works from a dead process while a plain media key does not.
 *
 * The actual resume runs fire-and-forget on the repository's own scope
 * ([PlayerRepository.resumeLastQueue]), so this activity can finish right
 * away without a visible window (see Theme.NoDisplay in the manifest).
 */
@AndroidEntryPoint
class ResumePlaybackActivity : ComponentActivity() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerRepository.resumeLastQueue()
        finish()
    }
}
