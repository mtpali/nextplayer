package dev.anilbeesetti.nextplayer.feature.network.download

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.sun.net.httpserver.HttpServer
import dev.anilbeesetti.nextplayer.core.ui.R
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DownloadNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<SegmentedDownloadService>
    private lateinit var service: SegmentedDownloadService
    private lateinit var store: DownloadStore

    @Before
    fun setUp() {
        context.getSharedPreferences("segmented_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildService(SegmentedDownloadService::class.java).create()
        service = controller.get()
        store = DownloadStore(context)
    }

    @After
    fun tearDown() {
        controller.destroy()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun `running and queued notifications have separate pause and cancel actions`() {
        val record = store.create("https://example.com/file.apk")
        listOf(DownloadStatus.PENDING, DownloadStatus.RUNNING).forEach { status ->
            val notification = service.buildDownloadNotification(record.copy(status = status))

            assertEquals(listOf(service.getString(R.string.pause_download), service.getString(R.string.cancel_download)), notification.actions.map { it.title.toString() })
            assertNotEquals(notification.actions[0].actionIntent, notification.actions[1].actionIntent)
            assertEquals("player.download.CANCEL", shadowOf(notification.actions[1].actionIntent).savedIntent.action)
        }
    }

    @Test
    fun `paused notifications offer resume and cancel`() {
        val record = store.create("https://example.com/file.apk").copy(status = DownloadStatus.PAUSED)
        val notification = service.buildDownloadNotification(record)

        assertEquals(listOf(service.getString(R.string.resume), service.getString(R.string.cancel_download)), notification.actions.map { it.title.toString() })
    }

    @Test
    fun `cancel action removes an unfinished record and its parts without affecting another download`() {
        val record = store.create("https://example.com/file.apk")
        val other = store.create("https://example.com/other.apk")
        store.update(other.id) { it.copy(status = DownloadStatus.SUCCESSFUL) }
        val parts = downloadPartsDirectory(context, record.id).apply { mkdirs() }
        parts.resolve("part-0").writeText("partial content")
        val notification = service.buildDownloadNotification(record)

        sendAction(notification, R.string.cancel_download)

        assertNull(store.get(record.id))
        assertFalse(parts.exists())
        assertNotNull(store.get(other.id))
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == record.title })
        DownloadManagerClient(context)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `pause retains partial files and a following cancel discards them`() {
        val record = store.create("https://example.com/file.apk")
        val parts = downloadPartsDirectory(context, record.id).apply { mkdirs() }
        parts.resolve("part-0").writeText("partial content")

        sendAction(service.buildDownloadNotification(record), R.string.pause_download)

        val paused = store.get(record.id)!!
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertTrue(parts.exists())

        sendAction(service.buildDownloadNotification(paused), R.string.cancel_download)

        assertNull(store.get(record.id))
        assertFalse(parts.exists())
    }

    @Test
    fun `a stale cancel action does not remove a completed download`() {
        val record = store.create("https://example.com/file.apk")
        val notification = service.buildDownloadNotification(record)
        store.update(record.id) { it.copy(status = DownloadStatus.SUCCESSFUL, localUri = "content://downloads/1") }

        sendAction(notification, R.string.cancel_download)

        assertEquals(DownloadStatus.SUCCESSFUL, store.get(record.id)?.status)
        assertEquals("content://downloads/1", store.get(record.id)?.localUri)
    }

    @Test
    fun `cancel interrupts an active transfer and cleans its temporary directory`() {
        val transferring = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/file.apk") { exchange ->
                try {
                    val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-4194303"
                    val start = range.substringAfter("bytes=").substringBefore('-').toLong()
                    val end = range.substringAfter('-').toLong()
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/4194304")
                    exchange.responseHeaders.add("ETag", "\"test-file\"")
                    exchange.sendResponseHeaders(206, end - start + 1L)
                    exchange.responseBody.use { output ->
                        if (range != "bytes=0-0") transferring.countDown()
                        var remaining = end - start + 1L
                        while (remaining > 0L) {
                            val block = ByteArray(minOf(4096L, remaining).toInt())
                            output.write(block)
                            output.flush()
                            remaining -= block.size
                            if (range != "bytes=0-0") Thread.sleep(20L)
                        }
                    }
                } catch (_: Exception) {
                    // Cancelling closes active response streams.
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        try {
            val record = store.create("http://127.0.0.1:${server.address.port}/file.apk")
            service.onStartCommand(Intent(context, SegmentedDownloadService::class.java).setAction("player.download.START").putExtra("download_id", record.id), 0, 1)
            assertTrue("The transfer should start", transferring.await(10, TimeUnit.SECONDS))

            sendAction(service.buildDownloadNotification(store.get(record.id)!!), R.string.cancel_download)

            val parts = downloadPartsDirectory(context, record.id)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (parts.exists() && System.nanoTime() < deadline) Thread.sleep(20L)
            assertNull(store.get(record.id))
            assertFalse("Cancelled workers should delete their parts", parts.exists())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun sendAction(notification: Notification, label: Int) {
        val action = notification.actions.single { it.title.toString() == service.getString(label) }
        service.onStartCommand(shadowOf(action.actionIntent).savedIntent, 0, 1)
    }
}
