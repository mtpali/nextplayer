package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.more.navigation.historyEntry
import dev.anilbeesetti.nextplayer.feature.more.navigation.moreEntry
import dev.anilbeesetti.nextplayer.feature.more.navigation.navigateToHistory
import dev.anilbeesetti.nextplayer.feature.more.navigation.navigateToTrash
import dev.anilbeesetti.nextplayer.feature.more.navigation.trashEntry
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.moreNavGraph(
    context: Context,
    backStack: NavBackStack<NavKey>,
) {
    moreEntry(
        onHistoryClick = backStack::navigateToHistory,
        onPlayVideo = { context.startPlayback(it.toUri()) },
        onSettingsClick = backStack::navigateToSettings,
        onTrashClick = backStack::navigateToTrash,
    )

    historyEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )

    trashEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )

}
