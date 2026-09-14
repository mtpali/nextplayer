package dev.anilbeesetti.nextplayer.feature.network.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.net.toUri

data class ManagedDownload(
    val id: Long,
    val title: String,
    val source: String,
    val localUri: Uri?,
    val mimeType: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
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
}

enum class DownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
}

class DownloadManagerClient(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

    fun enqueue(rawUrl: String): Result<Long> = runCatching {
        require(isValidHttpUrl(rawUrl)) { "Invalid download URL" }
        val url = rawUrl.trim()
        val uri = url.toUri()
        val fileName = URLUtil.guessFileName(url, null, guessMimeType(url))
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription(uri.host.orEmpty())
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Player/$fileName")
        guessMimeType(url)?.let(request::setMimeType)
        manager.enqueue(request)
    }

    fun query(): List<ManagedDownload> = runCatching {
        manager.query(DownloadManager.Query()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toManagedDownload())
                }
            }.sortedByDescending(ManagedDownload::updatedAt)
        }
    }.getOrDefault(emptyList())

    fun remove(id: Long): Boolean = manager.remove(id) > 0

    fun open(id: Long): Boolean {
        val uri = manager.getUriForDownloadedFile(id) ?: return false
        val mimeType = manager.getMimeTypeForDownloadedFile(id) ?: "*/*"
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (viewIntent.resolveActivity(appContext.packageManager) == null) return false
        appContext.startActivity(
            Intent.createChooser(viewIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private fun Cursor.toManagedDownload(): ManagedDownload {
        val statusValue = long(DownloadManager.COLUMN_STATUS).toInt()
        return ManagedDownload(
            id = long(DownloadManager.COLUMN_ID),
            title = string(DownloadManager.COLUMN_TITLE).orEmpty(),
            source = string(DownloadManager.COLUMN_URI).orEmpty(),
            localUri = string(DownloadManager.COLUMN_LOCAL_URI)?.toUri(),
            mimeType = string(DownloadManager.COLUMN_MEDIA_TYPE),
            downloadedBytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            totalBytes = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            status = when (statusValue) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESSFUL
                else -> DownloadStatus.FAILED
            },
            reason = long(DownloadManager.COLUMN_REASON).toInt(),
            updatedAt = long(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP),
        )
    }

    private fun Cursor.long(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}

private fun guessMimeType(url: String): String? {
    val extension = MimeTypeMap.getFileExtensionFromUrl(url).lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}
