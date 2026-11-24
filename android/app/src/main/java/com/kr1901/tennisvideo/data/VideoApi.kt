package com.kr1901.tennisvideo.data

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import com.kr1901.tennisvideo.BuildConfig
import java.util.UUID
import kotlin.math.min

class VideoApi(
    baseUrl: String,
    private val contentResolver: ContentResolver,
    private val apiToken: String = "",
    private val uploadPreferences: SharedPreferences? = null
) {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    private companion object {
        const val UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024
        const val MAX_CHUNK_RETRIES = 3
    }

    fun health(): JSONObject = requestJson("/health", "GET")

    fun uploadVideo(uri: Uri, onProgress: (Long, Long) -> Unit = {}): JSONObject {
        val fileName = displayName(uri)
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileSize = fileSize(uri)
        if (fileSize <= 0L) {
            throw IOException("Android could not determine the selected video's file size.")
        }

        val preferenceKey = "session_" + UUID.nameUUIDFromBytes(
            uri.toString().toByteArray(Charsets.UTF_8)
        ).toString()
        var uploadLength = fileSize

        fun createSession(): String {
            val session = postJson(
                "/api/v1/videos/uploads",
                JSONObject()
                    .put("filename", fileName)
                    .put("content_type", mimeType)
                    .put("size_bytes", fileSize)
            )
            val createdId = session.getString("upload_id")
            uploadLength = session.getLong("upload_length")
            uploadPreferences?.edit()?.putString(preferenceKey, createdId)?.apply()
            return createdId
        }

        var uploadId: String = uploadPreferences?.getString(preferenceKey, null) ?: createSession()
        var offset = try {
            val serverState = getUploadOffset(uploadId)
            uploadLength = serverState.second
            serverState.first
        } catch (error: IOException) {
            if (error.message?.contains("HTTP 404") == true) {
                uploadPreferences?.edit()?.remove(preferenceKey)?.apply()
                uploadId = createSession()
                0L
            } else {
                throw error
            }
        }

        if (uploadLength != fileSize) {
            runCatching { abortUpload(uploadId) }
            uploadPreferences?.edit()?.remove(preferenceKey)?.apply()
            uploadId = createSession()
            offset = 0L
        }
        if (offset !in 0..fileSize) {
            throw IOException("The server returned an invalid resumable upload offset.")
        }

        onProgress(offset, fileSize)
        contentResolver.openInputStream(uri)?.use { source ->
            skipFully(source, offset)
            while (offset < fileSize) {
                val chunkLength = min(UPLOAD_CHUNK_BYTES.toLong(), fileSize - offset).toInt()
                val chunk = ByteArray(chunkLength)
                readFully(source, chunk)

                var attempt = 0
                while (true) {
                    try {
                        val committedOffset = appendUploadChunk(uploadId, offset, chunk)
                        if (committedOffset != offset + chunkLength) {
                            throw IOException("The server confirmed an unexpected upload offset.")
                        }
                        offset = committedOffset
                        break
                    } catch (error: IOException) {
                        val confirmed = runCatching { getUploadOffset(uploadId).first }.getOrNull()
                        if (confirmed == offset + chunkLength) {
                            // The server committed the chunk but the response was lost.
                            offset = confirmed
                            break
                        }
                        if (confirmed != null && confirmed != offset) {
                            throw IOException("Upload offset changed unexpectedly; expected $offset, got $confirmed.")
                        }
                        val permanentClientError = listOf(400, 401, 403, 404, 413, 415, 422)
                            .any { error.message?.contains("HTTP $it") == true }
                        if (permanentClientError || attempt >= MAX_CHUNK_RETRIES) throw error
                        Thread.sleep(500L shl attempt)
                        attempt += 1
                    }
                }
                onProgress(offset, fileSize)
            }
        } ?: throw IOException("Android could not reopen the selected video.")

        val completed = postJson(
            "/api/v1/videos/uploads/$uploadId/complete",
            JSONObject()
        )
        uploadPreferences?.edit()?.remove(preferenceKey)?.apply()
        return completed
    }

    private fun fileSize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && !cursor.isNull(column)) {
                    val reportedSize = cursor.getLong(column)
                    if (reportedSize > 0L) return reportedSize
                }
            }
        }
        return contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    private fun getUploadOffset(uploadId: String): Pair<Long, Long> {
        val connection = openConnection("/api/v1/videos/uploads/$uploadId", "HEAD")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("Server returned HTTP $code: $body")
            }
            val offset = connection.getHeaderField("Upload-Offset")?.toLongOrNull()
                ?: throw IOException("The server did not return Upload-Offset.")
            val length = connection.getHeaderField("Upload-Length")?.toLongOrNull()
                ?: throw IOException("The server did not return Upload-Length.")
            return offset to length
        } finally {
            connection.disconnect()
        }
    }

    private fun appendUploadChunk(uploadId: String, offset: Long, chunk: ByteArray): Long {
        val connection = openConnection("/api/v1/videos/uploads/$uploadId", "PATCH")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(chunk.size)
        connection.setRequestProperty("Content-Type", "application/offset+octet-stream")
        connection.setRequestProperty("Upload-Offset", offset.toString())
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { output -> output.write(chunk) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("Server returned HTTP $code: $body")
            }
            return connection.getHeaderField("Upload-Offset")?.toLongOrNull()
                ?: throw IOException("The server did not confirm the committed upload offset.")
        } finally {
            connection.disconnect()
        }
    }

    private fun abortUpload(uploadId: String) {
        val connection = openConnection("/api/v1/videos/uploads/$uploadId", "DELETE")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Server returned HTTP $code while cancelling the upload.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun skipFully(input: java.io.InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw IOException("Selected video ended before the saved upload offset.")
                remaining -= 1
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("Selected video ended unexpectedly during upload.")
            offset += read
        }
    }

    fun inspectVideo(videoId: String): JSONObject =
        requestJson("/api/v1/videos/$videoId/inspect", "POST")

    fun getVideo(videoId: String): JSONObject =
        requestJson("/api/v1/videos/$videoId", "GET")

    fun deleteVideo(videoId: String) {
        val connection = openConnection("/api/v1/videos/$videoId", "DELETE")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("Server returned HTTP $code: $body")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun videoStreamUrl(videoId: String): String =
        "$baseUrl/api/v1/videos/$videoId/stream"

    fun videoStreamHeaders(): Map<String, String> =
        if (apiToken.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiToken")

    fun getFrame(videoId: String, timestampSeconds: Double): Bitmap {
        val timestamp = String.format(Locale.US, "%.3f", timestampSeconds)
        val connection = openConnection(
            "/api/v1/videos/$videoId/frame?timestamp_seconds=$timestamp",
            "GET"
        )
        connection.setRequestProperty("Accept", "image/jpeg")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                throw IOException("Server returned HTTP $code: ${body.toString(Charsets.UTF_8)}")
            }
            return BitmapFactory.decodeByteArray(body, 0, body.size)
                ?: throw IOException("The frame returned by the server was not a readable JPEG.")
        } finally {
            connection.disconnect()
        }
    }


    fun detectCourt(videoId: String, timestampSeconds: Double): JSONObject {
        val timestamp = String.format(Locale.US, "%.3f", timestampSeconds)
        return requestJson(
            "/api/v1/videos/$videoId/court-detection?timestamp_seconds=$timestamp",
            "POST"
        )
    }

    fun startAnalysis(videoId: String, calibrationId: String?): JSONObject {
        val payload = JSONObject()
            .put("sample_interval_seconds", 0.5)
            .put("max_samples", 32000)
            .put("enable_ball_tracking", true)
        if (!calibrationId.isNullOrBlank()) {
            payload.put("calibration_id", calibrationId)
        }
        return postJson("/api/v1/videos/$videoId/analyses", payload)
    }

    fun getAnalysis(analysisId: String): JSONObject =
        requestJson("/api/v1/analyses/$analysisId", "GET")

    fun getAnalysisEvents(analysisId: String): JSONObject =
        requestJson("/api/v1/analyses/$analysisId/events", "GET")

    fun reviewEvent(
        analysisId: String,
        eventId: String,
        eventType: String?,
        accepted: Boolean,
        note: String? = null
    ): JSONObject {
        val payload = JSONObject()
            .put("review_status", if (accepted) "accepted" else "rejected")
            .put("player_id", "unknown")
        if (eventType != null) payload.put("event_type", eventType)
        if (note != null) payload.put("note", note)
        return postJson("/api/v1/analyses/$analysisId/events/$eventId/review", payload)
    }


    fun createManualEvent(
        analysisId: String,
        timestampSeconds: Double,
        eventType: String,
        playerId: String,
        note: String,
        serveNumber: Int? = null,
        serveResult: String? = null,
        shotDepth: String? = null,
        shotDirection: String? = null
    ): JSONObject {
        val payload = JSONObject()
            .put("timestamp_seconds", timestampSeconds)
            .put("event_type", eventType)
            .put("player_id", playerId)
            .put("note", note)
        serveNumber?.let { payload.put("serve_number", it) }
        serveResult?.takeIf { it.isNotBlank() }?.let { payload.put("serve_result", it) }
        shotDepth?.takeIf { it.isNotBlank() }?.let { payload.put("shot_depth", it) }
        shotDirection?.takeIf { it.isNotBlank() }?.let { payload.put("shot_direction", it) }
        return postJson("/api/v1/analyses/$analysisId/events", payload)
    }

    fun getAnalysisReportSvg(analysisId: String): ByteArray =
        getReportBytes("/api/v1/analyses/$analysisId/report.svg", "image/svg+xml")

    fun getAnalysisReportPng(analysisId: String): ByteArray =
        getReportBytes("/api/v1/analyses/$analysisId/report.png", "image/png")

    fun getAnalysisReportPdf(analysisId: String): ByteArray =
        getReportBytes("/api/v1/analyses/$analysisId/report.pdf", "application/pdf")

    private fun getReportBytes(path: String, accept: String): ByteArray {
        val connection = openConnection(path, "GET")
        connection.setRequestProperty("Accept", accept)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(body.toString(Charsets.UTF_8)).optString("detail", body.toString(Charsets.UTF_8))
                }.getOrDefault(body.toString(Charsets.UTF_8))
                throw IOException("Server returned HTTP $code: $detail")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    fun createCalibration(
        videoId: String,
        timestampSeconds: Double,
        imagePoints: List<Pair<Float, Float>>
    ): JSONObject {
        val points = JSONArray()
        imagePoints.forEach { (x, y) ->
            points.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
        }
        val payload = JSONObject()
            .put("frame_timestamp_seconds", timestampSeconds)
            .put("court_area", "doubles")
            .put("image_points", points)
        return postJson("/api/v1/videos/$videoId/calibrations", payload)
    }

    fun projectPoint(
        videoId: String,
        calibrationId: String,
        imagePoint: Pair<Float, Float>,
        playerEnd: String
    ): JSONObject {
        val payload = JSONObject()
            .put("image_point", JSONArray().put(imagePoint.first.toDouble()).put(imagePoint.second.toDouble()))
            .put("player_end", playerEnd)
        return postJson(
            "/api/v1/videos/$videoId/calibrations/$calibrationId/project",
            payload
        )
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) return cursor.getString(column)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "match.mp4"
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val connection = openConnection(path, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            return readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJson(path: String, method: String): JSONObject {
        val connection = openConnection(path, method)
        connection.setRequestProperty("Accept", "application/json")
        try {
            return readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        if (baseUrl.isBlank()) {
            throw IOException("Enter your backend URL. Production builds require an HTTPS backend.")
        }
        val url = URL("$baseUrl$path")
        if (url.protocol != "https" && !(BuildConfig.DEBUG && url.protocol == "http")) {
            throw IOException("Production builds require HTTPS. The http://10.0.2.2:8000 address only works in a debug Android emulator.")
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (apiToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $apiToken")
        }
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.useCaches = false
        return connection
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(body).optString("detail", body) }.getOrDefault(body)
            throw IOException("Server returned HTTP $code: $detail")
        }
        return JSONObject(body)
    }
}
