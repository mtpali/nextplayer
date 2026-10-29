package dev.anilbeesetti.nextplayer.feature.network.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadHttpClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val executor = Executors.newFixedThreadPool(8)
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private var payload = ByteArray(128 * 1024 + 7) { (it % 251).toByte() }
    private var etag: String? = "\"version-1\""
    private var handler: (HttpExchange) -> Unit = { serveFile(it) }
    private val url get() = "http://127.0.0.1:${server.address.port}/file.apk"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            requests += Request(exchange.requestHeaders.getFirst("Range"), exchange.requestHeaders.getFirst("If-Range"))
            try {
                handler(exchange)
            } catch (_: IOException) {
                // Probe requests and cancelled workers intentionally close their response bodies.
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test(timeout = 15_000)
    fun `parallel requests reconstruct exactly the original file`() = runBlocking {
        val client = client()
        val remote = client.probe(url)
        val started = mutableListOf<Int>()
        val result = client.download(remote, temporaryFolder.newFolder(), { count, _ -> started += count }, {})

        assertTrue(remote.supportsRanges)
        assertEquals(listOf(4), started)
        assertArrayEquals(payload, contents(result))
        assertEquals(payload.size.toLong(), result.totalBytes)
        assertTrue(requests.drop(1).all { it.validator == etag })
    }

    @Test(timeout = 15_000)
    fun `resume requests only the missing bytes of an unchanged file`() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val first = client()
        val result = first.download(first.probe(url), directory, { _, _ -> }, {})
        val firstPart = result.files.first()
        val savedBytes = firstPart.length() / 2
        RandomAccessFile(firstPart, "rw").use { it.setLength(savedBytes) }
        requests.clear()

        val resumed = client()
        val completed = resumed.download(resumed.probe(url), directory, { _, _ -> }, {})

        assertArrayEquals(payload, contents(completed))
        assertEquals(2, requests.size) // One probe and one incomplete part.
        assertEquals("bytes=$savedBytes-${splitIntoRanges(payload.size.toLong(), 4).first().endInclusive}", requests.last().range)
    }

    @Test(timeout = 15_000)
    fun `a changed validator discards cached parts even when the size is unchanged`() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val first = client()
        first.download(first.probe(url), directory, { _, _ -> }, {})
        payload = payload.map { (it + 1).toByte() }.toByteArray()
        etag = "\"version-2\""
        requests.clear()

        val second = client()
        val completed = second.download(second.probe(url), directory, { _, _ -> }, {})

        assertArrayEquals(payload, contents(completed))
        assertEquals(5, requests.size)
        assertTrue(requests.drop(1).all { it.validator == etag })
    }

    @Test(timeout = 15_000)
    fun `cached parts without a validator are not silently reused`() = runBlocking {
        etag = null
        val directory = temporaryFolder.newFolder()
        val first = client()
        val parts = first.download(first.probe(url), directory, { _, _ -> }, {})
        parts.files.first().writeBytes(ByteArray(parts.files.first().length().toInt()))
        requests.clear()

        val second = client()
        val completed = second.download(second.probe(url), directory, { _, _ -> }, {})

        assertArrayEquals(payload, contents(completed))
        assertEquals(5, requests.size)
    }

    @Test(timeout = 15_000)
    fun `a server ignoring ranges after the probe falls back to one connection`() = runBlocking {
        handler = { exchange ->
            serveFile(exchange, ranges = exchange.requestHeaders.getFirst("Range") == "bytes=0-0")
        }
        val client = client()
        val counts = mutableListOf<Int>()
        val result = client.download(client.probe(url), temporaryFolder.newFolder(), { count, _ -> counts += count }, {})

        assertEquals(listOf(4, 1), counts)
        assertEquals(1, result.files.size)
        assertArrayEquals(payload, contents(result))
        assertTrue(requests.any { it.range == null })
    }

    @Test(timeout = 15_000)
    fun `a wrong Content-Range offset never gets appended to a part`() = runBlocking {
        handler = { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && range != "bytes=0-0") {
                exchange.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                exchange.sendResponseHeaders(206, 1)
                exchange.responseBody.write(byteArrayOf(payload[0]))
            } else {
                serveFile(exchange)
            }
        }
        val client = client()
        val result = client.download(client.probe(url), temporaryFolder.newFolder(), { _, _ -> }, {})

        assertEquals(1, result.files.size)
        assertArrayEquals(payload, contents(result))
    }

    @Test(timeout = 15_000)
    fun `temporary server errors are retried`() = runBlocking {
        val attempts = AtomicInteger()
        handler = { exchange ->
            if (attempts.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                serveFile(exchange)
            }
        }

        assertTrue(client().probe(url).supportsRanges)
        assertEquals(3, attempts.get())
    }

    @Test(timeout = 15_000)
    fun `a permanent HTTP error is not retried`() = runBlocking {
        handler = { it.sendResponseHeaders(404, -1) }
        try {
            client().probe(url)
            fail("Expected the HTTP error")
        } catch (error: IOException) {
            assertEquals("HTTP 404", error.message)
        }
        assertEquals(1, requests.size)
    }

    @Test(timeout = 15_000)
    fun `an interrupted range is resumed from the bytes actually written`() = runBlocking {
        val interrupted = AtomicInteger()
        handler = { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && range != "bytes=0-0" && interrupted.getAndIncrement() == 0) {
                val positions = range.removePrefix("bytes=").split('-').map(String::toInt)
                val (start, end) = positions
                val length = end - start + 1
                etag?.let { exchange.responseHeaders.add("ETag", it) }
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(206, length.toLong())
                exchange.responseBody.write(payload, start, length / 2)
            } else {
                serveFile(exchange)
            }
        }
        val client = client()
        val result = withContext(Dispatchers.IO) {
            client.download(client.probe(url), temporaryFolder.newFolder(), { _, _ -> }, {})
        }

        assertArrayEquals(payload, contents(result))
        assertTrue(requests.size > 5)
    }

    @Test(timeout = 15_000)
    fun `a changed representation during transfer fails rather than mixing versions`() = runBlocking {
        val client = client()
        val remote = client.probe(url)
        etag = "\"changed\""
        try {
            client.download(remote, temporaryFolder.newFolder(), { _, _ -> }, {})
            fail("Expected changed remote file to be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("Remote file changed"))
        }
        assertFalse(requests.any { it.range == null })
    }

    @Test(timeout = 15_000)
    fun `cancelled clients cannot start new network requests`() = runBlocking {
        val client = client()
        client.cancel()
        try {
            client.probe(url)
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue(requests.isEmpty())
        }
    }

    @Test(timeout = 15_000)
    fun `probe retains final redirect metadata`() = runBlocking {
        handler = { exchange ->
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "/file.apk")
                exchange.sendResponseHeaders(302, -1)
            } else {
                serveFile(exchange)
            }
        }
        val remote = client().probe(url.replace("/file.apk", "/redirect"))

        assertTrue(remote.url.endsWith("/file.apk"))
        assertEquals("attachment; filename=Player.apk", remote.contentDisposition)
    }

    @Test
    fun `Content-Range rejects reversed out of bounds unknown and overflowing ranges`() {
        assertEquals(ResponseRange(0L, 9L, 10L), parseResponseRange("bytes 0-9/10"))
        listOf("bytes 9-0/10", "bytes 0-10/10", "bytes 0-9/*", "bytes */10", "bytes 0-999999999999999999999/10")
            .forEach { assertNull(parseResponseRange(it)) }
    }

    private fun client() = DownloadHttpClient(requestedParts = 4, minimumPartSize = 1L, retryDelayMillis = 0L)

    private fun contents(result: DownloadedParts): ByteArray = result.files.fold(byteArrayOf()) { bytes, file -> bytes + file.readBytes() }

    private fun serveFile(exchange: HttpExchange, ranges: Boolean = true) {
        etag?.let { exchange.responseHeaders.add("ETag", it) }
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.responseHeaders.add("Content-Disposition", "attachment; filename=Player.apk")
        val request = exchange.requestHeaders.getFirst("Range")
        if (ranges && request != null) {
            val positions = request.removePrefix("bytes=").split('-').map(String::toInt)
            val (start, end) = positions
            val length = end - start + 1
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            exchange.sendResponseHeaders(206, length.toLong())
            exchange.responseBody.write(payload, start, length)
        } else {
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
        }
    }

    private data class Request(val range: String?, val validator: String?)
}
