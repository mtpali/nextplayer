package dev.anilbeesetti.nextplayer.feature.network.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentedDownloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun eightRangesCoverWholeFileWithoutGaps() {
        val ranges = splitIntoRanges(totalBytes = 10_003L)

        assertEquals(8, ranges.size)
        assertEquals(0L, ranges.first().start)
        assertEquals(10_002L, ranges.last().endInclusive)
        assertEquals(10_003L, ranges.sumOf(ByteRange::length))
        ranges.zipWithNext().forEach { (first, second) ->
            assertEquals(first.endInclusive + 1L, second.start)
        }
    }

    @Test
    fun tinyFileDoesNotCreateEmptyRanges() {
        val ranges = splitIntoRanges(totalBytes = 3L)

        assertEquals(3, ranges.size)
        assertTrue(ranges.all { it.length == 1L })
    }

    @Test
    fun fileNameRemovesUnsafeCharacters() {
        assertEquals("video_________.mp4", sanitizeFileName("video\\/:*?\"<>|.mp4"))
    }

    @Test
    fun sharedDownloadsKeepExistingFilesWhenNamesCollide() {
        val directory = temporaryFolder.newFolder()
        val existing = File(directory, "video.mp4").apply { writeText("existing video") }

        val first = uniqueFile(directory, "video.mp4")
        val second = uniqueFile(directory, "video.mp4")

        assertEquals("video (1).mp4", first.name)
        assertEquals("video (2).mp4", second.name)
        assertTrue(first.exists() && second.exists())
        assertEquals("existing video", existing.readText())
    }

    @Test
    fun byteCountUsesReadableBinaryUnits() {
        assertEquals("512 B", formatByteCount(512L))
        assertEquals("1.0 KB", formatByteCount(1024L))
        assertEquals("1.5 MB", formatByteCount(1_572_864L))
    }
}
