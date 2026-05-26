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
        // YT InnerTube browse against the user's library — an unauthenticated
        // request returns Empty per YTMusicApiClient.getUserPlaylists kdoc.
        when (api.getUserPlaylists()) {
            is SyncResult.Empty -> true
            is SyncResult.Success -> false
            is SyncResult.Error -> false // conservative — could be network
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
