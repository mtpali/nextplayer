package dev.anilbeesetti.nextplayer.feature.network.screens.list

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.network.download.DownloadManagerClient
import dev.anilbeesetti.nextplayer.feature.network.download.ManagedDownload
import dev.anilbeesetti.nextplayer.feature.network.download.isValidHttpUrl
import dev.anilbeesetti.nextplayer.feature.network.download.isValidPlayableUrl
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NetworkUiState(
    val downloads: List<ManagedDownload> = emptyList(),
    val message: NetworkMessage? = null,
)

enum class NetworkMessage {
    INVALID_STREAM_URL,
    INVALID_DOWNLOAD_URL,
    DOWNLOAD_STARTED,
    DOWNLOAD_FAILED,
    CANNOT_OPEN_DOWNLOAD,
}

@HiltViewModel(assistedFactory = NetworkViewModel.Factory::class)
class NetworkViewModel @AssistedInject constructor(
    @ApplicationContext context: Context,
    @Assisted internal var output: Output,
) : MviViewModel<NetworkUiState, NetworkAction>() {

    data class Output(
        val openSettings: () -> Unit,
        val openStream: (Uri) -> Unit,
    )

    @AssistedFactory
    interface Factory {
        fun create(output: Output): NetworkViewModel
    }

    private val downloads = DownloadManagerClient(context)
    private val stateInternal = MutableStateFlow(NetworkUiState())
    override val state: StateFlow<NetworkUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshDownloads()
                delay(1.seconds)
            }
        }
    }

    override fun onAction(action: NetworkAction) {
        when (action) {
            NetworkAction.OpenSettings -> output.openSettings()
            NetworkAction.MessageShown -> stateInternal.update { it.copy(message = null) }
            is NetworkAction.OpenStream -> openStream(action.url)
            is NetworkAction.EnqueueDownload -> enqueueDownload(action.url)
            is NetworkAction.OpenDownload -> {
                if (!downloads.open(action.id)) showMessage(NetworkMessage.CANNOT_OPEN_DOWNLOAD)
            }
            is NetworkAction.RemoveDownload -> {
                downloads.remove(action.id)
                refresh()
            }
        }
    }

    private fun openStream(url: String) {
        if (!isValidPlayableUrl(url)) {
            showMessage(NetworkMessage.INVALID_STREAM_URL)
            return
        }
        output.openStream(url.trim().toUri())
    }

    private fun enqueueDownload(url: String) {
        if (!isValidHttpUrl(url)) {
            showMessage(NetworkMessage.INVALID_DOWNLOAD_URL)
            return
        }
        downloads.enqueue(url)
            .onSuccess {
                showMessage(NetworkMessage.DOWNLOAD_STARTED)
                refresh()
            }
            .onFailure { showMessage(NetworkMessage.DOWNLOAD_FAILED) }
    }

    private fun refresh() {
        viewModelScope.launch { refreshDownloads() }
    }

    private suspend fun refreshDownloads() {
        val items = withContext(Dispatchers.IO) { downloads.query() }
        stateInternal.update { it.copy(downloads = items) }
    }

    private fun showMessage(message: NetworkMessage) {
        stateInternal.update { it.copy(message = message) }
    }
}

sealed interface NetworkAction {
    data object OpenSettings : NetworkAction
    data object MessageShown : NetworkAction
    data class OpenStream(val url: String) : NetworkAction
    data class EnqueueDownload(val url: String) : NetworkAction
    data class OpenDownload(val id: Long) : NetworkAction
    data class RemoveDownload(val id: Long) : NetworkAction
}
