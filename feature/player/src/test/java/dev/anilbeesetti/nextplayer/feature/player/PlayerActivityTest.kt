package dev.anilbeesetti.nextplayer.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerActivityTest {

    @Test
    fun currentUriWithExplicitQueueStartsNewPlaybackQueue() {
        assertFalse(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitQueue = true,
            ),
        )
    }

    @Test
    fun currentUriWithoutExplicitQueueResumesExistingPlayback() {
        assertTrue(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitQueue = false,
            ),
        )
    }

    @Test
    fun returningFromBackgroundAlwaysResumesExistingPlayback() {
        assertTrue(
            shouldResumeExistingPlayback(
                returningFromBackground = true,
                isRequestedUriCurrent = true,
                hasExplicitQueue = true,
            ),
        )
    }
}
