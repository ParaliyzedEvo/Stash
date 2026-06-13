package com.stash.data.download.lossless.antra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Response models for the antra.hoshi.cfd API, shaped to the 2026-06-08 HAR.
 *
 * Field names mirror antra's JSON verbatim (snake_case) so no
 * `@SerialName` annotations are needed. All fields have defaults so a
 * partial/evolving payload still deserializes — combined with
 * [AntraJson]'s `ignoreUnknownKeys`, the parser tolerates antra adding or
 * dropping fields without breaking Stash.
 */

/** `GET /api/auth/me` — auth confirmation + remaining quota. */
@Serializable
data class AntraMe(
    val username: String = "",
    val is_supporter: Boolean = false,
    val concurrent_jobs: Int = 0,
    val albums_left: Int = 0,
    val playlists_left: Int = 0,
    val singles_left: Int = 0,
)

/** One track in a `POST /api/resolve` release. */
@Serializable
data class AntraResolveTrack(
    val index: Int = 0,
    val title: String = "",
    val artist: String = "",
    val duration_ms: Long = 0,
    val track_number: Int = 0,
    val explicit: Boolean = false,
)

/** `POST /api/resolve` — the release/track metadata for a streaming URL. */
@Serializable
data class AntraResolve(
    val release_name: String = "",
    val release_type: String = "",
    val artist: String = "",
    val artwork_url: String? = null,
    val tracks: List<AntraResolveTrack> = emptyList(),
)

/** `POST /api/jobs` — the created download job. */
@Serializable
data class AntraJobCreated(
    val job_id: String,
    val ws_token: String? = null,
)

/** `GET /api/jobs/<id>/status` — job progress / terminal state. */
@Serializable
data class AntraJobStatus(
    val job_id: String = "",
    val status: String = "",
    val done: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val filename: String? = null,
)

/** Shared lenient JSON for all antra API parsing. */
val AntraJson: Json = Json { ignoreUnknownKeys = true }
