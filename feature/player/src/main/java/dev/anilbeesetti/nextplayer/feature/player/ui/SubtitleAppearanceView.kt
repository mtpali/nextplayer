package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.requestFocusUntilLanded
import kotlin.math.roundToInt

@Composable
fun BoxScope.SubtitleAppearanceView(
    show: Boolean,
    textSize: Int,
    verticalPosition: Int,
    useSystemCaptionStyle: Boolean,
    onTextSizePreview: (Int) -> Unit,
    onVerticalPositionPreview: (Int) -> Unit,
    onTextSizeSelected: (Int) -> Unit,
    onVerticalPositionSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val focusRequester = remember { FocusRequester() }
    val isTv = LocalContext.current.isTelevision
    LaunchedEffect(show) {
        if (isTv) focusRequester.requestFocusUntilLanded(attempts = 5)
    }

    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp)
            .fillMaxWidth(0.94f)
            .widthIn(max = 420.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.subtitle_adjustments),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
            if (useSystemCaptionStyle) {
                Text(
                    text = stringResource(R.string.subtitle_size_overrides_system),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SubtitleAdjustment(
                title = stringResource(R.string.subtitle_text_size),
                valueDescription = stringResource(R.string.subtitle_size_value, textSize),
                value = textSize,
                range = 10..60,
                modifier = Modifier.focusRequester(focusRequester),
                onPreview = onTextSizePreview,
                onSelected = onTextSizeSelected,
            )
            SubtitleAdjustment(
                title = stringResource(R.string.subtitle_vertical_position),
                valueDescription = stringResource(R.string.subtitle_vertical_position_desc, verticalPosition),
                value = verticalPosition,
                range = 0..30,
                onPreview = onVerticalPositionPreview,
                onSelected = onVerticalPositionSelected,
            )
        }
    }
}

@Composable
private fun SubtitleAdjustment(
    modifier: Modifier = Modifier,
    title: String,
    valueDescription: String,
    value: Int,
    range: IntRange,
    onPreview: (Int) -> Unit,
    onSelected: (Int) -> Unit,
) {
    val currentValue = remember { mutableIntStateOf(value) }
    LaunchedEffect(value) { currentValue.intValue = value }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = valueDescription, style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = title },
        value = value.toFloat(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        onValueChange = {
            currentValue.intValue = it.roundToInt()
            onPreview(currentValue.intValue)
        },
        onValueChangeFinished = { onSelected(currentValue.intValue) },
    )
}
