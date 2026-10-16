package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v56 -> v57 adds `scanned_songs.album_art_path`, the file name of a scanned song's embedded cover.
 *
 * Existing rows must come through with NULL there ("not looked at yet": the next scan fills it in),
 * and both older paths must still reach 57: master's v55 (through 55 -> 56 -> 57) and the All Songs
 * PR's debug v55 (healed by 55 -> 56, then 56 -> 57).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV56V57Test {

    private val DB_NAME = "migration-v56v57-test"

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

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    /** v56's scanned_songs shape: has last_modified, no album_art_path. */
    private fun seedScanV56(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO scan_folders (id, tree_uri, display_name, added_at) VALUES (1, 'content://t/tree/a', 'A', 11)")
        db.execSQL(
            "INSERT INTO scanned_songs (id, folder_id, uri, title, artist, album, duration_ms, file_format, " +
                "file_size_bytes, last_modified, date_added) " +
                "VALUES (5, 1, 'content://t/tree/a/document/x', 'T', 'A', 'Al', 2000, 'flac', 5, 7, 99)",
        )
    }

    @Test fun `56 to 57 keeps the folder and song and leaves the cover NULL`() {
        helper.createDatabase(DB_NAME, 56).use {
            seedLibrary(it)
            seedScanV56(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 57, true, StashDatabase.MIGRATION_56_57)

        assertTrue("album_art_path" in columns(db, "scanned_songs"))
        db.query("SELECT id, uri, title, album, duration_ms, file_format, file_size_bytes, last_modified, date_added, album_art_path FROM scanned_songs").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(5L, c.getLong(0))
            assertEquals("content://t/tree/a/document/x", c.getString(1))
            assertEquals("T", c.getString(2))
            assertEquals("Al", c.getString(3))
            assertEquals(2000L, c.getLong(4))
            assertEquals("flac", c.getString(5))
            assertEquals(5L, c.getLong(6))
            assertEquals(7L, c.getLong(7))
            assertEquals(99L, c.getLong(8))
            assertTrue("a row from before covers is 'not looked at yet'", c.isNull(9))
        }
        assertEquals(1, count(db, "SELECT COUNT(*) FROM scan_folders"))
        assertEquals(true, indexes(db, "scanned_songs")["index_scanned_songs_uri"])
        assertEquals(false, indexes(db, "scanned_songs")["index_scanned_songs_folder_id"])
        assertLibraryUnchanged(db)
    }

    @Test fun `the new column takes a file name, an empty string and NULL`() {
        helper.createDatabase(DB_NAME, 56).use { seedScanV56(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 57, true, StashDatabase.MIGRATION_56_57)

        db.execSQL("UPDATE scanned_songs SET album_art_path = 'scanned_abc.jpg' WHERE id = 5")
        db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("scanned_abc.jpg", c.getString(0))
        }
        db.execSQL("UPDATE scanned_songs SET album_art_path = '' WHERE id = 5")
        db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
        }
        db.execSQL("UPDATE scanned_songs SET album_art_path = NULL WHERE id = 5")
        db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }
    }

    @Test fun `master's v55 with a library reaches 57 through both steps, library unchanged`() {
        helper.createDatabase(DB_NAME, 55).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(
            DB_NAME, 57, true,
            StashDatabase.MIGRATION_55_56, StashDatabase.MIGRATION_56_57,
        )

        assertTrue("album_art_path" in columns(db, "scanned_songs"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM scan_folders"))
        assertTrue("master's tracks index survives", "index_tracks_canonical_title_canonical_artist" in indexes(db, "tracks"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the All Songs debug v55 is healed all the way to 57`() {
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

        // Validation compares every table and index against 57.json: it fails if the tracks index is
        // still missing, the old scanned_songs shape survived, or album_art_path wasn't added.
        val db = helper.runMigrationsAndValidate(
            DB_NAME, 57, true,
            StashDatabase.MIGRATION_55_56, StashDatabase.MIGRATION_56_57,
        )

        assertTrue("index_tracks_canonical_title_canonical_artist" in indexes(db, "tracks"))
        assertTrue("album_art_path" in columns(db, "scanned_songs"))
        assertTrue("last_modified" in columns(db, "scanned_songs"))
        assertEquals("the scan cache is rebuilt by the next scan", 0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertLibraryUnchanged(db)
    }
}