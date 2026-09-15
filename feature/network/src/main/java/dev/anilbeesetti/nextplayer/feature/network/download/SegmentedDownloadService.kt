package dev.anilbeesetti.nextplayer.feature.network.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.anilbeesetti.nextplayer.core.ui.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

internal fun splitIntoRanges(
    totalBytes: Long,
    requestedParts: Int = SegmentedDownloadService.DEFAULT_THREAD_COUNT,
): List<ByteRange> {
    require(totalBytes > 0L)
    require(requestedParts > 0)
    val partCount = minOf(totalBytes, requestedParts.toLong()).toInt()
    val baseLength = totalBytes / partCount
    return List(partCount) { index ->
        val start = index * baseLength
        val end = if (index == partCount - 1) totalBytes - 1L else start + baseLength - 1L
        ByteRange(start = start, endInclusive = end)
    }
}

class SegmentedDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val cancelledDownloads = ConcurrentHashMap.newKeySet<Long>()
    private val notificationLock = Any()
    private lateinit var store: DownloadStore
    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        store = DownloadStore(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        val id = intent?.getLongExtra(EXTRA_DOWNLOAD_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> if (id >= 0L) startDownload(id)
            ACTION_CANCEL -> if (id >= 0L) cancelDownload(id)
        }
        if (jobs.isEmpty() && intent?.action == ACTION_CANCEL) stopWhenIdle()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        jobs.keys.forEach { id ->
            store.update(id) { record ->
                if (record.status == DownloadStatus.RUNNING) {
                    record.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0L)
                } else {
                    record
                }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(id: Long) {
        if (jobs[id]?.isActive == true) return
        val record = store.get(id) ?: return
        if (record.status == DownloadStatus.SUCCESSFUL) return
        cancelledDownloads.remove(id)
        jobs[id] = serviceScope.launch {
            try {
                download(record)
            } catch (_: CancellationException) {
                store.update(id) { current ->
                    current.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0L)
                }
            } catch (error: Exception) {
                if (cancelledDownloads.contains(id)) {
                    store.update(id) { current ->
                        current.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0L)
                    }
                    return@launch
                }
                val failed = store.update(id) { current ->
                    current.copy(
                        status = DownloadStatus.FAILED,
                        bytesPerSecond = 0L,
                        reason = error.message.orEmpty().hashCode(),
                    )
                }
                failed?.let(::showNotification)
            } finally {
                jobs.remove(id)
                if (jobs.isEmpty()) stopWhenIdle()
            }
        }
    }

    private fun cancelDownload(id: Long) {
        synchronized(notificationLock) {
            cancelledDownloads.add(id)
            notificationManager.cancel(notificationId(id))
        }
        jobs.remove(id)?.cancel()
        store.update(id) { record ->
            record.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0L)
        }
    }

    private suspend fun download(initialRecord: PersistedDownload) {
        val response = probe(initialRecord.source)
        val safeTitle = sanitizeFileName(
            URLUtil.guessFileName(initialRecord.source, response.contentDisposition, response.mimeType),
        )
        val ranges = if (response.supportsRanges && response.totalBytes > 0L) {
            splitIntoRanges(response.totalBytes)
        } else {
            emptyList()
        }
        var record = store.update(initialRecord.id) {
            it.copy(
                title = safeTitle,
                mimeType = response.mimeType ?: guessMimeType(initialRecord.source),
                totalBytes = response.totalBytes,
                threadCount = ranges.size.takeIf { count -> count > 0 } ?: 1,
                status = DownloadStatus.RUNNING,
                reason = 0,
            )
        } ?: return
        showNotification(record)

        val directory = downloadPartsDirectory(this, record.id).apply { mkdirs() }
        val progress = AtomicLong(0L)

        if (ranges.isNotEmpty()) {
            val partFiles = ranges.indices.map { File(directory, "part-$it") }
            progress.set(
                partFiles.zip(ranges).sumOf { (file, range) ->
                    file.length().coerceAtMost(range.length)
                },
            )
            reportProgress(record.id, progress.get(), response.totalBytes, 0L)
            val reporter = ProgressReporter(record.id, progress)
            coroutineScope {
                ranges.mapIndexed { index, range ->
                    async {
                        downloadRange(
                            url = record.source,
                            range = range,
                            destination = partFiles[index],
                            progress = progress,
                            reporter = reporter,
                        )
                    }
                }.awaitAll()
            }
            record = store.get(record.id) ?: return
            val localUri = mergeParts(record, partFiles)
            complete(record.id, localUri)
        } else {
            val partFile = File(directory, "part-0")
            partFile.delete()
            val reporter = ProgressReporter(record.id, progress)
            downloadSingle(
                url = record.source,
                destination = partFile,
                progress = progress,
                reporter = reporter,
            )
            record = store.get(record.id) ?: return
            val localUri = mergeParts(record, listOf(partFile))
            complete(record.id, localUri)
        }
    }

    private fun probe(url: String): ProbeResponse {
        val connection = openConnection(url).apply {
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val contentRange = connection.getHeaderField("Content-Range")
            val rangeTotal = contentRange?.substringAfterLast('/')?.toLongOrNull()
            val totalBytes = when {
                code == HttpURLConnection.HTTP_PARTIAL && rangeTotal != null -> rangeTotal
                connection.contentLengthLong > 0L -> connection.contentLengthLong
                else -> -1L
            }
            runCatching { connection.inputStream.close() }
            ProbeResponse(
                totalBytes = totalBytes,
                supportsRanges = code == HttpURLConnection.HTTP_PARTIAL && totalBytes > 0L,
                mimeType = connection.contentType?.substringBefore(';'),
                contentDisposition = connection.getHeaderField("Content-Disposition"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadRange(
        url: String,
        range: ByteRange,
        destination: File,
        progress: AtomicLong,
        reporter: ProgressReporter,
    ) {
        var existing = destination.length()
        if (existing > range.length) {
            destination.delete()
            existing = 0L
        }
        if (existing == range.length) return
        val start = range.start + existing
        val connection = openConnection(url).apply {
            setRequestProperty("Range", "bytes=" + start + "-" + range.endInclusive)
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Server stopped supporting ranged downloads"
            }
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                FileOutputStream(destination, true).buffered(BUFFER_SIZE).use { output ->
                    copyWithProgress(input, output, progress, reporter)
                }
            }
            require(destination.length() == range.length) { "Incomplete download segment" }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadSingle(
        url: String,
        destination: File,
        progress: AtomicLong,
        reporter: ProgressReporter,
    ) {
        val connection = openConnection(url)
        try {
            require(connection.responseCode in 200..299) { "HTTP " + connection.responseCode }
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                FileOutputStream(destination, false).buffered(BUFFER_SIZE).use { output ->
                    copyWithProgress(input, output, progress, reporter)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        progress: AtomicLong,
        reporter: ProgressReporter,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            reporter.report(progress.addAndGet(count.toLong()))
        }
    }

    private fun mergeParts(record: PersistedDownload, parts: List<File>): Uri {
        val mimeType = record.mimeType ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, record.title)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Player")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create download")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    parts.forEach { part ->
                        part.inputStream().buffered(BUFFER_SIZE).use { input ->
                            input.copyTo(output, BUFFER_SIZE)
                        }
                    }
                } ?: error("Unable to open download destination")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            val publicDirectory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Player",
            ).apply { mkdirs() }
            val outputFile = uniqueFile(publicDirectory, record.title)
            runCatching {
                FileOutputStream(outputFile).buffered(BUFFER_SIZE).use { output ->
                    parts.forEach { part ->
                        part.inputStream().buffered(BUFFER_SIZE).use { input ->
                            input.copyTo(output, BUFFER_SIZE)
                        }
                    }
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
            }.getOrElse {
                val fallbackDirectory = File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
                    "Player",
                ).apply { mkdirs() }
                val fallbackFile = uniqueFile(fallbackDirectory, record.title)
                FileOutputStream(fallbackFile).buffered(BUFFER_SIZE).use { output ->
                    parts.forEach { part ->
                        part.inputStream().buffered(BUFFER_SIZE).use { input ->
                            input.copyTo(output, BUFFER_SIZE)
                        }
                    }
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", fallbackFile)
            }
        }
    }

    private fun complete(id: Long, localUri: Uri) {
        if (cancelledDownloads.contains(id)) return
        downloadPartsDirectory(this, id).deleteRecursively()
        val complete = store.update(id) { record ->
            record.copy(
                localUri = localUri.toString(),
                downloadedBytes = record.totalBytes.takeIf { it > 0L } ?: record.downloadedBytes,
                bytesPerSecond = 0L,
                status = DownloadStatus.SUCCESSFUL,
            )
        }
        complete?.let(::showNotification)
    }

    private inner class ProgressReporter(
        private val id: Long,
        progress: AtomicLong,
    ) {
        private var lastBytes = progress.get()
        private var lastTime = System.currentTimeMillis()

        @Synchronized
        fun report(downloadedBytes: Long) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            if (elapsed < PROGRESS_UPDATE_INTERVAL_MS) return
            val speed = ((downloadedBytes - lastBytes).coerceAtLeast(0L) * 1000L) / elapsed.coerceAtLeast(1L)
            lastBytes = downloadedBytes
            lastTime = now
            reportProgress(id, downloadedBytes, store.get(id)?.totalBytes ?: -1L, speed)
        }
    }

    private fun reportProgress(id: Long, downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) {
        if (cancelledDownloads.contains(id)) return
        val updated = store.update(id) { record ->
            record.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
                status = DownloadStatus.RUNNING,
            )
        }
        updated?.let(::showNotification)
    }

    private fun showNotification(record: PersistedDownload) {
        val progress = if (record.totalBytes > 0L) {
            ((record.downloadedBytes * 1000L) / record.totalBytes).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        val text = when (record.status) {
            DownloadStatus.SUCCESSFUL -> getString(R.string.download_complete)
            DownloadStatus.FAILED -> getString(R.string.download_failed)
            DownloadStatus.PAUSED -> getString(R.string.download_paused)
            else -> buildString {
                if (record.bytesPerSecond > 0L) {
                    append(formatByteCount(record.bytesPerSecond))
                    append("/s")
                }
                if (record.totalBytes > 0L) {
                    if (isNotEmpty()) append(" · ")
                    append(formatByteCount(record.downloadedBytes))
                    append(" / ")
                    append(formatByteCount(record.totalBytes))
                }
            }.ifBlank { getString(R.string.download_running) }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(record.title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING)
            .setAutoCancel(record.status == DownloadStatus.SUCCESSFUL || record.status == DownloadStatus.FAILED)
        if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) {
            builder.setProgress(1000, progress, record.totalBytes <= 0L)
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                cancelPendingIntent(record.id),
            )
        }
        synchronized(notificationLock) {
            if (!cancelledDownloads.contains(record.id)) {
                notificationManager.notify(notificationId(record.id), builder.build())
            }
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(SUMMARY_NOTIFICATION_ID, summaryNotification())
        foregroundStarted = true
    }

    private fun stopWhenIdle() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun summaryNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.download_manager))
        .setContentText(getString(R.string.download_running))
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun cancelPendingIntent(id: Long): PendingIntent {
        val intent = Intent(this, SegmentedDownloadService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_DOWNLOAD_ID, id)
        return PendingIntent.getService(
            this,
            notificationId(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_manager),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun openConnection(rawUrl: String): HttpURLConnection =
        (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECTION_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    companion object {
        const val DEFAULT_THREAD_COUNT = 8
        private const val ACTION_START = "player.download.START"
        private const val ACTION_CANCEL = "player.download.CANCEL"
        private const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val CHANNEL_ID = "player_downloads"
        private const val SUMMARY_NOTIFICATION_ID = 4100
        private const val BUFFER_SIZE = 256 * 1024
        private const val CONNECTION_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val USER_AGENT = "Player/0.18 Android"

        fun start(context: Context, id: Long) {
            val intent = Intent(context, SegmentedDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DOWNLOAD_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, id: Long) {
            val intent = Intent(context, SegmentedDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_DOWNLOAD_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun notificationId(id: Long): Int = 5000 + ((id xor (id ushr 32)).toInt() and 0x0FFFFFFF)
    }
}

private data class ProbeResponse(
    val totalBytes: Long,
    val supportsRanges: Boolean,
    val mimeType: String?,
    val contentDisposition: String?,
)

internal fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }

private fun uniqueFile(directory: File, name: String): File {
    val direct = File(directory, name)
    if (!direct.exists()) return direct
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    val base = if (extension.isBlank()) name else name.removeSuffix(".$extension")
    var index = 1
    while (true) {
        val candidateName = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
        val candidate = File(directory, candidateName)
        if (!candidate.exists()) return candidate
        index++
    }
}

internal fun formatByteCount(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (value >= 100.0) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}
