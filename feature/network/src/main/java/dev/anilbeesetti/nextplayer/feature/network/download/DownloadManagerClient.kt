package dev.anilbeesetti.nextplayer.feature.network.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.net.toUri
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ManagedDownload(
    val id: Long,
    val title: String,
    val source: String,
    val localUri: Uri?,
    val mimeType: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val threadCount: Int,
    val status: DownloadStatus,
    val reason: Int,
    val updatedAt: Long,
) {
    val progress: Float?
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    val remainingBytes: Long?
        get() = totalBytes.takeIf { it > 0L }?.let { (it - downloadedBytes).coerceAtLeast(0L) }
}

@Serializable
enum class DownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
}

@Serializable
internal data class PersistedDownload(
    val id: Long,
    val title: String,
    val source: String,
    val localUri: String? = null,
    val mimeType: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val threadCount: Int = SegmentedDownloadService.DEFAULT_THREAD_COUNT,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val reason: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toManagedDownload() = ManagedDownload(
        id = id,
        title = title,
        source = source,
        localUri = localUri?.toUri(),
        mimeType = mimeType,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        threadCount = threadCount,
        status = status,
        reason = reason,
        updatedAt = updatedAt,
    )
}

internal class DownloadStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun create(url: String): PersistedDownload = synchronized(lock) {
        val records = readRecords().toMutableList()
        var id = System.currentTimeMillis()
        while (records.any { it.id == id }) id++
        val record = PersistedDownload(
            id = id,
            title = URLUtil.guessFileName(url, null, guessMimeType(url)),
            source = url,
        )
        records += record
        writeRecords(records)
        record
    }

    fun list(): List<PersistedDownload> = synchronized(lock) {
        readRecords().sortedByDescending(PersistedDownload::updatedAt)
    }

    fun get(id: Long): PersistedDownload? = synchronized(lock) {
        readRecords().firstOrNull { it.id == id }
    }

    fun update(id: Long, transform: (PersistedDownload) -> PersistedDownload): PersistedDownload? = synchronized(lock) {
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = transform(records[index]).copy(updatedAt = System.currentTimeMillis())
        records[index] = updated
        writeRecords(records)
        updated
    }

    fun remove(id: Long): PersistedDownload? = synchronized(lock) {
        val records = readRecords().toMutableList()
        val removed = records.firstOrNull { it.id == id } ?: return@synchronized null
        records.removeAll { it.id == id }
        writeRecords(records)
        removed
    }

    private fun readRecords(): List<PersistedDownload> {
        val value = preferences.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(
                deserializer = ListSerializer(PersistedDownload.serializer()),
                string = value,
            )
        }.getOrDefault(emptyList())
    }

    private fun writeRecords(records: List<PersistedDownload>) {
        val value = json.encodeToString(
            serializer = ListSerializer(PersistedDownload.serializer()),
            value = records,
        )
        preferences.edit().putString(KEY_DOWNLOADS, value).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "segmented_downloads"
        const val KEY_DOWNLOADS = "downloads"
        val lock = Any()
    }
}

class DownloadManagerClient(context: Context) {
    private val appContext = context.applicationContext
    private val store = DownloadStore(appContext)

    init {
        store.list()
            .filter {
                it.status == DownloadStatus.PENDING ||
                    it.status == DownloadStatus.RUNNING ||
                    it.status == DownloadStatus.PAUSED
            }
            .forEach { SegmentedDownloadService.start(appContext, it.id) }
    }

    fun enqueue(rawUrl: String): Result<Long> = runCatching {
        require(isValidHttpUrl(rawUrl)) { "Invalid download URL" }
        val record = store.create(rawUrl.trim())
        SegmentedDownloadService.start(appContext, record.id)
        record.id
    }

    fun query(): List<ManagedDownload> = store.list().map(PersistedDownload::toManagedDownload)

    fun retry(id: Long): Boolean {
        val record = store.update(id) {
            it.copy(status = DownloadStatus.PENDING, reason = 0, bytesPerSecond = 0L, localUri = null)
        } ?: return false
        SegmentedDownloadService.start(appContext, record.id)
        return true
    }

    fun remove(id: Long): Boolean {
        SegmentedDownloadService.cancel(appContext, id)
        val record = store.remove(id) ?: return false
        record.localUri?.toUri()?.let { uri ->
            runCatching {
                if (uri.scheme == "content") appContext.contentResolver.delete(uri, null, null)
            }
        }
        downloadPartsDirectory(appContext, id).deleteRecursively()
        return true
    }

    fun open(id: Long): Boolean {
        val record = store.get(id) ?: return false
        val uri = record.localUri?.toUri() ?: return false
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, record.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (viewIntent.resolveActivity(appContext.packageManager) == null) return false
        appContext.startActivity(
            Intent.createChooser(viewIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

internal fun downloadPartsDirectory(context: Context, id: Long): File =
    File(context.filesDir, "segmented-downloads/$id")

internal fun guessMimeType(url: String): String? {
    val extension = MimeTypeMap.getFileExtensionFromUrl(url).lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}
