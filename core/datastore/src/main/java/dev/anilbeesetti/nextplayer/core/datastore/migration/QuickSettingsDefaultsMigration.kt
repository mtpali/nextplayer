package dev.anilbeesetti.nextplayer.core.datastore.migration

import androidx.datastore.core.DataMigration
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort

object QuickSettingsDefaultsMigration : DataMigration<ApplicationPreferences> {
    private const val VERSION = 1

    override suspend fun shouldMigrate(currentData: ApplicationPreferences): Boolean =
        currentData.quickSettingsDefaultsVersion < VERSION

    override suspend fun migrate(currentData: ApplicationPreferences): ApplicationPreferences {
        if (!shouldMigrate(currentData)) return currentData
        return currentData.copy(
            mediaViewMode = MediaViewMode.VIDEOS,
            mediaLayoutMode = MediaLayoutMode.LIST,
            sortBy = Sort.By.DATE,
            sortOrder = Sort.Order.DESCENDING,
            showDurationField = true,
            showFolderDurationField = true,
            showExtensionField = true,
            showPathField = true,
            showPlayedProgress = true,
            showResolutionField = true,
            showSizeField = true,
            showThumbnailField = true,
            quickSettingsDefaultsVersion = VERSION,
        )
    }

    override suspend fun cleanUp() = Unit
}
