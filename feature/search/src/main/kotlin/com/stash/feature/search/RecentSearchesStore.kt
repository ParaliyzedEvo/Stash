package com.stash.feature.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// internal (not private) as a test seam: the legacy-migration test seeds raw
// pre-thumbnail rows directly into the same DataStore instance.
internal val Context.recentSearchesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_searches",
)

/**
 * One recent-search entry. Three flavours:
 *  - [Type.QUERY]  — a committed text query (keyboard Search key). No art.
 *  - [Type.ARTIST] — the user opened an artist profile from results; carries
 *    the avatar and the artist browse id so a tap navigates straight back.
 *  - [Type.TRACK]  — the user engaged a track row (tap/preview/download/
 *    queue/playlist); carries the track thumbnail; [subtitle] is the artist.
 */
data class RecentSearch(
    val type: Type,
    val text: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val artistId: String? = null,
) {
    enum class Type { QUERY, ARTIST, TRACK }

    /** The query a tap should re-run (ARTIST entries navigate instead). */
    val searchText: String
        get() = if (type == Type.TRACK && subtitle != null) "$subtitle $text" else text
}

/**
 * Most-recent-first list of committed searches, capped at [MAX].
 *
 * Backed by a single string value: rows separated by newline, fields by the
 * ASCII unit separator (the same single-string pattern
 * LosslessSourcePreferences uses for its comma-list, extended to records).
 * Legacy rows from the strings-only era have no separator and decode as
 * [RecentSearch.Type.QUERY] — old data migrates for free.
 *
 * Dedupe is by [RecentSearch.text], case-insensitive, regardless of type:
 * committing the query "lil wayne" and then opening the artist "Lil Wayne"
 * yields ONE entry — the richer, most recent one (the artist, with avatar).
 */
@Singleton
class RecentSearchesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("queries")

    val recent: Flow<List<RecentSearch>> = context.recentSearchesDataStore.data.map { prefs ->
        prefs[key].toEntries()
    }

    /** Record [entry] as the most-recent search. No-op for blank text. */
    suspend fun record(entry: RecentSearch) {
        val text = entry.text.clean()
        if (text.isEmpty()) return
        val sanitized = entry.copy(
            text = text,
            subtitle = entry.subtitle?.clean()?.takeIf { it.isNotEmpty() },
            thumbnailUrl = entry.thumbnailUrl?.clean()?.takeIf { it.isNotEmpty() },
            artistId = entry.artistId?.clean()?.takeIf { it.isNotEmpty() },
        )
        context.recentSearchesDataStore.edit { prefs ->
            val deduped = prefs[key].toEntries()
                .filterNot { it.text.equals(text, ignoreCase = true) }
            prefs[key] = (listOf(sanitized) + deduped).take(MAX)
                .joinToString("\n") { it.encode() }
        }
    }

    /** Remove a single entry by its display text (case-insensitive). */
    suspend fun remove(text: String) {
        context.recentSearchesDataStore.edit { prefs ->
            prefs[key] = prefs[key].toEntries()
                .filterNot { it.text.equals(text, ignoreCase = true) }
                .joinToString("\n") { it.encode() }
        }
    }

    /** Clear all recent searches. */
    suspend fun clear() {
        context.recentSearchesDataStore.edit { it.remove(key) }
    }

    /** Strip the two structural characters + trim. */
    private fun String.clean(): String = replace("\n", " ").replace(SEP, ' ').trim()

    private fun RecentSearch.encode(): String = listOf(
        type.name, text, subtitle.orEmpty(), thumbnailUrl.orEmpty(), artistId.orEmpty(),
    ).joinToString(SEP.toString())

    private fun String?.toEntries(): List<RecentSearch> =
        this?.split("\n")?.filter { it.isNotBlank() }?.map { line ->
            val f = line.split(SEP)
            if (f.size < 2) {
                // Legacy strings-only row.
                RecentSearch(RecentSearch.Type.QUERY, line.trim())
            } else {
                RecentSearch(
                    type = runCatching { RecentSearch.Type.valueOf(f[0]) }
                        .getOrDefault(RecentSearch.Type.QUERY),
                    text = f[1],
                    subtitle = f.getOrNull(2)?.takeIf { it.isNotEmpty() },
                    thumbnailUrl = f.getOrNull(3)?.takeIf { it.isNotEmpty() },
                    artistId = f.getOrNull(4)?.takeIf { it.isNotEmpty() },
                )
            }
        } ?: emptyList()

    companion object {
        /** Max retained queries — industry-standard recents depth. */
        const val MAX = 10

        /** ASCII unit separator — never appears in typed queries or URLs. */
        private const val SEP = '\u001F'
    }
}
