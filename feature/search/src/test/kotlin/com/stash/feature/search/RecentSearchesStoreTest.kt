package com.stash.feature.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore-backed unit tests for [RecentSearchesStore] (Robolectric +
 * ApplicationProvider + a real temp DataStore). The preferencesDataStore
 * delegate is a single per-process instance, so persisted state leaks between
 * tests unless wiped — clear it in @Before. Mirrors QbdlxCredentialStoreTest.
 */
@RunWith(RobolectricTestRunner::class)
class RecentSearchesStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = RecentSearchesStore(ctx)

    private fun query(text: String) = RecentSearch(RecentSearch.Type.QUERY, text)

    @Before
    fun clear() = runBlocking { store().clear() }

    @Test
    fun `record prepends most-recent-first`() = runTest {
        val s = store(); s.record(query("beatles")); s.record(query("stones"))
        assertThat(s.recent.first().map { it.text })
            .containsExactly("stones", "beatles").inOrder()
    }

    @Test
    fun `record dedupes case-insensitively and moves to front`() = runTest {
        val s = store(); s.record(query("Beatles")); s.record(query("stones")); s.record(query("BEATLES"))
        assertThat(s.recent.first().map { it.text })
            .containsExactly("BEATLES", "stones").inOrder()
    }

    @Test
    fun `richer entry replaces a plain query with the same text`() = runTest {
        // Search "lil wayne" (keyboard commit), then open the artist profile:
        // ONE recents row — the artist, with avatar — not two near-duplicates.
        val s = store()
        s.record(query("lil wayne"))
        s.record(
            RecentSearch(
                type = RecentSearch.Type.ARTIST,
                text = "Lil Wayne",
                thumbnailUrl = "https://img/wayne.jpg",
                artistId = "UC123",
            ),
        )
        val r = s.recent.first()
        assertThat(r).hasSize(1)
        assertThat(r[0].type).isEqualTo(RecentSearch.Type.ARTIST)
        assertThat(r[0].thumbnailUrl).isEqualTo("https://img/wayne.jpg")
        assertThat(r[0].artistId).isEqualTo("UC123")
    }

    @Test
    fun `track entries round-trip subtitle and thumbnail`() = runTest {
        val s = store()
        s.record(
            RecentSearch(
                type = RecentSearch.Type.TRACK,
                text = "A Milli",
                subtitle = "Lil Wayne",
                thumbnailUrl = "https://img/amilli.jpg",
            ),
        )
        val e = s.recent.first().single()
        assertThat(e.type).isEqualTo(RecentSearch.Type.TRACK)
        assertThat(e.subtitle).isEqualTo("Lil Wayne")
        assertThat(e.thumbnailUrl).isEqualTo("https://img/amilli.jpg")
        assertThat(e.searchText).isEqualTo("Lil Wayne A Milli")
    }

    @Test
    fun `legacy plain-string rows decode as query entries`() = runTest {
        // Pre-thumbnail releases stored bare newline-delimited strings.
        ctx.recentSearchesDataStore.edit { prefs ->
            prefs[stringPreferencesKey("queries")] = "beatles\nstones"
        }
        val r = store().recent.first()
        assertThat(r.map { it.text }).containsExactly("beatles", "stones").inOrder()
        assertThat(r.map { it.type }).containsExactly(
            RecentSearch.Type.QUERY, RecentSearch.Type.QUERY,
        )
    }

    @Test
    fun `caps at 10 dropping oldest`() = runTest {
        val s = store(); (1..12).forEach { s.record(query("q$it")) }
        val r = s.recent.first().map { it.text }
        assertThat(r).hasSize(10)
        assertThat(r.first()).isEqualTo("q12")
        assertThat(r).doesNotContain("q1")
        assertThat(r).doesNotContain("q2")
    }

    @Test
    fun `blank is ignored`() = runTest {
        val s = store(); s.record(query("   ")); s.record(query(""))
        assertThat(s.recent.first()).isEmpty()
    }

    @Test
    fun `remove drops one entry`() = runTest {
        val s = store(); s.record(query("a")); s.record(query("b")); s.remove("a")
        assertThat(s.recent.first().map { it.text }).containsExactly("b")
    }

    @Test
    fun `clear empties`() = runTest {
        val s = store(); s.record(query("a")); s.clear()
        assertThat(s.recent.first()).isEmpty()
    }
}
