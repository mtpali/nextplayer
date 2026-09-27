package dev.anilbeesetti.nextplayer.feature.network.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlValidatorTest {
    @Test
    fun `playback accepts supported remote schemes`() {
        assertTrue(isValidPlayableUrl("https://cdn.example.com/video.m3u8"))
        assertTrue(isValidPlayableUrl("http://example.com/video.mp4"))
        assertTrue(isValidPlayableUrl("rtsp://camera.example.com/live"))
    }

    @Test
    fun `download only accepts http links with a host`() {
        assertTrue(isValidHttpUrl("https://example.com/archive.zip"))
        assertFalse(isValidHttpUrl("rtsp://example.com/live"))
        assertFalse(isValidHttpUrl("file:///sdcard/video.mp4"))
        assertFalse(isValidHttpUrl("https:///missing-host.mp4"))
        assertFalse(isValidHttpUrl("not a url"))
    }
}
