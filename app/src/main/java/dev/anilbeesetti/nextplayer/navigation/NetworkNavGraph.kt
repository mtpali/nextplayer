package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.network.navigation.networkEntry
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.networkNavGraph(
    context: Context,
    backStack: NavBackStack<NavKey>,
) {
    networkEntry(
        onSettingsClick = backStack::navigateToSettings,
        onOpenStream = { uri -> context.startPlayback(uri) },
    )
}
