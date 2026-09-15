package dev.anilbeesetti.nextplayer.feature.network.screens.list

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextOutlinedTextField
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.network.download.DownloadStatus
import dev.anilbeesetti.nextplayer.feature.network.download.ManagedDownload
import dev.anilbeesetti.nextplayer.feature.network.download.formatByteCount
import kotlin.math.roundToInt

@Composable
fun NetworkScreen(viewModel: NetworkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NetworkScreenContent(state = state, onAction = viewModel::onAction)
}

@Suppress("DEPRECATION")
@Composable
internal fun NetworkScreenContent(
    state: NetworkUiState,
    onAction: (NetworkAction) -> Unit,
) {
    var streamUrl by rememberSaveable { mutableStateOf("") }
    var downloadUrl by rememberSaveable { mutableStateOf("") }
    var downloadToRemove by remember { mutableStateOf<ManagedDownload?>(null) }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.stringRes) }
    val navigationBottomPadding = LocalNavigationBottomPadding.current

    LaunchedEffect(messageText) {
        messageText?.let {
            snackbarHostState.showSnackbar(it)
            onAction(NetworkAction.MessageShown)
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.network),
                fontWeight = FontWeight.Bold,
                actions = {
                    IconButton(onClick = { onAction(NetworkAction.OpenSettings) }) {
                        Icon(NextIcons.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding.copy(bottom = 0.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp).copy(
                    bottom = navigationBottomPadding + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    UrlActionCard(
                        title = stringResource(R.string.online_playback),
                        description = stringResource(R.string.online_playback_description),
                        icon = NextIcons.Play,
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        onPaste = { clipboard.getText()?.text?.let { streamUrl = it } },
                        actionText = stringResource(R.string.play),
                        onAction = { onAction(NetworkAction.OpenStream(streamUrl)) },
                    )
                }
                item {
                    UrlActionCard(
                        title = stringResource(R.string.download_manager),
                        description = stringResource(R.string.download_manager_description),
                        icon = NextIcons.Download,
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        onPaste = { clipboard.getText()?.text?.let { downloadUrl = it } },
                        actionText = stringResource(R.string.download),
                        onAction = { onAction(NetworkAction.EnqueueDownload(downloadUrl)) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.downloads),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.downloads.isEmpty()) {
                    item { EmptyDownloads() }
                } else {
                    items(state.downloads, key = ManagedDownload::id) { item ->
                        DownloadItem(
                            item = item,
                            onOpen = { onAction(NetworkAction.OpenDownload(item.id)) },
                            onRetry = { onAction(NetworkAction.RetryDownload(item.id)) },
                            onRemove = { downloadToRemove = item },
                        )
                    }
                }
            }
        }
    }

    downloadToRemove?.let { item ->
        NextDialog(
            onDismissRequest = { downloadToRemove = null },
            title = { Text(stringResource(R.string.remove_download)) },
            content = { Text(stringResource(R.string.remove_download_confirmation, item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(NetworkAction.RemoveDownload(item.id))
                        downloadToRemove = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { downloadToRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun UrlActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    actionText: String,
    onAction: () -> Unit,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            NextOutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.example_url)) },
                trailingIcon = {
                    IconButton(onClick = onPaste) {
                        Icon(NextIcons.Copy, contentDescription = stringResource(R.string.paste))
                    }
                },
                singleLine = true,
            )
            Button(
                onClick = onAction,
                enabled = value.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(actionText)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadItem(
    item: ManagedDownload,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (item.status == DownloadStatus.SUCCESSFUL) {
                        NextIcons.DownloadDone
                    } else {
                        NextIcons.Download
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { stringResource(R.string.unknown_file) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(item.status.stringRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.status == DownloadStatus.SUCCESSFUL) {
                    IconButton(onClick = onOpen) {
                        Icon(NextIcons.FileOpen, contentDescription = stringResource(R.string.open))
                    }
                }
                if (item.status == DownloadStatus.FAILED) {
                    IconButton(onClick = onRetry) {
                        Icon(NextIcons.Replay, contentDescription = stringResource(R.string.retry))
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(NextIcons.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
            if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.PENDING) {
                item.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.download_progress, (progress * 100).roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (item.totalBytes > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            R.string.download_size_progress,
                            formatByteCount(item.downloadedBytes),
                            formatByteCount(item.totalBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.remainingBytes?.let { remaining ->
                        Text(
                            text = stringResource(R.string.download_remaining, formatByteCount(remaining)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.PENDING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (item.bytesPerSecond > 0L) {
                            stringResource(R.string.download_speed, formatByteCount(item.bytesPerSecond))
                        } else {
                            stringResource(R.string.download_speed_calculating)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.download_threads, item.threadCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloads() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            NextIcons.Download,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_downloads),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val NetworkMessage.stringRes: Int
    @StringRes get() = when (this) {
        NetworkMessage.INVALID_STREAM_URL -> R.string.invalid_stream_url
        NetworkMessage.INVALID_DOWNLOAD_URL -> R.string.invalid_download_url
        NetworkMessage.DOWNLOAD_STARTED -> R.string.download_started
        NetworkMessage.DOWNLOAD_FAILED -> R.string.download_failed
        NetworkMessage.CANNOT_OPEN_DOWNLOAD -> R.string.cannot_open_download
    }

private val DownloadStatus.stringRes: Int
    @StringRes get() = when (this) {
        DownloadStatus.PENDING -> R.string.download_pending
        DownloadStatus.RUNNING -> R.string.download_running
        DownloadStatus.PAUSED -> R.string.download_paused
        DownloadStatus.SUCCESSFUL -> R.string.download_complete
        DownloadStatus.FAILED -> R.string.download_failed
    }

@PreviewLightDark
@Composable
private fun NetworkScreenPreview() {
    NextPlayerTheme {
        NetworkScreenContent(
            state = NetworkUiState(
                downloads = listOf(
                    ManagedDownload(
                        id = 1,
                        title = "sample-video.mp4",
                        source = "https://example.com/sample-video.mp4",
                        localUri = null,
                        mimeType = "video/mp4",
                        downloadedBytes = 50,
                        totalBytes = 100,
                        bytesPerSecond = 20,
                        threadCount = 8,
                        status = DownloadStatus.RUNNING,
                        reason = 0,
                        updatedAt = 0,
                    ),
                ),
            ),
            onAction = {},
        )
    }
}
