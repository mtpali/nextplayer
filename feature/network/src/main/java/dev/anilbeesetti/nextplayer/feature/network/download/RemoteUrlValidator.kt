package dev.anilbeesetti.nextplayer.feature.network.download

import java.net.URI

internal fun isValidPlayableUrl(rawUrl: String): Boolean =
    isValidRemoteUrl(rawUrl, setOf("http", "https", "rtsp"))

internal fun isValidHttpUrl(rawUrl: String): Boolean =
    isValidRemoteUrl(rawUrl, setOf("http", "https"))

private fun isValidRemoteUrl(rawUrl: String, allowedSchemes: Set<String>): Boolean = runCatching {
    val uri = URI(rawUrl.trim())
    uri.scheme?.lowercase() in allowedSchemes && !uri.host.isNullOrBlank()
}.getOrDefault(false)
