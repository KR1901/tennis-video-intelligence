package com.kr1901.tennisvideo.upload

import android.app.job.JobParameters
import android.app.job.JobService
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import com.kr1901.tennisvideo.data.VideoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class VideoUploadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

        val extras: PersistableBundle = params.extras
        val uri = params.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: extras.getString(KEY_URI)?.let(Uri::parse)
            ?: return false

        val connection = UploadRuntimeStore.connection(this)
        if (connection.baseUrl.isBlank()) {
            UploadRuntimeStore.markFailed(this, "Backend URL is not configured.")
            return false
        }

        val totalBytes = extras.getLong(KEY_TOTAL_BYTES, 0L)
        setNotification(
            params,
            UploadNotifications.NOTIFICATION_ID,
            UploadNotifications.build(this, 0L, totalBytes),
            JOB_END_NOTIFICATION_POLICY_DETACH
        )
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val workerJob = coroutineContext[Job]
            try {
                val uploadPreferences = getSharedPreferences("tvi_upload_state", MODE_PRIVATE)
                val api = VideoApi(
                    connection.baseUrl,
                    contentResolver,
                    connection.apiToken,
                    uploadPreferences
                )
                val response = api.uploadVideo(
                    uri,
                    onProgress = { sent, total ->
                        UploadRuntimeStore.updateProgress(this@VideoUploadJobService, sent, total)
                        setNotification(
                            params,
                            UploadNotifications.NOTIFICATION_ID,
                            UploadNotifications.build(this@VideoUploadJobService, sent, total),
                            JOB_END_NOTIFICATION_POLICY_DETACH
                        )
                    },
                    isCancelled = { workerJob?.isActive != true }
                )
                val videoId = response.optString("video_id")
                if (videoId.isBlank()) throw IOException("The server completed the upload without returning a video ID.")
                UploadRuntimeStore.markComplete(this@VideoUploadJobService, videoId, totalBytes)
                UploadNotifications.complete(this@VideoUploadJobService, videoId)
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                UploadRuntimeStore.markPaused(this@VideoUploadJobService)
                throw cancelled
            } catch (error: Exception) {
                if (workerJob?.isActive != true) {
                    UploadRuntimeStore.markPaused(this@VideoUploadJobService)
                    return@launch
                }
                val retry = isRetryable(error)
                if (retry) {
                    UploadRuntimeStore.markPaused(this@VideoUploadJobService)
                } else {
                    UploadRuntimeStore.markFailed(this@VideoUploadJobService, error.message ?: "Upload failed.")
                    UploadNotifications.failed(this@VideoUploadJobService, error.message ?: "Upload failed.")
                }
                jobFinished(params, retry)
            } finally {
                runningJobs.remove(params.jobId)
            }
        }
        runningJobs[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.remove(params.jobId)?.cancel()
        UploadRuntimeStore.markPaused(this)
        // The server has durable byte offsets, so the system may safely reschedule.
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isRetryable(error: Exception): Boolean {
        if (error is CancellationException) return true
        val message = error.message.orEmpty()
        return listOf(400, 401, 403, 404, 413, 415, 422).none {
            message.contains("HTTP $it")
        }
    }

    companion object {
        const val KEY_URI = "upload_uri"
        const val KEY_TOTAL_BYTES = "upload_total_bytes"
    }
}
