package dev.anilbeesetti.nextplayer.feature.network.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DownloadFileNameTest {
    @Test
    fun `generic server MIME does not replace APK extension with bin`() {
        val info = resolveDownloadFileInfo("https://example.com/Player.apk?token=abc", mimeType = "application/octet-stream")

        assertEquals("Player.apk", info.name)
        assertEquals("application/vnd.android.package-archive", info.mimeType)
    }

    @Test
    fun `a generic server filename does not hide the real URL extension`() {
        val info = resolveDownloadFileInfo(
            "https://example.com/Player.apk",
            contentDisposition = "attachment; filename=download.bin",
            mimeType = "application/octet-stream",
        )

        assertEquals("Player.apk", info.name)
    }

    @Test
    fun `keeps explicit file extensions when a server sends a conflicting type`() {
        assertEquals("app.apk", resolveDownloadFileInfo("https://example.com/app.apk", mimeType = "application/zip").name)
        assertEquals("book.epub", resolveDownloadFileInfo("https://example.com/book.epub", mimeType = "application/octet-stream").name)
        assertEquals("archive.tar.gz", resolveDownloadFileInfo("https://example.com/archive.tar.gz").name)
    }

    @Test
    fun `uses the final redirect name or falls back to the original informative URL`() {
        assertEquals(
            "app.apk",
            resolveDownloadFileInfo("https://example.com/get/42", resolvedUrl = "https://cdn.example.com/app.apk").name,
        )
        assertEquals(
            "original.apk",
            resolveDownloadFileInfo("https://example.com/original.apk", resolvedUrl = "https://cdn.example.com/download").name,
        )
    }

    @Test
    fun `extended disposition supports Unicode and preserves literal plus signs`() {
        val info = resolveDownloadFileInfo(
            "https://example.com/download",
            contentDisposition = "attachment; filename=fallback.bin; filename*=UTF-8''%D9%81%DB%8C%D9%84%D9%85+1.mp4",
        )

        assertEquals("فیلم+1.mp4", info.name)
    }

    @Test
    fun `quoted disposition can contain semicolons and escaped quotes`() {
        assertEquals("a;b.apk", contentDispositionFileName("attachment; filename=\"a;b.apk\""))
        assertEquals("a\"b.apk", contentDispositionFileName("attachment; filename=\"a\\\"b.apk\""))
    }

    @Test
    fun `invalid extended disposition falls back to the regular name`() {
        assertEquals("app.apk", contentDispositionFileName("attachment; filename*=UTF-8''bad%ZZ; filename=app.apk"))
        assertNull(contentDispositionFileName("attachment; other=\"x; filename=not-a-parameter.apk\""))
    }

    @Test
    fun `APK intent MIME and browser filename survive an opaque URL`() {
        val info = resolveDownloadFileInfo(
            "https://example.com/get/42",
            suggestedName = "Player.apk",
            mimeType = "application/octet-stream",
            mimeTypeHint = "application/vnd.android.package-archive",
        )

        assertEquals("Player.apk", info.name)
        assertEquals("application/vnd.android.package-archive", info.mimeType)
    }

    @Test
    fun `specific MIME supplies an extension for an extensionless or script URL`() {
        assertEquals(
            "download.apk",
            resolveDownloadFileInfo("https://example.com/download", mimeType = "application/vnd.android.package-archive").name,
        )
        assertEquals("get.pdf", resolveDownloadFileInfo("https://example.com/get.php", mimeType = "application/pdf; charset=utf-8").name)
    }

    @Test
    fun `URL decoding and query filename hints preserve the file extension`() {
        assertEquals("a+b.apk", resolveDownloadFileInfo("https://example.com/a+b.apk").name)
        assertEquals("a b.apk", resolveDownloadFileInfo("https://example.com/a%20b.apk").name)
        assertEquals("book.pdf", resolveDownloadFileInfo("https://example.com/get.php?filename=book.pdf&token=123").name)
    }

    @Test
    fun `names cannot introduce paths control characters or dot directories`() {
        val info = resolveDownloadFileInfo("https://example.com/get", suggestedName = "../bad\u0000name.apk")

        assertEquals(".._bad_name.apk", info.name)
        assertEquals("download", sanitizeFileName(".."))
        assertEquals("download", sanitizeFileName("  "))
    }

    @Test
    fun `long Unicode names fit the filesystem byte limit and retain their extension`() {
        val info = resolveDownloadFileInfo("https://example.com/get", suggestedName = "فیلم".repeat(100) + ".mp4")

        assertTrue(info.name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(info.name.endsWith(".mp4"))
    }
}
