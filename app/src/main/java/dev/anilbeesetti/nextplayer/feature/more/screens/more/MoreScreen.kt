package dev.anilbeesetti.nextplayer.feature.more.screens.more

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.components.rememberRestorableFocusState
import dev.anilbeesetti.nextplayer.core.ui.components.restorableFocusGroup
import dev.anilbeesetti.nextplayer.core.ui.components.restorableFocusItem
import dev.anilbeesetti.nextplayer.core.ui.components.thenIf
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.VideoGridItem

@Composable
fun MoreScreen(
    viewModel: MoreViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MoreScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun MoreScreenContent(
    state: MoreUiState,
    onAction: (MoreAction) -> Unit,
) {
    BindTopLevelFab(TopLevelFabKey.MORE, NextIcons.Settings) { onAction(MoreAction.OpenSettings) }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.more),
                fontWeight = FontWeight.Bold,
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding.copy(bottom = 0.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .tvListFocus()
                    .padding(horizontal = 8.dp)
                    .padding(top = 8.dp, bottom = scaffoldPadding.calculateBottomPadding() + LocalNavigationBottomPadding.current + 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (MediaOperationsService.supportsTrash()) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth().tvFocusRing(),
                        onClick = { onAction(MoreAction.OpenTrash) },
                    ) {
                        Icon(
                            imageVector = NextIcons.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.trash))
                    }
                }
                HistorySection(
                    history = state.history.result.orEmpty().take(10),
                    preferences = state.preferences,
                    onMoreClick = { onAction(MoreAction.OpenHistory) },
                    onVideoClick = { onAction(MoreAction.PlayVideo(it.uriString)) },
                )
            }
        }
    }
}

@Composable
private fun HistorySection(
    history: List<Video>,
    preferences: ApplicationPreferences,
    onMoreClick: () -> Unit,
    onVideoClick: (Video) -> Unit,
) {
    if (history.isEmpty()) return
    val isTv = LocalContext.current.isTelevision
    val historyButtonFocusRequester = remember { FocusRequester() }
    val historyFocusState = rememberRestorableFocusState()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            // Keep the full header in the vertical focus path, including its empty title area.
            modifier = Modifier.fillMaxWidth().thenIf(isTv) { focusGroup() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier
                    .focusRequester(historyButtonFocusRequester)
                    .focusProperties { if (isTv) down = historyFocusState.requester }
                    .tvFocusRing(),
            ) {
                Icon(imageVector = NextIcons.ArrowForward, contentDescription = stringResource(R.string.history))
            }
        }
        LazyRow(
            // The screen owns initial focus; this region restores a video only when entered.
            modifier = Modifier.restorableFocusGroup(historyFocusState, ready = false),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(history, key = { it.uriString }) { video ->
                VideoGridItem(
                    video = video,
                    isRecentlyPlayedVideo = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                    preferences = preferences,
                    modifier = Modifier
                        .width(140.dp)
                        .restorableFocusItem(historyFocusState, video.uriString)
                        .focusProperties { if (isTv) up = historyButtonFocusRequester },
                    onClick = { onVideoClick(video) },
                )
            }
        }
    }
}

@Preview
@Composable
private fun MoreScreenContentPreview() {
    MoreScreenContent(state = MoreUiState(), onAction = {})
}
