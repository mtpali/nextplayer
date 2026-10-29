package dev.anilbeesetti.nextplayer.feature.network.download

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class RemoteDownload(
    val url: String,
    val totalBytes: Long,
    val supportsRanges: Boolean,
    val mimeType: String?,
    val contentDisposition: String?,
    val etag: String?,
    val lastModified: String?,
) {
    val validator: String?
        get() = etag?.takeIf { it.startsWith('"') && it.endsWith('"') } ?: lastModified
}

internal data class DownloadedParts(val files: List<File>, val totalBytes: Long)

internal data class ResponseRange(val start: Long, val endInclusive: Long, val total: Long)

internal fun parseResponseRange(value: String?): ResponseRange? {
    val match = value?.let { Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE).matchEntire(it.trim()) }
        ?: return null
    val (start, end, total) = match.destructured
    val first = start.toLongOrNull() ?: return null
    val last = end.toLongOrNull() ?: return null
    val size = total.toLongOrNull() ?: return null
    return ResponseRange(first, last, size).takeIf { first <= last && last < size }
}

/** One client per download, so pause/remove can close its sockets immediately. */
internal class DownloadHttpClient(
    private val requestedParts: Int = DEFAULT_DOWNLOAD_CONNECTIONS,
    private val minimumPartSize: Long = 256 * 1024L,
    private val retryDelayMillis: Long = 1_000L,
) {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        connections.forEach { runCatching { it.disconnect() } }
    }

    suspend fun probe(url: String): RemoteDownload = retry {
        val connection = openConnection(url).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            val code = connection.responseCode
            // An empty resource can legitimately reject bytes=0-0.
            val empty = code == 416 && connection.getHeaderField("Content-Range")?.trim() == "bytes */0"
            if (!empty) checkHttpStatus(code)
            val range = parseResponseRange(connection.getHeaderField("Content-Range"))
            val supportsRanges = code == HttpURLConnection.HTTP_PARTIAL && range?.start == 0L && range.endInclusive == 0L
            val length = when {
                empty -> 0L
                code == HttpURLConnection.HTTP_PARTIAL -> range?.total ?: -1L
                else -> connection.contentLengthLong
            }
            RemoteDownload(
                url = connection.url.toString(),
                totalBytes = length,
                supportsRanges = supportsRanges,
                mimeType = connection.contentType?.substringBefore(';')?.trim(),
                contentDisposition = connection.getHeaderField("Content-Disposition"),
                etag = connection.getHeaderField("ETag"),
                lastModified = connection.getHeaderField("Last-Modified"),
            )
        } finally {
            close(connection)
        }
    }

    suspend fun download(
        remote: RemoteDownload,
        directory: File,
        onStarted: (parts: Int, downloadedBytes: Long) -> Unit,
        onProgress: (Long) -> Unit,
    ): DownloadedParts {
        checkActive()
        check(directory.exists() || directory.mkdirs()) { "Unable to create download directory" }
        val partCount = if (remote.supportsRanges && remote.totalBytes > 0L) {
            minOf(requestedParts.toLong(), (remote.totalBytes / minimumPartSize).coerceAtLeast(1L)).toInt()
        } else {
            1
        }
        val state = TransferState(remote.url, remote.totalBytes, remote.validator, partCount)
        val stateFile = File(directory, "transfer.properties")
        val oldState = readTransferState(stateFile)
        // Without a validator, old bytes could belong to a different version of the file.
        if (!remote.supportsRanges || state.validator == null || oldState != state) clearParts(directory)
        writeTransferState(stateFile, state)

        if (remote.supportsRanges && remote.totalBytes > 0L) {
            val ranges = splitIntoRanges(remote.totalBytes, partCount)
            val parts = ranges.indices.map { File(directory, "part-$it") }
            parts.zip(ranges).forEach { (file, range) -> if (file.length() > range.length) file.delete() }
            val progress = AtomicLong(parts.sumOf(File::length))
            onStarted(partCount, progress.get())
            try {
                coroutineScope {
                    ranges.mapIndexed { index, range ->
                        async { downloadRange(remote, range, parts[index], progress, onProgress) }
                    }.awaitAll()
                }
                return DownloadedParts(parts, remote.totalBytes)
            } catch (_: RangeResponseException) {
                // Some hosts advertise ranges, then ignore them on the actual requests.
                checkActive()
                clearParts(directory)
                stateFile.delete()
            }
        }

        val part = File(directory, "part-0")
        onStarted(1, 0L)
        downloadSingle(remote, part, onProgress)
        return DownloadedParts(listOf(part), part.length())
    }

    private suspend fun downloadRange(
        remote: RemoteDownload,
        range: ByteRange,
        destination: File,
        progress: AtomicLong,
        onProgress: (Long) -> Unit,
    ) {
        var counted = destination.length()
        retry {
            val existing = destination.length()
            onProgress(progress.addAndGet(existing - counted))
            counted = existing
            if (existing == range.length) return@retry
            val start = range.start + existing
            val connection = openConnection(remote.url).apply {
                setRequestProperty("Range", "bytes=$start-${range.endInclusive}")
                remote.validator?.let { setRequestProperty("If-Range", it) }
            }
            try {
                val code = connection.responseCode
                checkRemoteValidator(connection, remote)
                if (code == HttpURLConnection.HTTP_OK || code == 416) throw RangeResponseException()
                checkHttpStatus(code)
                val responseRange = parseResponseRange(connection.getHeaderField("Content-Range"))
                if (responseRange != null && responseRange.total != remote.totalBytes) throw RemoteFileChangedException()
                if (code != HttpURLConnection.HTTP_PARTIAL || responseRange?.start != start || responseRange.endInclusive != range.endInclusive) {
                    throw RangeResponseException()
                }
                val remaining = range.length - existing
                if (connection.contentLengthLong >= 0L && connection.contentLengthLong != remaining) throw RangeResponseException()
                checkContentEncoding(connection)
                connection.inputStream.use { input ->
                    FileOutputStream(destination, true).use { output ->
                        copyBody(input, output, remaining) { count ->
                            counted += count
                            onProgress(progress.addAndGet(count))
                        }
                    }
                }
            } finally {
                close(connection)
            }
        }
    }

    private suspend fun downloadSingle(remote: RemoteDownload, destination: File, onProgress: (Long) -> Unit) = retry {
        onProgress(0L)
        val connection = openConnection(remote.url)
        try {
            val code = connection.responseCode
            checkHttpStatus(code)
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_NO_CONTENT) throw InvalidResponseException()
            checkRemoteValidator(connection, remote)
            checkContentEncoding(connection)
            val length = connection.contentLengthLong
            if (remote.totalBytes >= 0L && length >= 0L && remote.totalBytes != length) throw RemoteFileChangedException()
            val expected = remote.totalBytes.takeIf { it >= 0L } ?: length.takeIf { it >= 0L }
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination, false).use { output ->
                    copyBody(input, output, expected) { count ->
                        downloaded += count
                        onProgress(downloaded)
                    }
                }
            }
        } finally {
            close(connection)
        }
    }

    private suspend fun copyBody(input: InputStream, output: OutputStream, expected: Long?, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (expected != null && count > expected - copied) throw InvalidResponseException()
            output.write(buffer, 0, count)
            copied += count
            onBytes(count.toLong())
        }
        if (expected != null && copied != expected) throw EOFException("Incomplete download")
    }

    private suspend fun <T> retry(block: suspend () -> T): T {
        repeat(3) { attempt ->
            checkActive()
            try {
                return block()
            } catch (error: IOException) {
                checkActive()
                if (attempt == 2 || error is InvalidResponseException || error is HttpStatusException && !error.retryable) throw error
                delay(retryDelayMillis * (1L shl attempt))
            }
        }
        error("Unreachable retry state")
    }

    private suspend fun checkActive() {
        coroutineContext.ensureActive()
        if (cancelled.get()) throw CancellationException("Download paused")
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Player/0.18 Android")
        }
        connections.add(connection)
        if (cancelled.get()) {
            close(connection)
            throw CancellationException("Download paused")
        }
        return connection
    }

    private fun close(connection: HttpURLConnection) {
        connections.remove(connection)
        connection.disconnect()
    }

    private fun checkRemoteValidator(connection: HttpURLConnection, remote: RemoteDownload) {
        val etag = connection.getHeaderField("ETag")
        val modified = connection.getHeaderField("Last-Modified")
        if (remote.etag != null && etag != null && remote.etag != etag ||
            remote.lastModified != null && modified != null && remote.lastModified != modified
        ) {
            throw RemoteFileChangedException()
        }
    }

    private fun checkContentEncoding(connection: HttpURLConnection) {
        if (connection.contentEncoding?.equals("identity", ignoreCase = true) == false) throw InvalidResponseException()
    }

    private fun checkHttpStatus(code: Int) {
        if (code !in 200..299) throw HttpStatusException(code)
    }

    private fun clearParts(directory: File) {
        directory.listFiles { file -> file.name.startsWith("part-") }?.forEach { file ->
            check(file.delete()) { "Unable to clear old download segment" }
        }
    }

    private fun readTransferState(file: File): TransferState? = runCatching {
        val values = Properties().apply { file.inputStream().use(::load) }
        TransferState(
            url = values.getProperty("url") ?: return@runCatching null,
            totalBytes = values.getProperty("total").toLong(),
            validator = values.getProperty("validator")?.takeIf { it.isNotEmpty() },
            partCount = values.getProperty("parts").toInt(),
        )
    }.getOrNull()

    private fun writeTransferState(file: File, state: TransferState) {
        val values = Properties().apply {
            setProperty("url", state.url)
            setProperty("total", state.totalBytes.toString())
            setProperty("validator", state.validator.orEmpty())
            setProperty("parts", state.partCount.toString())
        }
        file.outputStream().use { values.store(it, null) }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

private data class TransferState(val url: String, val totalBytes: Long, val validator: String?, val partCount: Int)

private open class InvalidResponseException(message: String = "Invalid download response") : IOException(message)
private class RangeResponseException : InvalidResponseException("Server did not return the requested byte range")
private class RemoteFileChangedException : InvalidResponseException("Remote file changed; retry the download")
private class HttpStatusException(code: Int) : IOException("HTTP $code") {
    val retryable = code == 408 || code == 429 || code in 500..599
}
