package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MixRecipeMigration29To30Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate29To30_addsMoodKeysAndSampleDepthWithDefaults() {
        helper.createDatabase(TEST_DB, 29).apply {
            execSQL(
                "INSERT INTO stash_mix_recipes " +
                    "(id, name, include_tags_csv, exclude_tags_csv, affinity_bias, discovery_ratio, " +
                    " freshness_window_days, target_length, is_builtin, is_active, created_at, seed_strategy) " +
                    "VALUES (1, 'Deep Cuts', '', '', 0.0, 0.85, 90, 40, 1, 1, 0, 'TRACK_SIMILAR')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 30, true, StashDatabase.MIGRATION_29_30)
        db.query("SELECT mood_keys_csv, tag_sample_depth FROM stash_mix_recipes WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals("", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        db.close()
    }

    private companion object { const val TEST_DB = "migration-test-db" }
}
