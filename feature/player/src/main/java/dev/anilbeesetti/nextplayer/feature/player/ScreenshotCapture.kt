package dev.anilbeesetti.nextplayer.feature.player

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
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

    val captureBounds = videoBounds.intersectedWith(
        windowWidth = activity.window.decorView.width,
        windowHeight = activity.window.decorView.height,
    )
        ?: return ScreenshotResult.Failed
    val bitmap = Bitmap.createBitmap(
        captureBounds.width(),
        captureBounds.height(),
        Bitmap.Config.ARGB_8888,
    )
    val copyResult = suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            activity.window,
            captureBounds,
            bitmap,
            { result ->
                if (continuation.isActive) continuation.resume(result)
            },
            Handler(Looper.getMainLooper()),
        )
    }
    if (copyResult != PixelCopy.SUCCESS || bitmap.isAlmostEntirelyBlack()) {
        val fallbackSucceeded = drawCompositedWindow(activity, captureBounds, bitmap)
        if (fallbackSucceeded && !bitmap.isAlmostEntirelyBlack()) {
            val savedUri = withContext(Dispatchers.IO) { saveScreenshot(context, bitmap) }
            bitmap.recycle()
            return savedUri?.let(ScreenshotResult::Saved) ?: ScreenshotResult.Failed
        }
        bitmap.recycle()
        return ScreenshotResult.Failed
    }

    val savedUri = withContext(Dispatchers.IO) { saveScreenshot(context, bitmap) }
    bitmap.recycle()
    return savedUri?.let(ScreenshotResult::Saved) ?: ScreenshotResult.Failed
}

private fun Rect.intersectedWith(windowWidth: Int, windowHeight: Int): Rect? {
    val result = Rect(this)
    if (!result.intersect(0, 0, windowWidth, windowHeight)) return null
    return result.takeIf { it.width() > 0 && it.height() > 0 }
}

private fun drawCompositedWindow(activity: Activity, bounds: Rect, bitmap: Bitmap): Boolean = runCatching {
    bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
    val canvas = Canvas(bitmap)
    canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
    activity.window.decorView.draw(canvas)
}.isSuccess

private fun Bitmap.isAlmostEntirelyBlack(): Boolean {
    val horizontalSamples = minOf(width, 16)
    val verticalSamples = minOf(height, 16)
    if (horizontalSamples <= 0 || verticalSamples <= 0) return true
    var blackSamples = 0
    var samples = 0
    repeat(verticalSamples) { row ->
        val y = ((row + 0.5f) * height / verticalSamples).toInt().coerceIn(0, height - 1)
        repeat(horizontalSamples) { column ->
            val x = ((column + 0.5f) * width / horizontalSamples).toInt().coerceIn(0, width - 1)
            val color = getPixel(x, y)
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            if (red <= BLACK_THRESHOLD && green <= BLACK_THRESHOLD && blue <= BLACK_THRESHOLD) blackSamples++
            samples++
        }
    }
    return blackSamples.toFloat() / samples >= BLACK_SAMPLE_RATIO
}

private const val BLACK_THRESHOLD = 8
private const val BLACK_SAMPLE_RATIO = 0.98f

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
