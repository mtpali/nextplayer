package dev.anilbeesetti.nextplayer.feature.network.download

internal const val DEFAULT_DOWNLOAD_CONNECTIONS = 8

internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

internal fun splitIntoRanges(
    totalBytes: Long,
    requestedParts: Int = DEFAULT_DOWNLOAD_CONNECTIONS,
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
