package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import dev.anilbeesetti.nextplayer.core.model.Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SubtitleViewStyleTest {
    @Test
    fun `yellow subtitles with black outline use Media3 outline edges`() {
        val configuration = baseConfiguration().copy(textColor = Color.YELLOW, blackOutline = true)

        val style = configuration.toCaptionStyle(RuntimeEnvironment.getApplication())

        assertEquals(Color.YELLOW, style.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
        assertEquals(Color.BLACK, style.edgeColor)
        assertTrue(configuration.hasCustomAppearance)
    }

    @Test
    fun `black outline overrides a system caption style with white edges`() {
        val configuration = baseConfiguration().copy(useSystemCaptionStyle = true, blackOutline = true)
        val appStyle = configuration.toCaptionStyle(RuntimeEnvironment.getApplication())
        val systemStyle = CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.WHITE,
            null,
        )

        val effectiveStyle = configuration.resolveCaptionStyle(appStyle, systemStyle)

        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, effectiveStyle.edgeType)
        assertEquals(Color.BLACK, effectiveStyle.edgeColor)
    }

    @Test
    fun `system caption style applies when no custom appearance is selected`() {
        val configuration = baseConfiguration().copy(useSystemCaptionStyle = true)
        val appStyle = configuration.toCaptionStyle(RuntimeEnvironment.getApplication())
        val systemStyle = CaptionStyleCompat(
            Color.YELLOW,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.WHITE,
            null,
        )

        assertEquals(systemStyle, configuration.resolveCaptionStyle(appStyle, systemStyle))
    }

    @Test
    fun `vertical position maps to a safe bottom margin`() {
        assertEquals(0.08f, baseConfiguration().bottomPaddingFraction, 0.0001f)
        assertEquals(0f, baseConfiguration().copy(verticalPosition = -10).bottomPaddingFraction, 0.0001f)
        assertEquals(0.30f, baseConfiguration().copy(verticalPosition = 40).bottomPaddingFraction, 0.0001f)
    }

    @Test
    fun `vertical position shifts fractionally positioned cues but leaves authored line numbers intact`() {
        val positioned = Cue.Builder().setText("subtitle").setLine(0.85f, Cue.LINE_TYPE_FRACTION).build()
        val defaultPosition = Cue.Builder().setText("default").build()
        val numberedLine = Cue.Builder().setText("numbered").setLine(-1f, Cue.LINE_TYPE_NUMBER).build()

        val shifted = listOf(positioned, defaultPosition, numberedLine).shiftVerticalPosition(18)

        assertEquals(0.75f, shifted[0].line, 0.0001f)
        assertEquals(Cue.DIMEN_UNSET, shifted[1].line, 0f)
        assertEquals(-1f, shifted[2].line, 0f)
        assertEquals(0.85f, listOf(positioned).shiftVerticalPosition(8)[0].line, 0f)
    }

    @Test
    fun `default subtitles retain their white text and shadow`() {
        val configuration = baseConfiguration()

        val style = configuration.toCaptionStyle(RuntimeEnvironment.getApplication())

        assertEquals(Color.WHITE, style.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
        assertFalse(configuration.hasCustomAppearance)
    }

    private fun baseConfiguration() = SubtitleConfiguration(
        useSystemCaptionStyle = false,
        showBackground = false,
        font = Font.DEFAULT,
        customFontId = null,
        textSize = 20,
        verticalPosition = 8,
        textBold = true,
        textColor = Color.WHITE,
        blackOutline = false,
        applyEmbeddedStyles = true,
    )
}
