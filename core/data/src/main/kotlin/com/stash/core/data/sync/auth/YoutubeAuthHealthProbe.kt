package com.stash.core.data.sync.auth

import android.util.Log
import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.YTMusicApiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YoutubeAuthHealthProbe @Inject constructor(
    private val api: YTMusicApiClient,
) : AuthHealthProbe {
    override val source: AuthSource = AuthSource.YOUTUBE

    override suspend fun isExpired(): Boolean = try {
        // IMPORTANT: emptiness is NOT an expiry signal.
        //
        // getUserPlaylists() returns SyncResult.Empty in TWO cases that are
        // indistinguishable from here: (a) a logged-out request, and (b) a
        // fully-authenticated account that simply has no playlists in its
        // YouTube Music library — very common for users who linked YouTube
        // only as a download source. The old `Empty -> true` mapping
        // false-flagged case (b) as "credentials expired", which then
        // short-circuited the ENTIRE sync (Spotify included) and surfaced a
        // bogus "re-authenticate YouTube" prompt that no cookie re-paste
        // could ever clear (the cookie was never the problem).
        //
        // We can't positively detect YT de-auth from a content browse:
        // getUserPlaylists() collapses a 401 into SyncResult.Error (the
        // InnerTube layer turns an unauthorized browse into a null body, not a
        // distinct status code), so this probe is deliberately conservative and
        // never reports expired.
        //
        // KNOWN GAP: genuine YouTube cookie expiry is therefore NOT proactively
        // detected here — YT just returns no data until the user re-pastes a
        // cookie. Unlike Spotify's sp_dc there is no silent YT re-auth flow to
        // drive, so a missing banner is tolerable; the false POSITIVE this
        // replaced was actively blocking sync, which is far worse. To restore a
        // real signal, thread the 401 status from InnerTubeClient.browse()
        // through getUserPlaylists() and map it to `true` here.
        when (api.getUserPlaylists()) {
            is SyncResult.Empty -> false // authed-but-empty is NOT logged-out; see above
            is SyncResult.Success -> false
            is SyncResult.Error -> false // conservative — 401 and network errors are indistinguishable here
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Throwable) {
        Log.w(TAG, "probe failed conservatively", e)
        false
    }

    private companion object {
        const val TAG = "YtAuthProbe"
    }
}
