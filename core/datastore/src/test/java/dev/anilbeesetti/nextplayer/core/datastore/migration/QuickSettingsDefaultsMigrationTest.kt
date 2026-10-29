package dev.anilbeesetti.nextplayer.core.datastore.migration

import androidx.datastore.core.DataStoreFactory
import dev.anilbeesetti.nextplayer.core.datastore.serializer.ApplicationPreferencesSerializer
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.core.model.ThemeConfig
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickSettingsDefaultsMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `fresh installs use the requested library defaults`() {
        assertLibraryDefaults(ApplicationPreferencesSerializer.defaultValue)
    }

    @Test
    fun `legacy explicit settings migrate without resetting unrelated preferences`() = runBlocking {
        val legacy = ApplicationPreferencesSerializer.readFrom(
            ByteArrayInputStream(
                """{"mediaViewMode":"FOLDERS","mediaLayoutMode":"GRID","sortBy":"TITLE","sortOrder":"ASCENDING","showDurationField":false,"showFolderDurationField":false,"showExtensionField":false,"showPathField":false,"showPlayedProgress":false,"showResolutionField":false,"showSizeField":false,"showThumbnailField":false,"themeConfig":"ON","excludeFolders":["/private"]}"""
                    .toByteArray(),
            ),
        )

        assertTrue(QuickSettingsDefaultsMigration.shouldMigrate(legacy))
        val migrated = QuickSettingsDefaultsMigration.migrate(legacy)

        assertLibraryDefaults(migrated)
        assertEquals(ThemeConfig.ON, migrated.themeConfig)
        assertEquals(listOf("/private"), migrated.excludeFolders)
        assertFalse(QuickSettingsDefaultsMigration.shouldMigrate(migrated))
    }

    @Test
    fun `later user choices survive datastore reopening`() = runBlocking {
        val file = temporaryFolder.newFile("preferences.json")
        file.writeText("""{"mediaViewMode":"FOLDER_TREE","showSizeField":false}""")
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = DataStoreFactory.create(
            serializer = ApplicationPreferencesSerializer,
            migrations = listOf(QuickSettingsDefaultsMigration),
            scope = firstScope,
            produceFile = { file },
        )
        assertLibraryDefaults(first.data.first())
        val customized = first.updateData {
            it.copy(mediaViewMode = MediaViewMode.FOLDERS, sortBy = Sort.By.TITLE, showSizeField = false)
        }
        firstScope.cancel()
        firstScope.coroutineContext.job.join()

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val second = DataStoreFactory.create(
                serializer = ApplicationPreferencesSerializer,
                migrations = listOf(QuickSettingsDefaultsMigration),
                scope = secondScope,
                produceFile = { file },
            )
            assertEquals(customized, second.data.first())
        } finally {
            secondScope.cancel()
            secondScope.coroutineContext.job.join()
        }
    }

    @Test
    fun `repeating the migration keeps a completed migration unchanged`() = runBlocking {
        val migrated = QuickSettingsDefaultsMigration.migrate(ApplicationPreferences())
        val customized = migrated.copy(mediaLayoutMode = MediaLayoutMode.GRID, showThumbnailField = false)

        assertEquals(customized, QuickSettingsDefaultsMigration.migrate(customized))
    }

    private fun assertLibraryDefaults(preferences: ApplicationPreferences) {
        assertEquals(MediaViewMode.VIDEOS, preferences.mediaViewMode)
        assertEquals(MediaLayoutMode.LIST, preferences.mediaLayoutMode)
        assertEquals(Sort.By.DATE, preferences.sortBy)
        assertEquals(Sort.Order.DESCENDING, preferences.sortOrder)
        assertTrue(preferences.showDurationField)
        assertTrue(preferences.showFolderDurationField)
        assertTrue(preferences.showExtensionField)
        assertTrue(preferences.showPathField)
        assertTrue(preferences.showPlayedProgress)
        assertTrue(preferences.showResolutionField)
        assertTrue(preferences.showSizeField)
        assertTrue(preferences.showThumbnailField)
    }
}
