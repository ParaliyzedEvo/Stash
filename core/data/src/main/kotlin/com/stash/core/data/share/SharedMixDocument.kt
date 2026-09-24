package com.stash.core.data.share

import com.stash.core.model.share.SharedTrack
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Spec §3. id/version/updatedAt are set by the server; the app sends zeros. */
@Serializable
data class SharedMixDocument(
    val v: Int = 1,
    val id: String = "",
    val version: Int = 0,
    val updatedAt: Long = 0,
    val name: String,
    val sharedBy: String? = null,
    val covers: List<String> = emptyList(),
    val tracks: List<SharedTrack>,
) {
    /** Hash of what followers see, ignoring server-set fields: "did the mix change?". */
    fun contentHash(): String {
        val canonical = ShareJson.encodeToString(serializer(), copy(id = "", version = 0, updatedAt = 0))
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Omits nulls (optional descriptor fields) and tolerates fields a newer server adds. */
val ShareJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
