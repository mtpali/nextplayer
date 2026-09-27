package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPreferencesSubtitleTest {
    @Test
    fun `old subtitle settings retain defaults for new appearance options`() {
        val preferences = Json.decodeFromString(
            PlayerPreferences.serializer(),
            """{"subtitleFont":"SERIF","subtitleTextSize":24,"subtitleBackground":true}""",
        )

        assertEquals(Font.SERIF, preferences.subtitleFont)
        assertEquals(24, preferences.subtitleTextSize)
        assertEquals(PlayerPreferences.DEFAULT_SUBTITLE_TEXT_COLOR, preferences.subtitleTextColor)
        assertFalse(preferences.subtitleBlackOutline)
        assertNull(preferences.customSubtitleFontId)
    }

    @Test
    fun `custom font selection and appearance survive serialization`() {
        val preferences = PlayerPreferences(
            subtitleFont = Font.CUSTOM,
            customSubtitleFontId = "d38d13c1-6df1-4f1f-8cb0-b116782ad8d2.ttf",
            customSubtitleFontName = "My Font.ttf",
            subtitleTextColor = 0xFFFFFF00.toInt(),
            subtitleBlackOutline = true,
        )

        val encoded = Json.encodeToString(PlayerPreferences.serializer(), preferences)
        assertEquals(preferences, Json.decodeFromString(PlayerPreferences.serializer(), encoded))
    }
}
