package com.stash.core.data.discord

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single [DiscordRpcClient] for the connected account. Mirrors
 * [com.stash.core.data.lastfm.LastFmScrobbler]'s shape: a singleton that
 * observes session state and exposes one call for playback to invoke,
 * no-op when Discord isn't connected. Must be called once from
 * Application.onCreate, same as LastFmScrobbler.start().
 */
@Singleton
class DiscordRpcCoordinator @Inject constructor(
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var client: DiscordRpcClient? = null

    fun start() {
        scope.launch {
            tokenManager.discordAuthState.collect { state ->
                when (state) {
                    is AuthState.Connected -> {
                        if (client == null) {
                            val token = tokenManager.getDiscordUserToken() ?: return@collect
                            client = DiscordRpcClient(
                                userToken = token,
                                onUnauthorized = { scope.launch { tokenManager.clearAuth(AuthService.DISCORD) } },
                            )
                        }
                    }
                    else -> {
                        client?.clearNow()
                        client = null
                    }
                }
            }
        }
    }

    /**
     * Called on every track transition / play-pause. No-op if not connected.
     * [isPlaying] false clears presence rather than showing a stale
     * "Listening to X" while paused.
     */
    fun updateNowPlaying(title: String, artist: String, albumArtUrl: String?, isPlaying: Boolean) {
        val active = client ?: return
        if (title.isBlank() || !isPlaying) {
            active.requestActivity(null)
            return
        }
        active.requestActivity(
            DiscordActivity(
                name = "Stash",
                type = DiscordActivityType.Listening.value,
                details = title,
                state = artist,
                assets = DiscordActivity.Assets(
                    largeImage = albumArtUrl ?: FALLBACK_ICON_URL,
                    largeText = artist,
                ),
            ),
        )
    }

    companion object {
        // TODO: confirm the actual repo path/branch — placeholder for now.
        private const val FALLBACK_ICON_URL =
            "https://raw.githubusercontent.com/rawnaldclark/Stash/refs/heads/master/app/src/main/res/drawable/ic_launcher_foreground.png"
    }
}