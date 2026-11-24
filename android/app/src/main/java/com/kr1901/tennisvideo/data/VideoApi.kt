package com.kr1901.tennisvideo.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

class VideoApi(
    baseUrl: String,
    private val contentResolver: ContentResolver,
    private val apiToken: String = ""
) {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    fun health(): JSONObject = requestJson("/health", "GET")

    fun uploadVideo(uri: Uri): JSONObject {
        val fileName = displayName(uri)
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val boundary = "TviBoundary-${UUID.randomUUID()}"
        val connection = openConnection("/api/v1/videos", "POST")
        connection.doOutput = true
        connection.setChunkedStreamingMode(1024 * 1024)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Accept", "application/json")

        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${safeFileName(fileName)}\"\r\n".toByteArray()
                )
                output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("Android could not open the selected video.")
                BufferedInputStream(input).use { source ->
                    source.copyTo(output, bufferSize = 128 * 1024)
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
                output.flush()
            }
            return readJsonResponse(connection)
        } finally {
            connection.disconnect()
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
            .put("sample_interval_seconds", 0.333)
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

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "match.mp4" }

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
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (apiToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $apiToken")
        }
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
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
