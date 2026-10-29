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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.anilbeesetti.nextplayer.core.ui.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SegmentedDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val cancelledDownloads = ConcurrentHashMap.newKeySet<Long>()
    private val notificationLock = Any()
    private val clients = ConcurrentHashMap<Long, DownloadHttpClient>()
    private val downloadSlots = Semaphore(3)
    private val mainHandler = Handler(Looper.getMainLooper())
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
            ACTION_PAUSE -> if (id >= 0L) cancelDownload(id, showPaused = true)
        }
        if (jobs.isEmpty()) stopWhenIdle()
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
        clients.values.forEach(DownloadHttpClient::cancel)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(id: Long) {
        val previous = jobs[id]
        if (previous?.isActive == true) return
        if (store.get(id)?.status == DownloadStatus.SUCCESSFUL) return
        val record = synchronized(notificationLock) {
            cancelledDownloads.remove(id)
            store.update(id) { it.copy(status = DownloadStatus.PENDING, bytesPerSecond = 0L) }
        } ?: return
        showNotification(record)
        val client = DownloadHttpClient()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                // A resumed transfer must not share part files with a still-closing worker.
                previous?.join()
                coroutineContext.ensureActive()
                if (store.get(id) == null) return@launch
                clients[id] = client
                downloadSlots.withPermit {
                    store.get(id)?.let { download(it, client) }
                }
            } catch (_: CancellationException) {
                store.update(id) { current ->
                    if (current.status == DownloadStatus.SUCCESSFUL) current else current.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0L)
                }
            } catch (error: Exception) {
                if (cancelledDownloads.contains(id)) return@launch
                val failed = store.update(id) { current ->
                    current.copy(
                        status = DownloadStatus.FAILED,
                        bytesPerSecond = 0L,
                        reason = error.message.orEmpty().hashCode(),
                    )
                }
                failed?.let(::showNotification)
            } finally {
                client.cancel()
                clients.remove(id, client)
                jobs.remove(id, coroutineContext.job)
                if (store.get(id) == null) downloadPartsDirectory(this@SegmentedDownloadService, id).deleteRecursively()
                stopWhenIdle()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun cancelDownload(id: Long, showPaused: Boolean = false) {
        val paused = synchronized(notificationLock) {
            if (store.get(id)?.status == DownloadStatus.SUCCESSFUL) return
            cancelledDownloads.add(id)
            notificationManager.cancel(notificationId(id))
            if (showPaused) {
                store.update(id) { record -> record.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0L) }
            } else {
                store.remove(id)
                null
            }
        }
        jobs[id]?.cancel()
        clients[id]?.cancel()
        if (!showPaused && jobs[id] == null) {
            downloadPartsDirectory(this, id).deleteRecursively()
        }
        paused?.let(::showNotification)
    }

    private suspend fun download(initialRecord: PersistedDownload, client: DownloadHttpClient) {
        val response = client.probe(initialRecord.source)
        val fileInfo = resolveDownloadFileInfo(
            sourceUrl = initialRecord.source,
            resolvedUrl = response.url,
            contentDisposition = response.contentDisposition,
            mimeType = response.mimeType,
            suggestedName = initialRecord.suggestedName,
            mimeTypeHint = initialRecord.mimeTypeHint,
        )
        store.update(initialRecord.id) {
            it.copy(title = fileInfo.name, mimeType = fileInfo.mimeType, totalBytes = response.totalBytes, reason = 0)
        } ?: return
        val directory = downloadPartsDirectory(this, initialRecord.id)
        val reporter = ProgressReporter(initialRecord.id)
        val result = client.download(
            remote = response,
            directory = directory,
            onStarted = { count, bytes ->
                reporter.reset(bytes)
                store.update(initialRecord.id) { it.copy(threadCount = count) }
                reportProgress(initialRecord.id, bytes, response.totalBytes, 0L)
            },
            onProgress = reporter::report,
        )
        coroutineContext.ensureActive()
        reportProgress(initialRecord.id, result.totalBytes, result.totalBytes, 0L)
        val record = store.get(initialRecord.id) ?: return
        val localUri = mergeParts(record, result.files)
        if (!complete(record.id, localUri)) {
            runCatching { contentResolver.delete(localUri, null, null) }
        }
    }

    private suspend fun mergeParts(record: PersistedDownload, parts: List<File>): Uri {
        val mimeType = record.mimeType ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, record.title)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create download")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    parts.forEach { part ->
                        part.inputStream().buffered(BUFFER_SIZE).use { input ->
                            copyToDestination(input, output)
                        }
                    }
                } ?: error("Unable to open download destination")
                coroutineContext.ensureActive()
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            val publicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .apply { mkdirs() }
            val outputFile = uniqueFile(publicDirectory, record.title)
            try {
                FileOutputStream(outputFile).buffered(BUFFER_SIZE).use { output ->
                    parts.forEach { part ->
                        part.inputStream().buffered(BUFFER_SIZE).use { input ->
                            copyToDestination(input, output)
                        }
                    }
                }
                coroutineContext.ensureActive()
                FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
            } catch (error: Exception) {
                outputFile.delete()
                throw error
            }
        }
    }

    private suspend fun copyToDestination(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun complete(id: Long, localUri: Uri): Boolean {
        val completed = synchronized(notificationLock) {
            if (cancelledDownloads.contains(id)) return false
            store.update(id) { record ->
                record.copy(
                    localUri = localUri.toString(),
                    downloadedBytes = record.totalBytes,
                    bytesPerSecond = 0L,
                    status = DownloadStatus.SUCCESSFUL,
                )
            }
        } ?: return false
        downloadPartsDirectory(this, id).deleteRecursively()
        showNotification(completed)
        return true
    }

    private inner class ProgressReporter(private val id: Long) {
        private var lastBytes = 0L
        private var lastTime = SystemClock.elapsedRealtime()

        @Synchronized
        fun reset(bytes: Long) {
            lastBytes = bytes
            lastTime = SystemClock.elapsedRealtime()
        }

        @Synchronized
        fun report(downloadedBytes: Long) {
            val now = SystemClock.elapsedRealtime()
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
        val notification = buildDownloadNotification(record)
        synchronized(notificationLock) {
            if (store.get(record.id) != null && (!cancelledDownloads.contains(record.id) || record.status == DownloadStatus.PAUSED)) {
                notificationManager.notify(notificationId(record.id), notification)
            }
        }
    }

    internal fun buildDownloadNotification(record: PersistedDownload): Notification {
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
                android.R.drawable.ic_media_pause,
                getString(R.string.pause_download),
                actionPendingIntent(record.id, ACTION_PAUSE),
            )
        }
        if (record.status == DownloadStatus.PAUSED) {
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.resume), actionPendingIntent(record.id, ACTION_START))
        }
        if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING || record.status == DownloadStatus.PAUSED) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel_download),
                actionPendingIntent(record.id, ACTION_CANCEL),
            )
        }
        return builder.build()
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(SUMMARY_NOTIFICATION_ID, summaryNotification())
        foregroundStarted = true
    }

    private fun stopWhenIdle() {
        mainHandler.post {
            if (jobs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
        }
    }

    private fun summaryNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.download_manager))
        .setContentText(getString(R.string.download_running))
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun actionPendingIntent(id: Long, action: String): PendingIntent {
        val intent = Intent(this, SegmentedDownloadService::class.java)
            .setAction(action)
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

    companion object {
        const val DEFAULT_THREAD_COUNT = DEFAULT_DOWNLOAD_CONNECTIONS
        private const val ACTION_START = "player.download.START"
        private const val ACTION_CANCEL = "player.download.CANCEL"
        private const val ACTION_PAUSE = "player.download.PAUSE"
        private const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val CHANNEL_ID = "player_downloads"
        private const val SUMMARY_NOTIFICATION_ID = 4100
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L

        fun start(context: Context, id: Long) {
            val intent = Intent(context, SegmentedDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DOWNLOAD_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, id: Long) {
            val intent = Intent(context, SegmentedDownloadService::class.java)
                .setAction(ACTION_PAUSE)
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

internal fun uniqueFile(directory: File, name: String): File {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    val base = if (extension.isBlank()) name else name.removeSuffix(".$extension")
    var index = 0
    while (true) {
        val candidateName = when {
            index == 0 -> name
            extension.isBlank() -> "$base ($index)"
            else -> "$base ($index).$extension"
        }
        val candidate = File(directory, candidateName)
        if (candidate.createNewFile()) return candidate
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
