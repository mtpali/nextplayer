package dev.anilbeesetti.nextplayer.feature.network.navigation

import android.net.Uri
import androidx.compose.runtime.SideEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.network.screens.list.NetworkScreen
import dev.anilbeesetti.nextplayer.feature.network.screens.list.NetworkViewModel
import kotlinx.serialization.Serializable

@Serializable
object NetworkRoute : NavKey

fun EntryProviderScope<NavKey>.networkEntry(
    onSettingsClick: () -> Unit,
    onOpenStream: (Uri) -> Unit,
) {
    entry<NetworkRoute> {
        val output = NetworkViewModel.Output(
            openSettings = onSettingsClick,
            openStream = onOpenStream,
        )
        val viewModel = hiltViewModel<NetworkViewModel, NetworkViewModel.Factory>(
            creationCallback = { factory -> factory.create(output) },
        )
        SideEffect { viewModel.output = output }
        NetworkScreen(viewModel)
    }
}
