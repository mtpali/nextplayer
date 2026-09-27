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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.anilbeesetti.nextplayer.core.common.SubtitleFontStorage
import dev.anilbeesetti.nextplayer.core.model.Font
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
            subtitleView.setStyle(systemStyle ?: customStyle)
            subtitleView.setApplyEmbeddedStyles(
                configuration.applyEmbeddedStyles &&
                    (configuration.useSystemCaptionStyle || !configuration.hasCustomAppearance),
            )
            subtitleView.setCues(cuesState.cues)
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
    val textBold: Boolean,
    val textColor: Int,
    val blackOutline: Boolean,
    val applyEmbeddedStyles: Boolean,
) {
    val hasCustomAppearance: Boolean
        get() = font == Font.CUSTOM || textColor != Color.WHITE || blackOutline
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
