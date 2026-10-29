package dev.anilbeesetti.nextplayer.core.common

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.DataInputStream
import java.util.UUID

/** Copies a user-selected font into private storage so playback does not depend on the document provider. */
object SubtitleFontStorage {
    private const val FONT_DIRECTORY = "subtitle_fonts"
    private const val MAX_FONT_BYTES = 20 * 1024 * 1024
    private val validId = Regex("[0-9a-fA-F-]{36}\\.(ttf|otf)")

    data class ImportedFont(val id: String, val displayName: String)

    fun importFont(context: Context, uri: Uri): ImportedFont {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw IOException("Font has no filename")
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension == "ttf" || extension == "otf") { "Only TTF and OTF fonts are supported" }

        val directory = File(context.filesDir, FONT_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create font directory")
        val id = "${UUID.randomUUID()}.$extension"
        val temporary = File(directory, "$id.tmp")
        val destination = File(directory, id)
        try {
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read font")
            source.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_FONT_BYTES) throw IOException("Font is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val signature = DataInputStream(temporary.inputStream()).use { it.readInt() }
            require(signature in FONT_SIGNATURES) { "Invalid font file" }
            // Reject unreadable files before changing the active preference or deleting an earlier font.
            Typeface.createFromFile(temporary)
            if (!temporary.renameTo(destination)) throw IOException("Cannot save font")
            return ImportedFont(id, displayName)
        } finally {
            temporary.delete()
        }
    }

    fun load(context: Context, id: String?): Typeface? {
        if (id == null || !validId.matches(id)) return null
        return runCatching { Typeface.createFromFile(File(context.filesDir, "$FONT_DIRECTORY/$id")) }.getOrNull()
    }

    fun delete(context: Context, id: String?) {
        if (id != null && validId.matches(id)) File(context.filesDir, "$FONT_DIRECTORY/$id").delete()
    }

    private val FONT_SIGNATURES = setOf(
        0x00010000, // TrueType
        0x4F54544F, // OpenType (OTTO)
        0x74746366, // TrueType collection (ttcf)
        0x74727565, // Apple TrueType (true)
    )
}
