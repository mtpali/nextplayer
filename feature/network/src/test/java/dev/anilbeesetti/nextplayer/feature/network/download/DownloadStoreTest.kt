package dev.anilbeesetti.nextplayer.feature.network.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DownloadStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearStore() {
        context.getSharedPreferences("segmented_downloads", 0).edit().clear().commit()
    }

    @Test
    fun `browser filename and MIME hints persist with an opaque download URL`() {
        val store = DownloadStore(context)
        val created = store.create(
            "https://example.com/download?id=1",
            suggestedName = "Player.apk",
            mimeTypeHint = "application/vnd.android.package-archive",
        )
        val restored = DownloadStore(context).get(created.id)!!

        assertEquals("Player.apk", restored.title)
        assertEquals("Player.apk", restored.suggestedName)
        assertEquals("application/vnd.android.package-archive", restored.mimeType)
    }

    @Test
    fun `opening the download screen does not restart a deliberately paused download`() {
        val store = DownloadStore(context)
        val created = store.create("https://example.com/Player.apk")
        store.update(created.id) { it.copy(status = DownloadStatus.PAUSED) }

        DownloadManagerClient(context)

        assertNull(shadowOf(context).nextStartedService)
        assertEquals(DownloadStatus.PAUSED, store.get(created.id)?.status)
    }

    @Test
    fun `older records without filename hints remain readable`() {
        context.getSharedPreferences("segmented_downloads", 0).edit().putString(
            "downloads",
            """[{"id":1,"title":"old.apk","source":"https://example.com/old.apk","status":"PAUSED"}]""",
        ).commit()

        val restored = DownloadStore(context).get(1L)!!

        assertEquals("old.apk", restored.title)
        assertNull(restored.suggestedName)
        assertNull(restored.mimeTypeHint)
    }
}
