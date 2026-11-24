package com.kr1901.tennisvideo

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

class LocalAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return@withContext Result.failure()
        val startMs = inputData.getLong(KEY_START_MS, 0L).coerceAtLeast(0L)
        val project = LocalProjectStore.load(applicationContext, projectId)
            ?: return@withContext Result.failure()
        val durationMs = project.optLong("duration_ms", 0L)
        val contentUri = project.optString("content_uri")
        if (durationMs <= 0L || contentUri.isBlank()) {
            LocalProjectStore.updateProgress(applicationContext, projectId, "failed", 0, "Video metadata is incomplete.")
            return@withContext Result.failure()
        }

        val intervalMs = SAMPLE_INTERVAL_MS
        val nextStartMs = min(durationMs, startMs + CHUNK_SAMPLE_COUNT * intervalMs)
        val retriever = MediaMetadataRetriever()
        val additions = ArrayList<JSONObject>()
        var previousPixels: IntArray? = null
        var timeMs = startMs

        try {
            retriever.setDataSource(applicationContext, Uri.parse(contentUri))
            while (timeMs < nextStartMs && !isStopped) {
                val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        FRAME_WIDTH,
                        FRAME_HEIGHT
                    )
                } else {
                    retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                if (source != null) {
                    val scaled = Bitmap.createScaledBitmap(source, FRAME_WIDTH, FRAME_HEIGHT, true)
                    if (scaled !== source) source.recycle()
                    val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT)
                    scaled.getPixels(pixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
                    var greenPixels = 0
                    var brightnessTotal = 0.0
                    var changedPixels = 0
                    val previous = previousPixels
                    for (index in pixels.indices) {
                        val pixel = pixels[index]
                        val red = Color.red(pixel)
                        val green = Color.green(pixel)
                        val blue = Color.blue(pixel)
                        if (green > 60 && green > red * 1.12 && green > blue * 1.08) greenPixels++
                        brightnessTotal += red * 0.299 + green * 0.587 + blue * 0.114
                        if (previous != null) {
                            val difference = abs(red - Color.red(previous[index])) +
                                abs(green - Color.green(previous[index])) +
                                abs(blue - Color.blue(previous[index]))
                            if (difference > PIXEL_CHANGE_THRESHOLD) changedPixels++
                        }
                    }
                    val count = pixels.size.toDouble()
                    val sample = JSONObject()
                        .put("timestamp_ms", timeMs)
                        .put("green_pct", greenPixels * 100.0 / count)
                        .put("motion_pct", if (previous == null) 0.0 else changedPixels * 100.0 / count)
                        .put("brightness", brightnessTotal / count)
                    additions.add(sample)
                    previousPixels = pixels
                    scaled.recycle()
                }
                timeMs += intervalMs
            }
        } catch (error: Exception) {
            LocalProjectStore.updateProgress(
                applicationContext,
                projectId,
                "failed",
                ((startMs * 100L) / durationMs).toInt(),
                error.message ?: "Frame decoding failed."
            )
            return@withContext Result.failure()
        } finally {
            retriever.release()
        }

        if (isStopped) return@withContext Result.retry()

        LocalProjectStore.appendSamples(applicationContext, projectId, additions)
        val completed = nextStartMs >= durationMs
        val progress = if (completed) 100 else ((nextStartMs * 100L) / durationMs).toInt()
        LocalProjectStore.updateProgress(
            applicationContext,
            projectId,
            if (completed) "completed" else "processing",
            progress
        )

        if (!completed) {
            val nextRequest = OneTimeWorkRequestBuilder<LocalAnalysisWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROJECT_ID to projectId,
                        KEY_START_MS to nextStartMs
                    )
                )
                .addTag(tag(projectId))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                workName(projectId, nextStartMs),
                ExistingWorkPolicy.KEEP,
                nextRequest
            )
        } else if (LocalProjectStore.load(applicationContext, projectId)?.optJSONArray("samples")?.length() == 0) {
            LocalProjectStore.updateProgress(
                applicationContext,
                projectId,
                "failed",
                100,
                "No video frames could be decoded."
            )
        }
        Result.success()
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_START_MS = "start_ms"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val CHUNK_SAMPLE_COUNT = 24L
        private const val FRAME_WIDTH = 160
        private const val FRAME_HEIGHT = 90
        private const val PIXEL_CHANGE_THRESHOLD = 72

        fun tag(projectId: String): String = "tvi-local-analysis-" + projectId
        fun workName(projectId: String, startMs: Long): String =
            "tvi-local-analysis-" + projectId + "-" + startMs
    }
}
