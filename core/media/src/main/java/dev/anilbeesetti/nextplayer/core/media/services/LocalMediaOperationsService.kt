package dev.anilbeesetti.nextplayer.core.media.services

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.common.extensions.scanPaths
import dev.anilbeesetti.nextplayer.core.common.extensions.updateMedia
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class LocalMediaOperationsService @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaOperationsService {

    private lateinit var activity: Activity
    private val contentResolver = context.contentResolver
    private val activityRequestMutex = Mutex()
    private var pendingActivityResult: CompletableDeferred<Boolean>? = null
    private var mediaRequestLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var writePermissionLauncher: ActivityResultLauncher<String>? = null

    override fun initialize(activity: ComponentActivity) {
        initialize(activity, activity.activityResultRegistry)
    }

    internal fun initialize(activity: ComponentActivity, registry: ActivityResultRegistry) {
        this.activity = activity
        mediaRequestLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
            registry,
        ) { result ->
            pendingActivityResult?.complete(result.resultCode == Activity.RESULT_OK)
        }
        writePermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
            registry,
        ) { granted -> pendingActivityResult?.complete(granted) }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (this@LocalMediaOperationsService.activity === activity) {
                    pendingActivityResult?.complete(false)
                    mediaRequestLauncher = null
                    writePermissionLauncher = null
                }
            }
        })
    }

    override suspend fun deleteMedia(uris: List<Uri>, permanently: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (permanently) deleteMediaR(uris) else trashMediaR(uris)
            } else {
                deleteMediaBelowR(uris)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Keep a cancelled request registered until its result arrives, so it cannot finish a later request. */
    private suspend fun awaitActivityResult(launch: () -> Unit): Boolean = withContext(Dispatchers.Main.immediate) {
        activityRequestMutex.withLock {
            val result = CompletableDeferred<Boolean>()
            pendingActivityResult = result
            try {
                launch()
                withContext(NonCancellable) { result.await() }
            } finally {
                pendingActivityResult = null
            }
        }
    }

    private suspend fun requestMediaPermission(intent: PendingIntent): Boolean = awaitActivityResult {
        checkNotNull(mediaRequestLauncher).launch(IntentSenderRequest.Builder(intent).build())
    }

    private suspend fun requestLegacyWritePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return awaitActivityResult {
            checkNotNull(writePermissionLauncher).launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override suspend fun restoreMedia(uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        return@withContext Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && trashMediaR(uris, trashed = false)
    }

    override suspend fun renameMedia(uri: Uri, to: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            renameMediaR(uri, to)
        } else {
            renameMediaBelowR(uri, to)
        }
    }

    override suspend fun shareMedia(uris: List<Uri>) {
        val intent = Intent.createChooser(
            Intent().apply {
                type = "video/*"
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            },
            null,
        )
        activity.startActivity(intent)
    }

    override suspend fun moveMedia(targets: Map<Uri, File>): Map<Uri, File?> {
        return moveMedia(targets, onMoveCommitted = null)
    }

    /**
     * The internal callback is a deterministic synchronization seam for commit-boundary tests.
     * Production calls use [moveMedia] and pay no callback cost.
     */
    internal suspend fun moveMedia(
        targets: Map<Uri, File>,
        onMoveCommitted: (suspend (Uri) -> Unit)?,
    ): Map<Uri, File?> {
        val uris = targets.keys.toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mediaStoreUris = uris.filter { it.authority == MediaStore.AUTHORITY }
            if (mediaStoreUris.isNotEmpty()) {
                val granted = try {
                    requestWriteAccessR(mediaStoreUris)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!granted) return targets.keys.associateWith { null }
            }
        }

        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) io@{
                val movedFiles = linkedMapOf<Uri, File?>()
                val entries = targets.entries.iterator()
                var moveCommitted = false
                while (entries.hasNext()) {
                    val (uri, destination) = entries.next()
                    val movedFile = try {
                        callerJob?.ensureActive()
                        val sourceFile = context.getPath(uri)?.let { File(it) }
                        if (sourceFile == null) {
                            null
                        } else {
                            destination.parentFile?.mkdirs()
                            moveFileWithoutOverwrite(sourceFile, destination) { callerJob?.ensureActive() }
                        }
                    } catch (e: CancellationException) {
                        if (!moveCommitted) throw e
                        movedFiles[uri] = null
                        while (entries.hasNext()) movedFiles[entries.next().key] = null
                        return@io movedFiles
                    } catch (e: Exception) {
                        null
                    }
                    movedFiles[uri] = movedFile
                    moveCommitted = moveCommitted || movedFile != null
                    if (movedFile != null) onMoveCommitted?.invoke(uri)
                }
                movedFiles
            }
        }
    }

    /** Copies into an exclusively-created destination before atomically committing source deletion. */
    internal fun moveFileWithoutOverwrite(
        source: File,
        destination: File,
        ensureActive: () -> Unit,
    ): File? {
        if (!source.exists() || !destination.createNewFile()) return null
        val expectedBytes = source.length()

        val copiedBytes = try {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    output.fd.sync()
                    ensureActive()
                    copied
                }
            }
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: Exception) {
            destination.delete()
            return null
        }

        // Cancellation before this point rolls back the destination. From here, the move is committed.
        if (copiedBytes != expectedBytes || !source.delete()) {
            destination.delete()
            return null
        }
        return destination
    }

    override fun transferMedia(
        uris: List<Uri>,
        folderUri: Uri,
        mode: TransferMode,
    ): Flow<TransferEvent> = flow {
        val destinationDir = folderUri.toTreeDocumentUri()

        // Moving a file into the folder it already lives in is a no-op, so keep those aside.
        val (sameFolder, sources) = if (mode == TransferMode.MOVE) {
            uris.partition { isInFolder(it, destinationDir) }
        } else {
            emptyList<Uri>() to uris
        }

        val sizes = sources.map { sizeOf(it) }
        val overallTotal = sizes.sum()

        val createdPaths = mutableListOf<String>()
        val movedSources = mutableListOf<Uri>()
        var overallCopied = 0L
        var succeeded = 0

        sources.forEachIndexed { index, source ->
            val name = context.getFilenameFromUri(source).takeIf { it.isNotBlank() }
            val currentTotal = sizes[index]

            fun progress(copied: Long) = TransferProgress(
                totalFiles = sources.size,
                currentIndex = index,
                currentName = name,
                currentBytesCopied = copied,
                currentBytesTotal = currentTotal,
                overallBytesCopied = overallCopied + copied,
                overallBytesTotal = overallTotal,
            )

            emit(TransferEvent.Progress(progress(0)))
            if (name == null) return@forEachIndexed

            val copied = copyToDocument(source, destinationDir, name, currentTotal) { copiedSoFar ->
                emit(TransferEvent.Progress(progress(copiedSoFar)))
            } ?: return@forEachIndexed

            succeeded++
            overallCopied += copied.bytes
            context.getPath(copied.uri)?.let { createdPaths.add(it) }
            if (mode == TransferMode.MOVE) movedSources.add(source)
        }

        if (createdPaths.isNotEmpty()) context.scanPaths(createdPaths)

        // The copies are already in place, so a failed deletion still counts as a move;
        // the originals are just left behind, which we report separately.
        val originalsNotDeleted = mode == TransferMode.MOVE &&
            movedSources.isNotEmpty() &&
            !deleteMedia(movedSources)

        emit(
            TransferEvent.Completed(
                TransferResult(
                    succeeded = succeeded,
                    failed = sources.size - succeeded,
                    sameFolderSkipped = sameFolder.size,
                    originalsNotDeleted = originalsNotDeleted,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    private fun isInFolder(uri: Uri, folderDocUri: Uri): Boolean {
        val folderPath = runCatching { context.getPath(folderDocUri) }.getOrNull() ?: return false
        val filePath = runCatching { context.getPath(uri) }.getOrNull() ?: return false
        return File(filePath).parent == folderPath
    }

    private data class CopyResult(val uri: Uri, val bytes: Long)

    private suspend fun copyToDocument(
        source: Uri,
        destinationDir: Uri,
        name: String,
        expectedSize: Long,
        onProgress: suspend (Long) -> Unit,
    ): CopyResult? {
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(File(name).extension.lowercase()) ?: "video/*"
        val destUri = runCatching {
            DocumentsContract.createDocument(contentResolver, destinationDir, mimeType, name)
        }.getOrNull() ?: return null

        return try {
            val bytes = contentResolver.openInputStream(source)?.use { input ->
                contentResolver.openOutputStream(destUri)?.use { output ->
                    copyStream(input, output, expectedSize, onProgress)
                } ?: error("Unable to open output stream for $destUri")
            } ?: error("Unable to open input stream for $source")
            CopyResult(destUri, bytes)
        } catch (e: CancellationException) {
            deleteDocumentQuietly(destUri)
            throw e
        } catch (e: Exception) {
            deleteDocumentQuietly(destUri)
            null
        }
    }

    private fun deleteDocumentQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    private fun Uri.toTreeDocumentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(
        this,
        DocumentsContract.getTreeDocumentId(this),
    )

    private fun sizeOf(uri: Uri): Long = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0) }
    }.getOrNull() ?: 0L

    private suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        expectedTotal: Long,
        onCopied: suspend (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val reportThreshold = maxOf(expectedTotal / 100, 64L * 1024)
        var copied = 0L
        var lastReported = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (copied - lastReported >= reportThreshold) {
                onCopied(copied)
                lastReported = copied
            }
        }
        output.flush()
        onCopied(copied)
        return copied
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestWriteAccessR(uris: List<Uri>): Boolean = runMediaWriteRequests(
        uris = uris,
        itemExists = ::mediaExists,
        request = ::requestWriteR,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestWriteR(uris: List<Uri>): Boolean =
        requestMediaPermission(MediaStore.createWriteRequest(contentResolver, uris))

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun deleteMediaR(uris: List<Uri>): Boolean = runMediaRequests(
        items = uris,
        itemExists = ::mediaExists,
        request = ::requestDeleteR,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun trashMediaR(uris: List<Uri>, trashed: Boolean = true): Boolean = runMediaRequests(
        items = uris,
        itemExists = ::mediaExists,
        request = { requestTrashR(it, trashed) },
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestTrashR(uris: List<Uri>, trashed: Boolean): Boolean =
        requestMediaPermission(MediaStore.createTrashRequest(contentResolver, uris, trashed))

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestDeleteR(uris: List<Uri>): Boolean =
        requestMediaPermission(MediaStore.createDeleteRequest(contentResolver, uris))

    private fun mediaExists(uri: Uri): Boolean {
        val cursor = checkNotNull(
            contentResolver.query(
                uri,
                arrayOf(BaseColumns._ID),
                null,
                null,
                null,
            ),
        ) { "Unable to check whether the video exists" }
        return cursor.use { it.moveToFirst() }
    }

    private suspend fun deleteMediaBelowR(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return true
        val legacyStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Environment.isExternalStorageLegacy()
        if (legacyStorage && !requestLegacyWritePermission()) return false

        return uris.distinct().map { uri ->
            val deleted = try {
                contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    if (!requestMediaPermission(e.userAction.actionIntent)) return false
                    contentResolver.delete(uri, null, null)
                } else {
                    throw e
                }
            }
            deleted > 0 || !mediaExists(uri)
        }.all { it }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun renameMediaR(uri: Uri, to: String): Boolean {
        if (!requestWriteR(listOf(uri))) return false
        return contentResolver.updateMedia(
            uri = uri,
            contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, to)
            },
        )
    }

    private suspend fun renameMediaBelowR(uri: Uri, to: String): Boolean {
        return runCatching {
            val oldFile = context.getPath(uri)?.let { File(it) } ?: throw Error()
            val newFile = File(oldFile.parentFile, to)
            oldFile.renameTo(newFile).also { success ->
                if (success) {
                    contentResolver.updateMedia(
                        uri = uri,
                        contentValues = ContentValues().apply {
                            put(MediaStore.Files.FileColumns.DISPLAY_NAME, to)
                            put(MediaStore.Files.FileColumns.TITLE, to)
                            put(MediaStore.Files.FileColumns.DATA, newFile.path)
                        },
                    )
                }
            }
        }.getOrNull() ?: false
    }
}
