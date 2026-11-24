package com.kr1901.tennisvideo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlin.math.min

/**
 * Downloads a direct, user-authorised HTTPS video URL to private app storage.
 * Partial downloads are resumed with HTTP Range where the origin supports it.
 * This does not scrape watch pages or bypass streaming restrictions.
 */
class DirectVideoDownloadWorker(
    appContext: android.content.Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(0L, -1L)

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo(0L, -1L))
        val suppliedUrl = inputData.getString(KEY_URL)?.trim().orEmpty()
        if (suppliedUrl.isBlank()) return Result.failure()

        var targetUri = DirectVideoImportPolicy.parseAllowedHttpsUrl(suppliedUrl)
            ?: return Result.failure()

        val videoDirectory = File(applicationContext.filesDir, "remote_videos").apply { mkdirs() }
        val stableName = stableName(suppliedUrl)
        val partialFile = File(videoDirectory, "${stableName}.part")
        val finalFile = File(videoDirectory, stableName + safeExtension(targetUri.path))
        if (finalFile.isFile && finalFile.length() > 0L) {
            return registerLocalVideo(finalFile)
        }

        try {
            val startOffset = partialFile.length()
            if (startOffset > MAX_VIDEO_BYTES) {
                partialFile.delete()
                return Result.failure()
            }

            var connection: javax.net.ssl.HttpsURLConnection? = null
            var responseCode = -1
            for (redirect in 0..MAX_REDIRECTS) {
                val candidate = URL(targetUri.toString()).openConnection()
                val https = candidate as? javax.net.ssl.HttpsURLConnection
                    ?: return Result.failure()
                https.connectTimeout = CONNECT_TIMEOUT_MS
                https.readTimeout = READ_TIMEOUT_MS
                https.requestMethod = "GET"
                https.instanceFollowRedirects = false
                https.setRequestProperty("User-Agent", "TennisVideoIntelligence-Android/0.5.0")
                https.setRequestProperty("Accept", "video/*,application/octet-stream")
                if (startOffset > 0L) {
                    https.setRequestProperty("Range", "bytes=$startOffset-")
                }
                https.connect()
                responseCode = https.responseCode
                if (responseCode in REDIRECT_CODES) {
                    val location = https.getHeaderField("Location")
                    https.disconnect()
                    if (location.isNullOrBlank() || redirect == MAX_REDIRECTS) {
                        return Result.failure()
                    }
                    targetUri = targetUri.resolve(location)
                    if (!targetUri.scheme.equals("https", ignoreCase = true) ||
                        targetUri.host.isNullOrBlank()
                    ) {
                        return Result.failure()
                    }
                    continue
                }
                connection = https
                break
            }

            val activeConnection = connection ?: return Result.failure()
            try {
                val append = startOffset > 0L && responseCode == HTTP_PARTIAL_CONTENT
                if (responseCode == HTTP_PARTIAL_CONTENT) {
                    val rangeStart = parseContentRangeStart(activeConnection.getHeaderField("Content-Range"))
                    val expectedStart = if (append) startOffset else 0L
                    if (rangeStart != expectedStart) {
                        partialFile.delete()
                        return Result.retry()
                    }
                }
                if (responseCode != HTTP_OK && responseCode != HTTP_PARTIAL_CONTENT) {
                    if (responseCode >= 500 || responseCode == 408 || responseCode == 429) {
                        throw IOException("Video server returned HTTP $responseCode")
                    }
                    return Result.failure(
                        androidx.work.workDataOf(KEY_ERROR to "Video server returned HTTP $responseCode")
                    )
                }

                if (!DirectVideoImportPolicy.isDirectVideoContentType(activeConnection.contentType)) {
                    partialFile.delete()
                    return Result.failure(
                        androidx.work.workDataOf(KEY_ERROR to "The URL did not return a direct video file.")
                    )
                }

                val offset = if (append) startOffset else 0L
                if (!append && partialFile.exists()) partialFile.delete()
                val declaredLength = activeConnection.contentLengthLong
                val rangeTotal = activeConnection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                val totalLength = rangeTotal ?: if (declaredLength >= 0L) {
                    declaredLength + offset
                } else {
                    -1L
                }
                if (totalLength > MAX_VIDEO_BYTES || offset > MAX_VIDEO_BYTES) {
                    partialFile.delete()
                    return Result.failure(
                        androidx.work.workDataOf(KEY_ERROR to "The direct video exceeds the 4,768 MiB local limit.")
                    )
                }

                var bytesWritten = offset
                var lastNotificationBytes = offset
                val buffer = ByteArray(BUFFER_SIZE)
                activeConnection.inputStream.use { input ->
                    FileOutputStream(partialFile, append).buffered(BUFFER_SIZE).use { output ->
                        while (!isStopped) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            bytesWritten += count
                            if (bytesWritten > MAX_VIDEO_BYTES ||
                                (totalLength > 0L && bytesWritten > totalLength)
                            ) {
                                throw IllegalArgumentException("The direct video exceeds the 4,768 MiB local limit.")
                            }
                            output.write(buffer, 0, count)
                            val percent = if (totalLength > 0L) {
                                min(99L, bytesWritten * 100L / totalLength).toInt()
                            } else {
                                -1
                            }
                            if (bytesWritten - lastNotificationBytes >= NOTIFICATION_UPDATE_BYTES) {
                                setProgress(
                                    androidx.work.workDataOf(
                                        KEY_BYTES_DONE to bytesWritten,
                                        KEY_TOTAL_BYTES to totalLength,
                                        KEY_PERCENT to percent
                                    )
                                )
                                setForeground(createForegroundInfo(bytesWritten, totalLength))
                                lastNotificationBytes = bytesWritten
                            }
                        }
                    }
                }

                if (isStopped) return Result.retry()
                if (totalLength > 0L && bytesWritten != totalLength) {
                    throw IOException("The video server ended the response before the complete file arrived.")
                }
            } finally {
                activeConnection.disconnect()
            }

            if (!partialFile.isFile || partialFile.length() <= 0L) {
                return Result.failure(
                    androidx.work.workDataOf(KEY_ERROR to "The video download was empty.")
                )
            }
            setProgress(
                androidx.work.workDataOf(
                    KEY_BYTES_DONE to partialFile.length(),
                    KEY_TOTAL_BYTES to partialFile.length(),
                    KEY_PERCENT to 100
                )
            )
            setForeground(createForegroundInfo(partialFile.length(), partialFile.length()))
            if (!partialFile.renameTo(finalFile)) {
                throw IOException("Could not finalise the downloaded video file.")
            }
            return registerLocalVideo(finalFile)
        } catch (error: IllegalArgumentException) {
            partialFile.delete()
            return Result.failure(
                androidx.work.workDataOf(KEY_ERROR to (error.message ?: "Invalid video"))
            )
        } catch (error: Exception) {
            if (isStopped) return Result.retry()
            return if (runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(
                androidx.work.workDataOf(KEY_ERROR to (error.message ?: "Download failed"))
            )
        }
    }

    private suspend fun createForegroundInfo(
        bytesDone: Long,
        totalBytes: Long
    ): ForegroundInfo {
        ensureNotificationChannel()
        val percent = if (totalBytes > 0L) {
            min(99L, bytesDone * 100L / totalBytes).toInt()
        } else {
            0
        }
        val detail = if (totalBytes > 0L) {
            "Downloaded " + android.text.format.Formatter.formatShortFileSize(applicationContext, bytesDone) +
                " of " + android.text.format.Formatter.formatShortFileSize(applicationContext, totalBytes)
        } else if (bytesDone > 0L) {
            "Downloaded " + android.text.format.Formatter.formatShortFileSize(applicationContext, bytesDone)
        } else {
            "Starting secure video download…"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading tennis video")
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, totalBytes <= 0L)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Video downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress for user-requested tennis video downloads"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun registerLocalVideo(file: File): Result {
        return try {
            val uri = Uri.fromFile(file)
            val metadata = LocalVideoInspector.inspect(applicationContext, uri)
            if (metadata.sizeBytes <= 0L || metadata.sizeBytes > MAX_VIDEO_BYTES) {
                return Result.failure(
                    androidx.work.workDataOf(KEY_ERROR to "The downloaded video size is invalid or above the local limit.")
                )
            }
            val project = LocalProjectStore.create(applicationContext, uri, metadata)
            Result.success(androidx.work.workDataOf(KEY_PROJECT_ID to project.optString("id")))
        } catch (error: Exception) {
            Result.failure(
                androidx.work.workDataOf(KEY_ERROR to (error.message ?: "Downloaded file is not a readable video"))
            )
        }
    }

    private fun parseContentRangeStart(value: String?): Long? {
        val match = CONTENT_RANGE_PATTERN.find(value.orEmpty()) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private fun stableName(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun safeExtension(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        return if (ext in ALLOWED_EXTENSIONS) ".$ext" else ".mp4"
    }

    companion object {
        const val KEY_URL = "direct_video_url"
        const val KEY_PROJECT_ID = "created_project_id"
        const val KEY_ERROR = "download_error"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_TOTAL_BYTES = "bytes_total"
        const val KEY_PERCENT = "percent"
        private const val CHANNEL_ID = "tvi-video-downloads"
        private const val NOTIFICATION_ID = 0x545649
        private const val NOTIFICATION_UPDATE_BYTES = 1024L * 1024L
        private const val MAX_VIDEO_BYTES = 4_768L * 1024L * 1024L
        private const val BUFFER_SIZE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_RETRIES = 5
        private const val MAX_REDIRECTS = 5
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE_PATTERN = Regex("^bytes\\s+(\\d+)-", RegexOption.IGNORE_CASE)
        private val ALLOWED_EXTENSIONS = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi")
    }
}
