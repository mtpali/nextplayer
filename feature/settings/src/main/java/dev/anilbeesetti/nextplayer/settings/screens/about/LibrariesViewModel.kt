package dev.anilbeesetti.nextplayer.settings.screens.about

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel(assistedFactory = LibrariesViewModel.Factory::class)
class LibrariesViewModel @AssistedInject constructor(
    @ApplicationContext context: Context,
    @Assisted internal var output: Output,
) : MviViewModel<LibrariesUiState, LibrariesAction>() {

    data class Output(val navigateUp: () -> Unit)

    @AssistedFactory
    interface Factory {
        fun create(output: Output): LibrariesViewModel
    }

    private val stateInternal = MutableStateFlow(LibrariesUiState())
    override val state: StateFlow<LibrariesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            val libraries = withContext(Dispatchers.Default) {
                Libs.Builder().withContext(context).build().libraries
            }
            stateInternal.value = LibrariesUiState(libraries)
        }
    }

    override fun onAction(action: LibrariesAction) {
        when (action) {
            is LibrariesAction.NavigateUp -> output.navigateUp()
        }
    }
}

data class LibrariesUiState(val libraries: List<Library> = emptyList())

sealed interface LibrariesAction {
    data object NavigateUp : LibrariesAction
}
