package com.stash.core.data.social.ytmusic

import com.stash.data.ytmusic.InnerTubeClient
import javax.inject.Inject
import javax.inject.Singleton

class YtMusicLibraryException(message: String) : Exception(message)

/**
 * v0.9.13: Thin wrapper around [InnerTubeClient.likeVideo] /
 * [InnerTubeClient.removeLike] (v0.9.52). Translates a "false" return
 * into an exception so the dispatcher's `runCatching` lifts it into
 * `Result.failure`. Existing InnerTubeClient handles SAPISID-hash auth,
 * cookies, headers via its existing internal request path.
 */
@Singleton
class YtMusicLibraryApiClient @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
) {
    suspend fun likeVideo(videoId: String) {
        val ok = innerTubeClient.likeVideo(videoId)
        if (!ok) throw YtMusicLibraryException("InnerTube likeVideo($videoId) failed")
    }

    suspend fun removeLike(videoId: String) {
        val ok = innerTubeClient.removeLike(videoId)
        if (!ok) throw YtMusicLibraryException("InnerTube removeLike($videoId) failed")
    }
}
