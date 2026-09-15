package dev.anilbeesetti.nextplayer.feature.network.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRequestTest {
    @Test
    fun `extracts a download URL from Firefox extra text`() {
        val result = findHttpUrl(
            listOf("Download https://example.com/releases/player.apk with an external app"),
        )

        assertEquals("https://example.com/releases/player.apk", result)
    }

    @Test
    fun `ignores non-http values`() {
        assertNull(findHttpUrl(listOf("content://downloads/42", "player.apk")))
    }
}
