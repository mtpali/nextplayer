package dev.anilbeesetti.nextplayer.settings.screens.subtitle

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.SubtitleFontStorage
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.Font
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel(assistedFactory = SubtitlePreferencesViewModel.Factory::class)
class SubtitlePreferencesViewModel @AssistedInject constructor(
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val appContext: Context,
    @Assisted internal var output: Output,
) : MviViewModel<SubtitlePreferencesUiState, SubtitlePreferencesUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
    )

    @AssistedFactory
    interface Factory {
        fun create(output: Output): SubtitlePreferencesViewModel
    }

    private val stateInternal = MutableStateFlow(
        SubtitlePreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
        ),
    )
    override val state: StateFlow<SubtitlePreferencesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { preferences ->
                stateInternal.update { currentState ->
                    currentState.copy(preferences = preferences)
                }
            }
        }
    }

    override fun onAction(action: SubtitlePreferencesUiEvent) {
        when (action) {
            is SubtitlePreferencesUiEvent.NavigateUp -> output.navigateUp()

            is SubtitlePreferencesUiEvent.ShowDialog -> showDialog(action.value)
            is SubtitlePreferencesUiEvent.UpdateSubtitleLanguage -> updateSubtitleLanguage(action.value)
            is SubtitlePreferencesUiEvent.UpdateSubtitleFont -> updateSubtitleFont(action.value)
            is SubtitlePreferencesUiEvent.ImportSubtitleFont -> importSubtitleFont(action.uri)
            is SubtitlePreferencesUiEvent.ToggleSubtitleTextBold -> toggleSubtitleTextBold()
            is SubtitlePreferencesUiEvent.UpdateSubtitleFontSize -> updateSubtitleFontSize(action.value)
            is SubtitlePreferencesUiEvent.UpdateSubtitleVerticalPosition -> updateSubtitleVerticalPosition(action.value)
            is SubtitlePreferencesUiEvent.UpdateSubtitleTextColor -> updateSubtitleTextColor(action.value)
            is SubtitlePreferencesUiEvent.ToggleSubtitleBlackOutline -> toggleSubtitleBlackOutline()
            is SubtitlePreferencesUiEvent.ToggleSubtitleBackground -> toggleSubtitleBackground()
            is SubtitlePreferencesUiEvent.ToggleApplyEmbeddedStyles -> toggleApplyEmbeddedStyles()
            is SubtitlePreferencesUiEvent.UpdateSubtitleEncoding -> updateSubtitleEncoding(action.value)
            is SubtitlePreferencesUiEvent.ToggleUseSystemCaptionStyle -> toggleUseSystemCaptionStyle()
        }
    }

    private fun showDialog(value: SubtitlePreferenceDialog?) {
        stateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updateSubtitleLanguage(value: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(preferredSubtitleLanguage = value)
            }
        }
    }

    private fun updateSubtitleFont(value: Font) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleFont = value)
            }
        }
    }

    private fun importSubtitleFont(uri: Uri) {
        viewModelScope.launch {
            val font = withContext(Dispatchers.IO) {
                runCatching { SubtitleFontStorage.importFont(appContext, uri) }
            }.getOrElse {
                Toast.makeText(appContext, R.string.subtitle_font_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val previousId = preferencesRepository.playerPreferences.value.customSubtitleFontId
            try {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        subtitleFont = Font.CUSTOM,
                        customSubtitleFontId = font.id,
                        customSubtitleFontName = font.displayName,
                    )
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.IO) { SubtitleFontStorage.delete(appContext, font.id) }
                Toast.makeText(appContext, R.string.subtitle_font_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.IO) { SubtitleFontStorage.delete(appContext, previousId) }
        }
    }

    private fun updateSubtitleTextColor(value: Int) {
        if (value != Color.WHITE && value != Color.YELLOW) return
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(subtitleTextColor = value) }
        }
    }

    private fun toggleSubtitleBlackOutline() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(subtitleBlackOutline = !it.subtitleBlackOutline) }
        }
    }

    private fun toggleSubtitleTextBold() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleTextBold = !it.subtitleTextBold)
            }
        }
    }

    private fun updateSubtitleFontSize(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleTextSize = value.coerceIn(10, 60))
            }
        }
    }

    private fun updateSubtitleVerticalPosition(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleVerticalPosition = value.coerceIn(0, 30))
            }
        }
    }

    private fun toggleSubtitleBackground() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleBackground = !it.subtitleBackground)
            }
        }
    }

    private fun toggleApplyEmbeddedStyles() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(applyEmbeddedStyles = !it.applyEmbeddedStyles)
            }
        }
    }

    private fun updateSubtitleEncoding(value: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(subtitleTextEncoding = value) }
        }
    }

    private fun toggleUseSystemCaptionStyle() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(useSystemCaptionStyle = !it.useSystemCaptionStyle) }
        }
    }
}

@Stable
data class SubtitlePreferencesUiState(
    val showDialog: SubtitlePreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
)

sealed interface SubtitlePreferenceDialog {
    data object SubtitleLanguageDialog : SubtitlePreferenceDialog
    data object SubtitleFontDialog : SubtitlePreferenceDialog
    data object SubtitleEncodingDialog : SubtitlePreferenceDialog
    data object SubtitleTextColorDialog : SubtitlePreferenceDialog
}

sealed interface SubtitlePreferencesUiEvent {
    data object NavigateUp : SubtitlePreferencesUiEvent

    data class ShowDialog(val value: SubtitlePreferenceDialog?) : SubtitlePreferencesUiEvent
    data class UpdateSubtitleLanguage(val value: String) : SubtitlePreferencesUiEvent
    data class UpdateSubtitleFont(val value: Font) : SubtitlePreferencesUiEvent
    data class ImportSubtitleFont(val uri: Uri) : SubtitlePreferencesUiEvent
    data object ToggleSubtitleTextBold : SubtitlePreferencesUiEvent
    data class UpdateSubtitleFontSize(val value: Int) : SubtitlePreferencesUiEvent
    data class UpdateSubtitleVerticalPosition(val value: Int) : SubtitlePreferencesUiEvent
    data class UpdateSubtitleTextColor(val value: Int) : SubtitlePreferencesUiEvent
    data object ToggleSubtitleBlackOutline : SubtitlePreferencesUiEvent
    data object ToggleSubtitleBackground : SubtitlePreferencesUiEvent
    data object ToggleApplyEmbeddedStyles : SubtitlePreferencesUiEvent
    data class UpdateSubtitleEncoding(val value: String) : SubtitlePreferencesUiEvent
    data object ToggleUseSystemCaptionStyle : SubtitlePreferencesUiEvent
}
