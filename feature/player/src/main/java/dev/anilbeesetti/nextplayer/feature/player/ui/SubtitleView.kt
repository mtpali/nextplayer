package dev.anilbeesetti.nextplayer.feature.player.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.anilbeesetti.nextplayer.core.common.SubtitleFontStorage
import dev.anilbeesetti.nextplayer.core.model.Font
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.feature.player.extensions.toTypeface
import dev.anilbeesetti.nextplayer.feature.player.state.rememberCuesState

@OptIn(UnstableApi::class)
@Composable
fun SubtitleView(
    modifier: Modifier = Modifier,
    player: Player,
    isInPictureInPictureMode: Boolean,
    configuration: SubtitleConfiguration,
) {
    val cuesState = rememberCuesState(player)
    val context = LocalContext.current
    val customStyle = remember(context, configuration) { configuration.toCaptionStyle(context) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> SubtitleView(context) },
        update = { subtitleView ->
            val systemStyle = if (configuration.useSystemCaptionStyle) {
                getSystemService(context, CaptioningManager::class.java)?.let {
                    CaptionStyleCompat.createFromCaptionStyle(it.userStyle)
                }
            } else {
                null
            }
            subtitleView.setStyle(configuration.resolveCaptionStyle(customStyle, systemStyle))
            subtitleView.setApplyEmbeddedStyles(
                configuration.applyEmbeddedStyles && !configuration.hasCustomAppearance,
            )
            subtitleView.setBottomPaddingFraction(configuration.bottomPaddingFraction)
            subtitleView.setCues(cuesState.cues.shiftVerticalPosition(configuration.verticalPosition))
            if (isInPictureInPictureMode) {
                subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            } else if (configuration.useSystemCaptionStyle) {
                subtitleView.setUserDefaultTextSize()
            } else {
                subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
            }
        },
    )
}

@Stable
data class SubtitleConfiguration(
    val useSystemCaptionStyle: Boolean,
    val showBackground: Boolean,
    val font: Font,
    val customFontId: String?,
    val textSize: Int,
    val verticalPosition: Int,
    val textBold: Boolean,
    val textColor: Int,
    val blackOutline: Boolean,
    val applyEmbeddedStyles: Boolean,
) {
    val hasCustomAppearance: Boolean
        get() = font == Font.CUSTOM || textColor != Color.WHITE || blackOutline

    val bottomPaddingFraction: Float
        get() = verticalPosition.coerceIn(0, 30) / 100f
}

@OptIn(UnstableApi::class)
internal fun SubtitleConfiguration.resolveCaptionStyle(
    appStyle: CaptionStyleCompat,
    systemStyle: CaptionStyleCompat?,
): CaptionStyleCompat = if (useSystemCaptionStyle && !hasCustomAppearance) systemStyle ?: appStyle else appStyle

internal fun List<Cue>.shiftVerticalPosition(verticalPosition: Int): List<Cue> {
    val offset = (PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION - verticalPosition.coerceIn(0, 30)) / 100f
    if (offset == 0f) return this
    return map { cue ->
        if (cue.line == Cue.DIMEN_UNSET || cue.lineType != Cue.LINE_TYPE_FRACTION) {
            cue
        } else {
            cue.buildUpon()
                .setLine((cue.line + offset).coerceIn(0f, 1f), Cue.LINE_TYPE_FRACTION)
                .build()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun SubtitleConfiguration.toCaptionStyle(context: Context): CaptionStyleCompat {
    val baseTypeface = if (font == Font.CUSTOM) {
        SubtitleFontStorage.load(context, customFontId) ?: Typeface.DEFAULT
    } else {
        font.toTypeface()
    }
    return CaptionStyleCompat(
        textColor,
        if (showBackground) Color.BLACK else Color.TRANSPARENT,
        Color.TRANSPARENT,
        if (blackOutline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        Color.BLACK,
        Typeface.create(baseTypeface, if (textBold) Typeface.BOLD else Typeface.NORMAL),
    )
}
