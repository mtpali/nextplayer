package dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.common.storagePermission
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.domain.GetRecentlyPlayedVideoUseCase
import dev.anilbeesetti.nextplayer.core.domain.GetSortedMediaUseCase
import dev.anilbeesetti.nextplayer.core.domain.GetSortedVideosUseCase
import dev.anilbeesetti.nextplayer.core.domain.MediaHolder
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.media.services.TransferEvent
import dev.anilbeesetti.nextplayer.core.media.services.TransferMode
import dev.anilbeesetti.nextplayer.core.media.services.TransferProgress
import dev.anilbeesetti.nextplayer.core.media.services.TransferResult
import dev.anilbeesetti.nextplayer.core.media.sync.MediaSynchronizer
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.model.findClosestFolder
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = MediaPickerViewModel.Factory::class)
class MediaPickerViewModel @AssistedInject constructor(
    private val getSortedMediaUseCase: GetSortedMediaUseCase,
    private val getRecentlyPlayedVideoUseCase: GetRecentlyPlayedVideoUseCase,
    private val getSortedVideosUseCase: GetSortedVideosUseCase,
    private val mediaOperationsService: MediaOperationsService,
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val mediaSynchronizer: MediaSynchronizer,
    private val systemService: SystemService,
    @ApplicationContext private val context: Context,
    @Assisted private val input: Input,
    @Assisted internal var output: Output,
) : MviViewModel<MediaPickerUiState, MediaPickerAction>() {

    data class Input(
        val folderId: String?,
    )

    data class Output(
        val navigateUp: () -> Unit,
        val playVideo: (Uri) -> Unit,
        val playVideos: (List<Uri>) -> Unit,
        val openFolder: (String) -> Unit,
        val openSettings: () -> Unit,
        val openSearch: () -> Unit,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            input: Input,
            output: Output,
        ): MediaPickerViewModel
    }

    val folderPath = input.folderId

    private val stateInternal = MutableStateFlow(
        MediaPickerUiState(
            folderName = folderPath?.let { File(folderPath).prettyName },
            preferences = preferencesRepository.applicationPreferences.value,
        ),
    )
    override val state: StateFlow<MediaPickerUiState> = stateInternal.asStateFlow()

    private var mediaCollectJob: Job? = null
    private var transferJob: Job? = null

    init {
        if (ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED) {
            startMediaCollection()
        }
        collectPreferences()
    }

    override fun onAction(action: MediaPickerAction) {
        when (action) {
            is MediaPickerAction.OnNavigateUpClick -> output.navigateUp()
            is MediaPickerAction.OnPlayVideo -> output.playVideo(action.uri)
            is MediaPickerAction.OnFolderClick -> output.openFolder(action.folderPath)
            is MediaPickerAction.OnSettingsClick -> output.openSettings()
            is MediaPickerAction.OnSearchClick -> output.openSearch()
            is MediaPickerAction.Refresh -> refresh()
            is MediaPickerAction.RenameVideo -> renameVideo(action.uri, action.to)
            is MediaPickerAction.UpdateMenu -> updateMenu(action.preferences)
            is MediaPickerAction.OnPermissionAccepted -> startMediaCollection()
            is MediaPickerAction.PlaySelectedItems -> playSelectedItems(action.selectionItems)
            is MediaPickerAction.DeleteSelectedItems -> deleteSelectedItems(action.selectionItems, action.permanently)
            is MediaPickerAction.ShareSelectedItems -> shareSelectedItems(action.selectionItems)
            is MediaPickerAction.ShowMediaInfo -> showMediaInfo(action.video)
            is MediaPickerAction.DismissMediaInfo -> stateInternal.update { it.copy(mediaInfo = null) }
            is MediaPickerAction.CopySelectedItems -> transferSelectedItems(action.selectionItems, TransferMode.COPY)
            is MediaPickerAction.MoveSelectedItems -> transferSelectedItems(action.selectionItems, TransferMode.MOVE)
            is MediaPickerAction.CancelTransfer -> cancelTransfer()
        }
    }

    private fun startMediaCollection() {
        mediaSynchronizer.startSync()
        collectMedia()
    }

    private fun collectMedia() {
        mediaCollectJob?.cancel()
        stateInternal.update { currentState ->
            currentState.copy(mediaDataState = DataState.Loading)
        }
        mediaCollectJob = viewModelScope.launch {
            launch {
                getSortedMediaUseCase(folderPath).collect { media ->
                    stateInternal.update { it.copy(mediaDataState = DataState.Success(media)) }
                }
            }
            launch {
                getRecentlyPlayedVideoUseCase(folderPath).collect { recentlyPlayed ->
                    stateInternal.update { it.copy(recentlyPlayedVideo = recentlyPlayed) }
                }
            }
        }
    }

    private fun collectPreferences() {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect {
                stateInternal.update { currentState ->
                    currentState.copy(preferences = it)
                }
            }
        }
    }

    private fun playSelectedItems(selectedItems: Set<SelectionItem>) {
        viewModelScope.launch {
            val videoUris = selectedItems.toVideoUris()
            output.playVideos(videoUris)
        }
    }

    private fun deleteSelectedItems(selectedItems: Set<SelectionItem>, permanently: Boolean) {
        viewModelScope.launch {
            val videoUris = selectedItems.toVideoUris()
            mediaOperationsService.deleteMedia(videoUris, permanently = permanently)
        }
    }

    private fun shareSelectedItems(selectedItems: Set<SelectionItem>) {
        viewModelScope.launch {
            val videoUris = selectedItems.toVideoUris()
            mediaOperationsService.shareMedia(videoUris)
        }
    }

    private fun transferSelectedItems(selectedItems: Set<SelectionItem>, mode: TransferMode) {
        transferJob = viewModelScope.launch {
            val treeUri = systemService.pickFolder() ?: return@launch
            val videoUris = selectedItems.toVideoUris()
            if (videoUris.isEmpty()) return@launch

            stateInternal.update {
                it.copy(
                    transferFlow = TransferFlowState.Processing(
                        mode = mode,
                        progress = TransferProgress(totalFiles = videoUris.size),
                    ),
                )
            }

            mediaOperationsService.transferMedia(
                uris = videoUris,
                folderUri = treeUri,
                mode = mode,
            ).collect { event ->
                when (event) {
                    is TransferEvent.Progress -> stateInternal.update {
                        (it.transferFlow as? TransferFlowState.Processing)?.let { state ->
                            it.copy(transferFlow = state.copy(progress = event.progress))
                        } ?: it
                    }

                    is TransferEvent.Completed -> {
                        stateInternal.update { it.copy(transferFlow = TransferFlowState.Idle) }
                        showTransferCompleteToast(mode, event.result)
                    }
                }
            }
        }
    }

    private fun showTransferCompleteToast(mode: TransferMode, result: TransferResult) {
        val message = when {
            result.sameFolderSkipped > 0 && result.succeeded == 0 && result.failed == 0 ->
                systemService.getString(R.string.cannot_move_to_same_folder)

            result.failed > 0 -> systemService.getQuantityString(
                if (mode == TransferMode.MOVE) R.plurals.move_failed else R.plurals.copy_failed,
                result.failed,
                result.failed,
            )

            result.originalsNotDeleted -> systemService.getQuantityString(
                R.plurals.moved_videos_originals_remain,
                result.succeeded,
                result.succeeded,
            )

            mode == TransferMode.MOVE -> systemService.getQuantityString(
                R.plurals.moved_videos_result,
                result.succeeded,
                result.succeeded,
            )

            else -> systemService.getQuantityString(
                R.plurals.copied_videos_result,
                result.succeeded,
                result.succeeded,
            )
        }
        systemService.showToast(message, Toast.LENGTH_SHORT)
    }

    private fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        stateInternal.update { it.copy(transferFlow = TransferFlowState.Idle) }
    }

    private fun showMediaInfo(video: Video) {
        viewModelScope.launch {
            val mediaInfo = mediaRepository.getMediaInfo(video.uriString)
            if (mediaInfo != null) {
                stateInternal.update { it.copy(mediaInfo = mediaInfo) }
            }
        }
    }

    private fun renameVideo(uri: Uri, to: String) {
        viewModelScope.launch {
            mediaOperationsService.renameMedia(uri, to)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            stateInternal.update { it.copy(refreshing = true) }
            mediaSynchronizer.refresh()
            stateInternal.update { it.copy(refreshing = false) }
        }
    }

    private fun updateMenu(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences }
        }
    }

    private suspend fun Set<SelectionItem>.toVideos(): List<Video> {
        val preferences = stateInternal.value.preferences
        return flatMap { selectionItem ->
            when (selectionItem) {
                is SelectionItem.Video -> listOfNotNull(mediaRepository.getVideoByUri(selectionItem.uriString))
                is SelectionItem.Folder -> {
                    val videos = getSortedVideosUseCase(selectionItem.path).first()
                    val filteredVideos = if (preferences.mediaViewMode == MediaViewMode.FOLDERS) {
                        videos.filter { it.parentPath == selectionItem.path }
                    } else {
                        videos
                    }
                    filteredVideos
                }
            }
        }.distinctBy(Video::uriString)
    }

    private suspend fun Set<SelectionItem>.toVideoUris(): List<Uri> {
        return toVideos().map { it.uriString.toUri() }
    }
}

