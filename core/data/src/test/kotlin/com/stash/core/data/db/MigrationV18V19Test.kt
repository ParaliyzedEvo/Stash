package com.stash.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.9.15: Verifies migration v18 -> v19 creates the `track_blocklist`
 * table and seeds it from existing `tracks.is_blacklisted = 1` rows.
 * Non-blacklisted tracks must NOT migrate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV18V19Test {

    private val DB_NAME = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v18 to v19 seeds blocklist from is_blacklisted=1 rows and ignores others`() {
        val now = System.currentTimeMillis()

        helper.createDatabase(DB_NAME, 18).use { db ->
            // Blocked track — should migrate.
            db.insertTrackV18(
                id = 1L,
                title = "505",
                artist = "Arctic Monkeys",
                canonicalTitle = "505",
                canonicalArtist = "arctic monkeys",
                spotifyUri = "spotify:track:abc",
                youtubeId = null,
                dateAdded = now,
                isBlacklisted = 1,
            )
            // Non-blacklisted track — should NOT migrate.
            db.insertTrackV18(
                id = 2L,
                title = "Brianstorm",
                artist = "Arctic Monkeys",
                canonicalTitle = "brianstorm",
                canonicalArtist = "arctic monkeys",
                spotifyUri = "spotify:track:def",
                youtubeId = null,
                dateAdded = now,
                isBlacklisted = 0,
            )
            // Blocked track sourced only from YouTube (nullable spotify_uri path).
            db.insertTrackV18(
                id = 3L,
                title = "Do I Wanna Know",
                artist = "Arctic Monkeys",
                canonicalTitle = "do i wanna know",
                canonicalArtist = "arctic monkeys",
                spotifyUri = null,
                youtubeId = "ytIDxyz",
                dateAdded = 0,            // forces strftime fallback
                isBlacklisted = 1,
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 19, true, StashDatabase.MIGRATION_18_19,
        )

        migrated.query(
            "SELECT canonical_key, artist, title, spotify_uri, youtube_id, blocked_from FROM track_blocklist ORDER BY canonical_key"
        ).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("arctic monkeys|505", c.getString(0))
            assertEquals("Arctic Monkeys", c.getString(1))
            assertEquals("505", c.getString(2))
            assertEquals("spotify:track:abc", c.getString(3))
            assertNull(c.getString(4))
            assertEquals("MIGRATION_V19", c.getString(5))

            c.moveToNext()
            assertEquals("arctic monkeys|do i wanna know", c.getString(0))
            assertEquals("Do I Wanna Know", c.getString(2))
            assertNull(c.getString(3))
            assertEquals("ytIDxyz", c.getString(4))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertTrackV18(
        id: Long,
        title: String,
        artist: String,
        canonicalTitle: String,
        canonicalArtist: String,
        spotifyUri: String?,
        youtubeId: String?,
        dateAdded: Long,
        isBlacklisted: Int,
    ) {
        // Mirror every NOT NULL column from the v18 schema with a sane
        // default so the insert succeeds. The migration only reads a
        // subset of columns; the rest just need to exist.
        val cv = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("album", "")
            put("duration_ms", 0L)
            put("file_format", "opus")
            put("quality_kbps", 0)
            put("file_size_bytes", 0L)
            put("source", "SPOTIFY")
            put("date_added", dateAdded)
            put("play_count", 0)
            put("is_downloaded", 0)
            put("canonical_title", canonicalTitle)
            put("canonical_artist", canonicalArtist)
            put("match_confidence", 0f)
            put("match_dismissed", 0)
            put("match_flagged", 0)
            put("is_blacklisted", isBlacklisted)
            spotifyUri?.let { put("spotify_uri", it) }
            youtubeId?.let { put("youtube_id", it) }
        }
        insert("tracks", SQLiteDatabase.CONFLICT_REPLACE, cv)
    }
}
