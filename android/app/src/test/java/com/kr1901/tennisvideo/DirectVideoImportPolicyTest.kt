package com.kr1901.tennisvideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectVideoImportPolicyTest {
    @Test
    fun acceptsDirectHttpsVideoFileUrls() {
        assertNotNull(
            DirectVideoImportPolicy.parseAllowedHttpsUrl(
                "https://cdn.example.com/match.mp4?signature=abc"
            )
        )
    }

    @Test
    fun rejectsPlainHttpAndNonWebSchemes() {
        assertNull(DirectVideoImportPolicy.parseAllowedHttpsUrl("http://cdn.example.com/match.mp4"))
        assertNull(DirectVideoImportPolicy.parseAllowedHttpsUrl("file:///sdcard/match.mp4"))
    }

    @Test
    fun rejectsCredentialBearingUrlsAndStreamingPlaylists() {
        assertNull(DirectVideoImportPolicy.parseAllowedHttpsUrl("https://user:pass@cdn.example.com/match.mp4"))
        assertNull(DirectVideoImportPolicy.parseAllowedHttpsUrl("https://cdn.example.com/live.m3u8"))
        assertNull(DirectVideoImportPolicy.parseAllowedHttpsUrl("https://cdn.example.com/manifest.mpd"))
    }

    @Test
    fun acceptsVideoAndGenericBinaryContentTypesOnly() {
        assertTrue(DirectVideoImportPolicy.isDirectVideoContentType("video/mp4"))
        assertTrue(DirectVideoImportPolicy.isDirectVideoContentType("application/octet-stream"))
        assertFalse(DirectVideoImportPolicy.isDirectVideoContentType("text/html"))
        assertFalse(DirectVideoImportPolicy.isDirectVideoContentType("application/vnd.apple.mpegurl"))
        assertFalse(DirectVideoImportPolicy.isDirectVideoContentType(null))
    }
}
