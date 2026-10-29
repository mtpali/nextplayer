package dev.anilbeesetti.nextplayer.feature.network.download

import android.webkit.MimeTypeMap
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

internal data class DownloadFileInfo(val name: String, val mimeType: String?)

/** Preserve real extensions even when a server labels every download as octet-stream. */
internal fun resolveDownloadFileInfo(
    sourceUrl: String,
    resolvedUrl: String = sourceUrl,
    contentDisposition: String? = null,
    mimeType: String? = null,
    suggestedName: String? = null,
    mimeTypeHint: String? = null,
): DownloadFileInfo {
    val sourceName = urlFileName(sourceUrl)
    val resolvedName = urlFileName(resolvedUrl)
    val headerName = contentDispositionFileName(contentDisposition)
    val type = normalizeMimeType(mimeType)?.takeUnless(::isGenericMimeType)
        ?: normalizeMimeType(mimeTypeHint)?.takeUnless(::isGenericMimeType)
    val name = headerName?.takeIf(::hasUsefulExtension)
        ?: suggestedName?.takeIf(::hasUsefulExtension)
        ?: resolvedName?.takeIf(::hasUsefulExtension)
        ?: sourceName?.takeIf(::hasUsefulExtension)
        ?: queryFileName(resolvedUrl)
        ?: queryFileName(sourceUrl)
        ?: headerName
        ?: suggestedName?.takeIf { it.isNotBlank() }
        ?: resolvedName
        ?: sourceName
        ?: "download"
    val safeName = sanitizeFileName(name)
    val extension = fileExtension(safeName)
    val inferredExtension = when (type) {
        APK_MIME_TYPE -> "apk"
        else -> type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    }
    val finalName = when {
        inferredExtension.isNullOrBlank() -> safeName
        extension.isBlank() -> "$safeName.$inferredExtension"
        extension in GENERIC_EXTENSIONS -> "${safeName.substringBeforeLast('.')}.$inferredExtension"
        else -> safeName
    }
    val fileType = mimeTypeForFileName(finalName)
    return DownloadFileInfo(
        name = limitFileName(finalName),
        mimeType = fileType ?: type ?: normalizeMimeType(mimeType),
    )
}

internal fun mimeTypeForFileName(name: String): String? = when (val extension = fileExtension(name)) {
    "apk" -> APK_MIME_TYPE
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

internal fun contentDispositionFileName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    // Split parameters without treating semicolons inside quoted names as separators.
    val parameters = mutableListOf<String>()
    val value = StringBuilder()
    var quoted = false
    var escaped = false
    header.forEach { character ->
        when {
            escaped -> {
                value.append(character)
                escaped = false
            }
            quoted && character == '\\' -> {
                value.append(character)
                escaped = true
            }
            character == '"' -> {
                quoted = !quoted
                value.append(character)
            }
            character == ';' && !quoted -> {
                parameters += value.toString()
                value.setLength(0)
            }
            else -> value.append(character)
        }
    }
    parameters += value.toString()
    val names = parameters.drop(1).mapNotNull { parameter ->
        val separator = parameter.indexOf('=')
        if (separator < 0) return@mapNotNull null
        val key = parameter.substring(0, separator).trim().lowercase(Locale.ROOT)
        val rawValue = parameter.substring(separator + 1).trim()
        val unquoted = if (rawValue.startsWith('"') && rawValue.endsWith('"') && rawValue.length >= 2) {
            rawValue.substring(1, rawValue.lastIndex).replace(Regex("\\\\(.)"), "$1")
        } else {
            rawValue
        }
        key to unquoted
    }
    val extendedName = names.firstOrNull { it.first == "filename*" }?.second?.let { encoded ->
        val fields = encoded.split('\'', limit = 3)
        if (fields.size != 3) return@let null
        runCatching {
            decodePercent(fields[2], Charset.forName(fields[0]))
        }.getOrNull()
    }
    return extendedName?.takeIf { it.isNotBlank() }
        ?: names.firstOrNull { it.first == "filename" }?.second?.takeIf { it.isNotBlank() }
}

internal fun sanitizeFileName(name: String): String = name
    .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
    .trim().trimEnd('.')
    .ifBlank { "download" }

private fun limitFileName(name: String): String {
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..20 }
    val suffix = extension?.let { ".$it" }.orEmpty()
    var base = if (suffix.isEmpty()) name else name.removeSuffix(suffix)
    while ((base + suffix).toByteArray(Charsets.UTF_8).size > 240 && base.isNotEmpty()) {
        base = base.substring(0, base.offsetByCodePoints(base.length, -1))
    }
    return base.ifBlank { "download" } + suffix
}

private fun urlFileName(url: String): String? = runCatching {
    URI(url).rawPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let(::decodePercent)
}.getOrNull()

private fun queryFileName(url: String): String? = runCatching {
    URI(url).rawQuery?.split('&')?.firstNotNullOfOrNull { parameter ->
        val key = parameter.substringBefore('=').lowercase(Locale.ROOT)
        if (key !in setOf("filename", "file", "name", "download")) return@firstNotNullOfOrNull null
        val value = URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
        value.takeIf(::hasUsefulExtension)
    }
}.getOrNull()

private fun decodePercent(value: String, charset: Charset = Charsets.UTF_8): String =
    URLDecoder.decode(value.replace("+", "%2B"), charset.name())

private fun fileExtension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

private fun hasUsefulExtension(name: String): Boolean =
    fileExtension(name).let { it.isNotBlank() && it !in GENERIC_EXTENSIONS && it.length <= 20 }

private fun normalizeMimeType(value: String?): String? =
    value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { '/' in it && '*' !in it }

private fun isGenericMimeType(type: String): Boolean = type in setOf(
    "application/octet-stream", "binary/octet-stream", "application/binary", "application/download", "application/x-download",
)

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private val GENERIC_EXTENSIONS = setOf("bin", "php", "asp", "aspx", "jsp", "cgi")
