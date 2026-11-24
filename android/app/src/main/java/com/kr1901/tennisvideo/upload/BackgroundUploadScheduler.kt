package com.kr1901.tennisvideo.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit

object BackgroundUploadScheduler {
    const val UNIQUE_WORK_NAME = "tennis-video-upload"
    const val JOB_ID = 4107

    fun fileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) {
                    val bytes = cursor.getLong(index)
                    if (bytes > 0L) return bytes
                }
            }
        }
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    fun schedule(context: Context, uri: Uri): Boolean {
        val totalBytes = fileSize(context, uri)
        if (totalBytes <= 0L) throw IOException("Android could not determine the selected video's file size.")

        val previous = UploadRuntimeStore.state(context)
        if (previous.uri == uri && previous.status in listOf("QUEUED", "UPLOADING")) {
            return true
        }
        UploadRuntimeStore.markQueued(context, uri, totalBytes)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scheduleUserInitiatedJob(context, uri, totalBytes)
        } else {
            scheduleWorkManager(context, uri, totalBytes)
        }
    }

    fun cancel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
        UploadRuntimeStore.markPaused(context)
    }

    private fun scheduleUserInitiatedJob(context: Context, uri: Uri, totalBytes: Long): Boolean {
        val clip = ClipData.newUri(context.contentResolver, "Tennis match video", uri)
        val extras = PersistableBundle().apply {
            putString(VideoUploadJobService.KEY_URI, uri.toString())
            putLong(VideoUploadJobService.KEY_TOTAL_BYTES, totalBytes)
        }
        val jobInfo = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, VideoUploadJobService::class.java)
        )
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(totalBytes, totalBytes)
            .setClipData(clip, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras)
            .build()

        val scheduler = context.getSystemService(JobScheduler::class.java)
        return scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
    }

    private fun scheduleWorkManager(context: Context, uri: Uri, totalBytes: Long): Boolean {
        val request = OneTimeWorkRequestBuilder<VideoUploadWorker>()
            .setInputData(
                workDataOf(
                    VideoUploadWorker.KEY_URI to uri.toString(),
                    VideoUploadWorker.KEY_TOTAL_BYTES to totalBytes
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(30_000L, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        return true
    }
}
