package dev.anilbeesetti.nextplayer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadIntentResolutionTest {
    @Test
    fun `file manager video opens player without download option`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://media/external/video/media/42"), "video/mp4")
        }

        assertTrue(handlersFor(intent).any { it.endsWith(".PlayerActivity") })
        assertFalse(handlersFor(intent).any { it.endsWith(".DownloadRequestActivity") })
        assertFalse(handlersFor(intent).any { it.endsWith(".OpenDownloadLinkActivity") })
    }

    @Test
    fun `Firefox typed HTTP download includes download option`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("https://example.com/file.zip"), "application/octet-stream")
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        assertTrue(handlersFor(intent).any { it.endsWith(".DownloadRequestActivity") })
        assertFalse(handlersFor(intent).any { it.endsWith(".OpenDownloadLinkActivity") })
    }

    @Test
    fun `ordinary untyped browser link still offers the legacy link handoff`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))

        assertFalse(handlersFor(intent).any { it.endsWith(".DownloadRequestActivity") })
        assertTrue(handlersFor(intent).any { it.endsWith(".OpenDownloadLinkActivity") })
    }

    @Test
    fun `sharing a URL offers download but sharing a video does not`() {
        val sharedUrl = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val sharedVideo = Intent(Intent.ACTION_SEND).apply { type = "video/mp4" }

        assertTrue(handlersFor(sharedUrl).any { it.endsWith(".DownloadRequestActivity") })
        assertFalse(handlersFor(sharedVideo).any { it.endsWith(".DownloadRequestActivity") })
    }

    private fun handlersFor(intent: Intent): List<String> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName == activity.packageName }
            .map { it.activityInfo.name }
    }
}
