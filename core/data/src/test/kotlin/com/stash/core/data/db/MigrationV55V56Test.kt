package com.stash.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v55 -> v56 adds All Songs' two tables, `scan_folders` and `scanned_songs`, apart from `tracks`.
 *
 * It must converge from BOTH v55 schemas that exist: master's (tracks index, no scan tables) and
 * the All Songs PR's debug builds, which also called themselves v55 but carried the scan tables
 * (without `last_modified`) and not master's tracks index. Test (c) builds that second shape by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV55V56Test {

    private val DB_NAME = "migration-v55v56-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun seedLibrary(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                "canonical_artist, match_confidence, match_dismissed, match_flagged, youtube_id) " +
                "VALUES (1, 'Song', 'Artist', 'Album', 1000, 'flac', 0, 10, 'LOCAL', 0, 3, 1, 'song', 'artist', 1.0, 0, 0, NULL)",
        )
        db.execSQL("INSERT INTO playlists (id, name, source, source_id, type, is_active, sync_enabled, track_count, date_added) VALUES (9, 'P', 'BOTH', 'custom_9', 'CUSTOM', 1, 1, 1, 0)")
        db.execSQL("INSERT INTO playlist_tracks (playlist_id, track_id, position, added_at) VALUES (9, 1, 0, 0)")
    }

    private fun assertLibraryUnchanged(db: SupportSQLiteDatabase) {
        db.query("SELECT id, title, file_format, play_count, is_downloaded FROM tracks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("Song", c.getString(1))
            assertEquals("flac", c.getString(2))
            assertEquals(3, c.getInt(3))
            assertEquals(1, c.getInt(4))
            assertEquals(1, c.count)
        }
        db.query("SELECT track_id FROM playlist_tracks WHERE playlist_id = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
    }

    /** name -> unique, for every index on [table]. */
    private fun indexes(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> =
        db.query("PRAGMA index_list(`$table`)").use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("unique")) == 1)
                }
            }
        }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun insertSong(db: SupportSQLiteDatabase, folderId: Long, uri: String, conflict: String = "") {
        db.execSQL(
            "INSERT $conflict INTO scanned_songs (folder_id, uri, title, artist, album, duration_ms, " +
                "file_format, file_size_bytes, last_modified, date_added) VALUES (?, ?, 'T', 'A', '', 1000, 'mp3', 5, 7, 0)",
            arrayOf<Any?>(folderId, uri),
        )
    }

    @Test fun `55 to 56 adds the scan tables with their unique indexes and leaves the library alone`() {
        helper.createDatabase(DB_NAME, 55).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 56, true, StashDatabase.MIGRATION_55_56)

        assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='scan_folders'"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='scanned_songs'"))
        assertEquals(true, indexes(db, "scan_folders")["index_scan_folders_tree_uri"])
        val songIndexes = indexes(db, "scanned_songs")
        assertEquals(true, songIndexes["index_scanned_songs_uri"])
        assertEquals(false, songIndexes["index_scanned_songs_folder_id"])
        assertTrue("master's tracks index survives", "index_tracks_canonical_title_canonical_artist" in indexes(db, "tracks"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the scan tables ignore a duplicate uri, cascade a folder's songs and refuse an unknown folder`() {
        helper.createDatabase(DB_NAME, 55).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 56, true, StashDatabase.MIGRATION_55_56)
        db.execSQL("PRAGMA foreign_keys=ON")

        db.execSQL("INSERT INTO scan_folders (id, tree_uri, display_name, added_at) VALUES (1, 'content://t/tree/a', 'A', 0)")
        insertSong(db, 1, "content://t/tree/a/document/a%2F1.mp3")
        insertSong(db, 1, "content://t/tree/a/document/a%2F1.mp3", conflict = "OR IGNORE")
        assertEquals("a duplicate uri is ignored", 1, count(db, "SELECT COUNT(*) FROM scanned_songs"))

        try {
            insertSong(db, 42, "content://t/tree/b/document/b%2F1.mp3")
            fail("a song under an unknown folder must be refused")
        } catch (expected: SQLiteConstraintException) {
            // FOREIGN KEY constraint failed.
        }

        db.execSQL("DELETE FROM scan_folders WHERE id = 1")
        assertEquals("deleting a folder takes its songs", 0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertLibraryUnchanged(db)
    }

    @Test fun `55 to 56 heals the All Songs debug v55 that had the scan tables but no tracks index`() {
        helper.createDatabase(DB_NAME, 55).use { db ->
            seedLibrary(db)
            // The PR's debug v55: master's index missing, its own scan tables (no last_modified) present.
            db.execSQL("DROP INDEX IF EXISTS `index_tracks_canonical_title_canonical_artist`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scan_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`tree_uri` TEXT NOT NULL, `display_name` TEXT NOT NULL, `added_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_folders_tree_uri` ON `scan_folders` (`tree_uri`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scanned_songs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`folder_id` INTEGER NOT NULL, `uri` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                    "`album` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `file_format` TEXT NOT NULL, " +
                    "`file_size_bytes` INTEGER NOT NULL, `date_added` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`folder_id`) REFERENCES `scan_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scanned_songs_uri` ON `scanned_songs` (`uri`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_songs_folder_id` ON `scanned_songs` (`folder_id`)")
            db.execSQL("INSERT INTO scan_folders (id, tree_uri, display_name, added_at) VALUES (1, 'content://t/tree/a', 'A', 0)")
            db.execSQL(
                "INSERT INTO scanned_songs (folder_id, uri, title, artist, album, duration_ms, file_format, " +
                    "file_size_bytes, date_added) VALUES (1, 'content://t/tree/a/document/x', 'T', 'A', '', 0, 'mp3', 5, 0)",
            )
        }

        // Validation compares every table and index against 56.json, so it fails if either the
        // tracks index is still missing or the old scanned_songs shape survived.
        val db = helper.runMigrationsAndValidate(DB_NAME, 56, true, StashDatabase.MIGRATION_55_56)

        assertTrue("index_tracks_canonical_title_canonical_artist" in indexes(db, "tracks"))
        assertEquals("the scan cache is rebuilt by the next scan", 0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertLibraryUnchanged(db)
    }
}
