package com.stash.core.data.share

import android.util.Base64
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Only [Failed] is retried by the publish worker. */
enum class PublishOutcome { Unchanged, Published, Removed, Forbidden, Rejected, Failed }

/** Every share and follow operation (spec §5-6). */
@Singleton
class SharedMixRepository @Inject constructor(
    private val sharedMixDao: SharedMixDao,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val musicRepository: MusicRepository,
    private val api: ShareApiClient,
) {
    fun observe(playlistId: Long): Flow<SharedMixEntity?> = sharedMixDao.observeForPlaylist(playlistId)
    suspend fun forPlaylist(playlistId: Long): SharedMixEntity? = sharedMixDao.forPlaylist(playlistId)

    // ── Owner ──────────────────────────────────────────────────────────────────────────────

    /**
     * Build the document from the playlist's live members, in order (removed ones excluded).
     * Every field is clipped to the Worker's limits (worker src/validate.js) so one odd library
     * row can never make the whole mix unpublishable.
     */
    suspend fun buildDocument(playlistId: Long, name: String, sharedBy: String?): SharedMixDocument {
        val tracks = playlistDao.getTracksForPlaylist(playlistId).map { it.toDomain() }
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        return SharedMixDocument(
            name = name.trim().take(100),
            sharedBy = sharedBy?.trim()?.take(40)?.ifBlank { null },
            covers = tracks.mapNotNull { it.albumArtUrl?.takeIf { u -> u.startsWith("https://") && u.length <= 1000 } }.distinct().take(4),
            tracks = tracks.take(MAX_TRACKS).map { it.toSharedTrack().withinLimits() },
        )
    }

    private fun SharedTrack.withinLimits() = copy(
        title = title.take(500),
        artist = artist.take(500),
        album = album?.take(500),
        isrc = isrc?.takeIf { it.length <= 20 },
        spotifyId = spotifyId?.takeIf { it.length <= 40 },
        youtubeId = youtubeId?.takeIf { it.length <= 20 },
    )

    /** Create a link for [playlistId]; returns the https URL. */
    suspend fun share(playlistId: Long, name: String, sharedBy: String?, autoUpdate: Boolean): ShareResult<String> {
        val doc = buildDocument(playlistId, name, sharedBy)
        if (doc.tracks.isEmpty()) return ShareResult.Failed("This playlist has no songs to share.")
        val key = newEditKey()
        return when (val r = api.create(doc, key)) {
            is ShareResult.Ok -> {
                sharedMixDao.delete(playlistId) // a stale REMOVED row from an earlier share
                sharedMixDao.insert(
                    SharedMixEntity(
                        playlistId = playlistId, shareId = r.value.id, role = SharedMixEntity.ROLE_OWNER,
                        editKey = key, name = doc.name, version = r.value.version, contentHash = doc.contentHash(),
                        autoUpdate = autoUpdate, sharedBy = doc.sharedBy,
                    ),
                )
                ShareResult.Ok(ShareLinks.mixUrl(r.value.id))
            }
            is ShareResult.Failed -> r
            else -> ShareResult.Failed("Couldn't create the link.")
        }
    }

    /** Send a new version only if what followers would see changed. */
    suspend fun publishIfChanged(row: SharedMixEntity): PublishOutcome {
        val key = row.editKey ?: return PublishOutcome.Forbidden
        val doc = buildDocument(row.playlistId, row.name, row.sharedBy)
        if (doc.tracks.isEmpty()) return PublishOutcome.Unchanged // an emptied playlist isn't published
        val hash = doc.contentHash()
        if (hash == row.contentHash) return PublishOutcome.Unchanged
        return when (val r = api.update(row.shareId, doc, key, row.version)) {
            is ShareResult.Ok -> { sharedMixDao.upsert(row.copy(version = r.value, contentHash = hash)); PublishOutcome.Published }
            ShareResult.Gone, ShareResult.NotFound -> { sharedMixDao.upsert(row.copy(status = SharedMixEntity.STATUS_REMOVED)); PublishOutcome.Removed }
            ShareResult.Forbidden -> { Log.w(TAG, "edit key rejected for ${row.shareId}"); PublishOutcome.Forbidden }
            is ShareResult.Rejected -> { Log.w(TAG, "server rejected ${row.shareId}: HTTP ${r.code}"); PublishOutcome.Rejected }
            is ShareResult.Failed -> PublishOutcome.Failed
        }
    }

    suspend fun setAutoUpdate(playlistId: Long, on: Boolean) {
        sharedMixDao.forPlaylist(playlistId)?.let { sharedMixDao.upsert(it.copy(autoUpdate = on)) }
    }

    /** Remove the link. A remote 404/410 still clears the local row. */
    suspend fun stopSharing(playlistId: Long): Boolean {
        val row = sharedMixDao.forPlaylist(playlistId) ?: return true
        val r = api.delete(row.shareId, row.editKey.orEmpty())
        if (r is ShareResult.Failed) return false
        sharedMixDao.delete(playlistId)
        return true
    }

    private fun newEditKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "SharedMix"
        const val MAX_TRACKS = 2000
    }
}
