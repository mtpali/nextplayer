package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Color
import androidx.annotation.OptIn
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
        textBold = true,
        textColor = Color.WHITE,
        blackOutline = false,
        applyEmbeddedStyles = true,
    )
}