@Stable
data class MediaPickerUiState(
    val folderName: String?,
    val refreshing: Boolean = false,
    val recentlyPlayedVideo: Video? = null,
    val mediaDataState: DataState<MediaHolder?> = DataState.Loading,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val mediaInfo: dev.anilbeesetti.nextplayer.core.model.MediaInfo? = null,
    val transferFlow: TransferFlowState = TransferFlowState.Idle,
) {
    val recentlyPlayedFolder: Folder?
        get() = recentlyPlayedVideo?.let { video ->
            (mediaDataState as? DataState.Success)?.value?.folders?.findClosestFolder(video.path)
        }
}

sealed interface TransferFlowState {
    data object Idle : TransferFlowState
    data class Processing(val mode: TransferMode, val progress: TransferProgress) : TransferFlowState
}

sealed interface MediaPickerAction {
    data object OnNavigateUpClick : MediaPickerAction
    data class OnPlayVideo(val uri: Uri) : MediaPickerAction
    data class OnFolderClick(val folderPath: String) : MediaPickerAction
    data object OnSettingsClick : MediaPickerAction
    data object OnSearchClick : MediaPickerAction
    data object Refresh : MediaPickerAction
    data class RenameVideo(val uri: Uri, val to: String) : MediaPickerAction
    data class UpdateMenu(val preferences: ApplicationPreferences) : MediaPickerAction
    data object OnPermissionAccepted : MediaPickerAction
    data class PlaySelectedItems(val selectionItems: Set<SelectionItem>) : MediaPickerAction
    data class DeleteSelectedItems(val selectionItems: Set<SelectionItem>, val permanently: Boolean = false) : MediaPickerAction
    data class ShareSelectedItems(val selectionItems: Set<SelectionItem>) : MediaPickerAction
    data class CopySelectedItems(val selectionItems: Set<SelectionItem>) : MediaPickerAction
    data class MoveSelectedItems(val selectionItems: Set<SelectionItem>) : MediaPickerAction
    data object CancelTransfer : MediaPickerAction
    data class ShowMediaInfo(val video: Video) : MediaPickerAction
    data object DismissMediaInfo : MediaPickerAction
}
