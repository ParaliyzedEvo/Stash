package com.stash.core.data.share

import android.util.Base64
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import com.stash.core.model.share.toTrack
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

enum class FollowCheck { UpToDate, Updated, Removed, Unreachable }

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
        // Untagged local files are kept: toSharedTrack() gives them placeholder title/artist.
        val tracks = playlistDao.getTracksForPlaylist(playlistId).map { it.toDomain() }
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


    // ── Follower ───────────────────────────────────────────────────────────────────────────

    suspend fun fetch(shareId: String): ShareResult<SharedMixDocument> = api.get(shareId)
    suspend fun byShareId(shareId: String): SharedMixEntity? = sharedMixDao.byShareId(shareId)

    /** Persist every descriptor (deduped against the library), keeping document order and dropping repeats. */
    suspend fun persistTracks(doc: SharedMixDocument): List<Long> =
        doc.tracks.map { musicRepository.ensureTrackPersisted(it.toTrack()) }.distinct()

    suspend fun tracksFor(doc: SharedMixDocument): List<Track> =
        persistTracks(doc).mapNotNull { trackDao.getById(it)?.toDomain() }

    /** Spec §6 Follow: a CUSTOM/BOTH playlist, source_id share:<id>, download off. Idempotent per share id. */
    suspend fun follow(doc: SharedMixDocument): Long {
        sharedMixDao.byShareId(doc.id)?.let { return it.playlistId }
        val ids = persistTracks(doc)
        val playlistId = playlistDao.insert(
            PlaylistEntity(
                name = doc.name, source = MusicSource.BOTH, sourceId = "share:${doc.id}",
                type = PlaylistType.CUSTOM, isActive = true, syncEnabled = false,
            ),
        )
        playlistDao.replaceMixMembership(playlistId, ids, doc.name, Instant.now())
        // insert, not upsert: a share_id clash must throw, never silently drop the row (Room's @Upsert
        // falls back to UPDATE by primary key, which matches nothing). byShareId above makes it rare.
        sharedMixDao.insert(
            SharedMixEntity(
                playlistId = playlistId, shareId = doc.id, role = SharedMixEntity.ROLE_FOLLOWER,
                name = doc.name, version = doc.version, sharedBy = doc.sharedBy, lastCheckedAt = System.currentTimeMillis(),
            ),
        )
        return playlistId
    }

    /** Spec §6 Save a copy: an ordinary editable playlist, no link back. */
    suspend fun saveCopy(doc: SharedMixDocument): Long {
        val ids = persistTracks(doc)
        val playlistId = musicRepository.createPlaylist(doc.name)
        playlistDao.replaceMixMembership(playlistId, ids, doc.name, Instant.now())
        return playlistId
    }

    /** Remove a followed mix. Tracks stay only if another playlist or a like claims them (existing orphan rules). */
    suspend fun unfollow(playlistId: Long) {
        val playlist = playlistDao.getById(playlistId)?.toDomain() ?: return
        sharedMixDao.delete(playlistId)
        musicRepository.removePlaylist(playlist)
    }

    /** "Download this mix" is the playlist's sync_enabled (spec §6); enabling also starts downloading now. */
    suspend fun setDownload(playlistId: Long, on: Boolean) {
        playlistDao.setSyncEnabled(playlistId, on)
        if (on) musicRepository.queueDownloadsForPlaylist(playlistId)
    }

    suspend fun checkForUpdate(row: SharedMixEntity, now: Long): FollowCheck {
        return when (val v = api.version(row.shareId)) {
            is ShareResult.Ok -> {
                if (v.value <= row.version) {
                    sharedMixDao.upsert(row.copy(missingCount = 0, lastCheckedAt = now)); FollowCheck.UpToDate
                } else when (val d = api.get(row.shareId)) {
                    is ShareResult.Ok -> { applyUpdate(row, d.value, now); FollowCheck.Updated }
                    ShareResult.Gone -> removed(row)
                    else -> FollowCheck.Unreachable
                }
            }
            ShareResult.Gone -> removed(row)
            ShareResult.NotFound -> if (row.missingCount + 1 >= 2) removed(row) else {
                sharedMixDao.upsert(row.copy(missingCount = row.missingCount + 1, lastCheckedAt = now)); FollowCheck.Unreachable
            }
            else -> FollowCheck.Unreachable
        }
    }

    private suspend fun applyUpdate(row: SharedMixEntity, doc: SharedMixDocument, now: Long) {
        val ids = persistTracks(doc)
        playlistDao.replaceMixMembership(row.playlistId, ids, doc.name, Instant.ofEpochMilli(now))
        sharedMixDao.upsert(row.copy(name = doc.name, version = doc.version, sharedBy = doc.sharedBy, missingCount = 0, lastCheckedAt = now))
        if (playlistDao.getById(row.playlistId)?.syncEnabled == true) musicRepository.queueDownloadsForPlaylist(row.playlistId)
    }

    private suspend fun removed(row: SharedMixEntity): FollowCheck {
        sharedMixDao.upsert(row.copy(status = SharedMixEntity.STATUS_REMOVED, noticePending = true))
        return FollowCheck.Removed
    }

    /** The one-time "stopped sharing" message (spec §6), or null. Clears the flag. */
    suspend fun consumeRemovedNotice(playlistId: Long): String? {
        val row = sharedMixDao.forPlaylist(playlistId)?.takeIf { it.noticePending } ?: return null
        sharedMixDao.upsert(row.copy(noticePending = false))
        return "${row.sharedBy ?: "The owner"} stopped sharing this mix. You keep your copy."
    }

    companion object {
        private const val TAG = "SharedMix"
        const val MAX_TRACKS = 2000
    }
}
