package com.kr1901.tennisvideo

import java.net.URI
import java.util.Locale

internal object DirectVideoImportPolicy {
    fun parseAllowedHttpsUrl(value: String): URI? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        if (uri.path?.substringAfterLast('.', "")?.lowercase(Locale.US) in PLAYLIST_EXTENSIONS) {
            return null
        }
        return uri
    }

    fun isDirectVideoContentType(value: String?): Boolean {
        val type = value?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        if (type in PLAYLIST_MIME_TYPES) return false
        return type.startsWith("video/") ||
            type == "application/octet-stream" ||
            type == "binary/octet-stream"
    }

    private val PLAYLIST_EXTENSIONS = setOf("m3u8", "mpd")
    private val PLAYLIST_MIME_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "application/dash+xml"
    )
}
