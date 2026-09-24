package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the word-synced lyrics migrations: v48 -> v49 adds `lyrics.ttml` and
 * `lyrics.ttml_checked_at` (both NULL on existing rows), v49 -> v50 adds
 * `lyrics.sync_offset_ms` (0 on existing rows). Existing lyrics survive both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV48V50Test {

    private val DB_NAME = "migration-v48v50-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun seedV48() {
        helper.createDatabase(DB_NAME, 48).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                    "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                    "canonical_artist, match_confidence, match_dismissed) " +
                    "VALUES (1, 'Song', 'Artist', 'Album', 200000, 'opus', 160, 0, 'YOUTUBE', 0, 0, 1, " +
                    "'song', 'artist', 0.0, 0)",
            )
            db.execSQL(
                "INSERT INTO lyrics (track_id, plain_text, synced_lrc, instrumental, language, " +
                    "source, source_lyrics_id, fetched_at) " +
                    "VALUES (1, 'la la', '[00:01.00]la la', 0, NULL, 'lrclib', '42', 1700000000000)",
            )
        }
    }

    @Test fun `48 to 49 adds null ttml columns and keeps the row`() {
        seedV48()
        val db = helper.runMigrationsAndValidate(DB_NAME, 49, true, StashDatabase.MIGRATION_48_49)

        db.query("SELECT synced_lrc, ttml, ttml_checked_at FROM lyrics WHERE track_id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("[00:01.00]la la", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
    }

    @Test fun `49 to 50 adds sync_offset_ms defaulting to 0`() {
        seedV48()
        helper.runMigrationsAndValidate(DB_NAME, 49, true, StashDatabase.MIGRATION_48_49).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 50, true, StashDatabase.MIGRATION_49_50)

        db.query("SELECT plain_text, sync_offset_ms FROM lyrics WHERE track_id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("la la", c.getString(0))
            assertEquals(0L, c.getLong(1))
        }
        db.execSQL("UPDATE lyrics SET sync_offset_ms = -250 WHERE track_id = 1")
        db.query("SELECT sync_offset_ms FROM lyrics WHERE track_id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(-250L, c.getLong(0))
        }
    }
}
