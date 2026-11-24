package com.kr1901.tennisvideo.upload

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kr1901.tennisvideo.data.VideoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException

class VideoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(
            inputData.getLong(KEY_TOTAL_BYTES, 0L).let { 0L to it }
        )

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val connection = UploadRuntimeStore.connection(applicationContext)
        if (connection.baseUrl.isBlank()) {
            UploadRuntimeStore.markFailed(applicationContext, "Backend URL is not configured.")
            return Result.failure()
        }

        setForeground(createForegroundInfo(0L to totalBytes))
        val workerJob = coroutineContext[Job]
        return try {
            val api = VideoApi(
                connection.baseUrl,
                applicationContext.contentResolver,
                connection.apiToken,
                applicationContext.getSharedPreferences("tvi_upload_state", Context.MODE_PRIVATE)
            )
            val response = withContext(Dispatchers.IO) {
                api.uploadVideo(
                    uri,
                    onProgress = { sent, total ->
                        UploadRuntimeStore.updateProgress(applicationContext, sent, total)
                        setProgressAsync(workDataOf(KEY_UPLOADED_BYTES to sent, KEY_TOTAL_BYTES to total))
                        NotificationManagerCompat.from(applicationContext).notify(
                            UploadNotifications.NOTIFICATION_ID,
                            UploadNotifications.build(applicationContext, sent, total)
                        )
                    },
                    isCancelled = { workerJob?.isActive != true }
                )
            }
            val videoId = response.optString("video_id")
            if (videoId.isBlank()) throw IOException("The server completed the upload without returning a video ID.")
            UploadRuntimeStore.markComplete(applicationContext, videoId, totalBytes)
            UploadNotifications.complete(applicationContext, videoId)
            Result.success(workDataOf(KEY_VIDEO_ID to videoId))
        } catch (cancelled: CancellationException) {
            UploadRuntimeStore.markPaused(applicationContext)
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "Upload failed."
            if (isRetryable(error)) {
                UploadRuntimeStore.markPaused(applicationContext)
                Result.retry()
            } else {
                UploadRuntimeStore.markFailed(applicationContext, message)
                UploadNotifications.failed(applicationContext, message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }
        }
    }

    private fun createForegroundInfo(progress: Pair<Long, Long>): ForegroundInfo {
        val notification = UploadNotifications.build(
            applicationContext,
            progress.first,
            progress.second
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UploadNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(UploadNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun isRetryable(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return listOf(400, 401, 403, 404, 413, 415, 422).none { message.contains("HTTP $it") }
    }

    companion object {
        const val KEY_URI = "upload_uri"
        const val KEY_TOTAL_BYTES = "upload_total_bytes"
        const val KEY_UPLOADED_BYTES = "upload_uploaded_bytes"
        const val KEY_VIDEO_ID = "upload_video_id"
        const val KEY_ERROR = "upload_error"
    }
}
