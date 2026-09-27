package dev.anilbeesetti.nextplayer.core.data.repository

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.database.dao.HiddenVideoDao
import dev.anilbeesetti.nextplayer.core.database.entities.HiddenVideoEntity
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Restores files hidden by older app versions before the private media feature was removed. */
class LegacyHiddenVideoRecovery @Inject constructor(
    private val hiddenVideoDao: HiddenVideoDao,
    @ApplicationContext private val context: Context,
) {
    suspend fun restore() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) return@withContext

        hiddenVideoDao.getAll().first().forEach { entity ->
            try {
                if (restoreFile(entity)) hiddenVideoDao.deleteByIds(listOf(entity.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.logError("LegacyHiddenVideoRecovery", "Failed to restore ${entity.id}: $error")
            }
        }
    }

    private fun restoreFile(entity: HiddenVideoEntity): Boolean {
        val source = File(entity.vaultPath)
        if (!source.isFile) return false
        val resolver = context.contentResolver
        val destination = insertDestination(entity, source) ?: return false
        return try {
            resolver.openOutputStream(destination)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("Unable to open restored video")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                check(resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) > 0)
            }
            check(source.delete())
            true
        } catch (error: Exception) {
            runCatching { resolver.delete(destination, null, null) }
            false
        }
    }

    private fun insertDestination(entity: HiddenVideoEntity, source: File): Uri? {
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(source.extension.lowercase()) ?: "video/*"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entity.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.DATA, entity.originalPath)
            }
            return runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        }

        val storageRoot = Environment.getExternalStorageDirectory().path
        val parent = File(entity.originalPath).parent
        val originalFolder = parent?.takeIf { it.startsWith("$storageRoot/") }
            ?.removePrefix(storageRoot)?.trim('/')?.takeIf(String::isNotEmpty)?.let { "$it/" }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return listOfNotNull(originalFolder, "${Environment.DIRECTORY_MOVIES}/").distinct().firstNotNullOfOrNull { folder ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entity.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            runCatching { resolver.insert(collection, values) }.getOrNull()
        }
    }
}
