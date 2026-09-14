package dev.anilbeesetti.nextplayer.feature.player

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal sealed interface ScreenshotResult {
    data class Saved(val uri: Uri) : ScreenshotResult
    data object Unsupported : ScreenshotResult
    data object Failed : ScreenshotResult
}

internal suspend fun captureVideoScreenshot(
    context: Context,
    videoBounds: Rect,
): ScreenshotResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ScreenshotResult.Unsupported
    val activity = context.findActivity() ?: return ScreenshotResult.Failed
    if (videoBounds.width() <= 0 || videoBounds.height() <= 0) return ScreenshotResult.Failed

    val bitmap = Bitmap.createBitmap(
        videoBounds.width(),
        videoBounds.height(),
        Bitmap.Config.ARGB_8888,
    )
    val copyResult = suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            activity.window,
            videoBounds,
            bitmap,
            { result ->
                if (continuation.isActive) continuation.resume(result)
            },
            Handler(Looper.getMainLooper()),
        )
    }
    if (copyResult != PixelCopy.SUCCESS) {
        bitmap.recycle()
        return ScreenshotResult.Failed
    }

    val savedUri = withContext(Dispatchers.IO) { saveScreenshot(context, bitmap) }
    bitmap.recycle()
    return savedUri?.let(ScreenshotResult::Saved) ?: ScreenshotResult.Failed
}

private fun saveScreenshot(context: Context, bitmap: Bitmap): Uri? {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
    val fileName = "Player_$timestamp.png"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Player")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } ?: error("Unable to open screenshot destination")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    } else {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
        val directory = File(root, "Player").apply { mkdirs() }
        val file = File(directory, fileName)
        try {
            FileOutputStream(file).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/png"),
                null,
            )
            Uri.fromFile(file)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
