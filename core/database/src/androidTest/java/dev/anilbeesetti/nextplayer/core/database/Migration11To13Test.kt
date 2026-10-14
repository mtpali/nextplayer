package dev.anilbeesetti.nextplayer.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration11To13Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MediaDatabase::class.java,
    )

    @Test
    fun migrationRemovesSavedCollectionsButRetainsPlaybackAndHiddenFilesForRecovery() {
        helper.createDatabase("migration-11-13", 11).apply {
            execSQL("INSERT INTO playlist (id, name, type, created_at) VALUES (7, 'Movies', 'LOCAL', 100)")
            execSQL("INSERT INTO playlist_item (playlist_id, uri, position, duration) VALUES (7, 'content://video', 0, -1)")
            execSQL("INSERT INTO media_state (uri, playback_position, external_subs, video_scale, subtitle_delay, subtitle_speed) " +
                "VALUES ('content://video', 42, '', 1, 0, 1)")
            execSQL("INSERT INTO hidden_video (vault_path, original_path, display_name, hidden_at) " +
                "VALUES ('/private/video.mp4', '/Movies/video.mp4', 'video.mp4', 123)")
            close()
        }

        helper.runMigrationsAndValidate(
            "migration-11-13",
            13,
            true,
            MediaDatabase.MIGRATION_11_12,
            MediaDatabase.MIGRATION_12_13,
        ).use { migrated ->
            migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'playlist%'").use {
                assertEquals(0, it.count)
            }
            migrated.query("SELECT playback_position FROM media_state WHERE uri = 'content://video'").use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
            }
            migrated.query("SELECT original_path FROM hidden_video").use {
                assertTrue(it.moveToFirst())
                assertEquals("/Movies/video.mp4", it.getString(0))
            }
        }
    }
}
