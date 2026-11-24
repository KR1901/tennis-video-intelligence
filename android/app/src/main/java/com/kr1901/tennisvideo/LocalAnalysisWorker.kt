package com.kr1901.tennisvideo

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        val frameIntervalMs = inputData.getLong(KEY_FRAME_INTERVAL_MS, DEFAULT_FRAME_INTERVAL_MS)
            .takeIf { it == 400L || it == 200L || it == 100L } ?: DEFAULT_FRAME_INTERVAL_MS
        val project = LocalProjectStore.load(applicationContext, projectId)
            ?: return@withContext Result.failure()
        val runId = inputData.getString(KEY_RUN_ID)?.takeIf { it.isNotBlank() }
            ?: LEGACY_RUN_ID
        if (!LocalProjectStore.isCurrentAnalysisRun(applicationContext, projectId, runId)) {
            return@withContext Result.success()
        }
        val durationMs = project.optLong("duration_ms", 0L)
        val contentUri = project.optString("content_uri")
        if (durationMs <= 0L || contentUri.isBlank()) {
            LocalProjectStore.updateProgress(
                applicationContext,
                projectId,
                "failed",
                0,
                "Video metadata is incomplete."
            )
            return@withContext Result.failure()
        }

        val currentProgress = project.optInt("progress_pct", 0)
        LocalProjectStore.updateProgress(
            applicationContext,
            projectId,
            "processing",
            currentProgress
        )
        setProgress(workDataOf(KEY_PROGRESS_PCT to currentProgress))

        val nextStartMs = min(durationMs, startMs + CHUNK_DURATION_MS)
        val retriever = MediaMetadataRetriever()
        val additions = ArrayList<JSONObject>()
        var olderFrame: Bitmap? = null
        var previousFrame: Bitmap? = null
        var previousMotionPixels: IntArray? = null
        var sampleIndex = 0
        val tracker = PlayerPoseTracker(
            LocalProjectStore.lastPlayerTracks(applicationContext, projectId, startMs)
        )
        val courtCorners = readCourtCorners(project)

        try {
            retriever.setDataSource(applicationContext, Uri.parse(contentUri))

            // The first two frames of each continuation are context only: TrackNet requires
            // three consecutive images even when WorkManager starts a fresh process.
            if (startMs >= frameIntervalMs * 2L) {
                olderFrame = decodeFrame(retriever, startMs - frameIntervalMs * 2L)
                previousFrame = decodeFrame(retriever, startMs - frameIntervalMs)
            }

            OnDevicePoseAnalyzer(applicationContext).use { poseAnalyzer ->
                TrackNetBallDetector(applicationContext).use { ballDetector ->
                    var timeMs = startMs
                    while (timeMs < nextStartMs && !isStopped) {
                        val frame = decodeFrame(retriever, timeMs)
                        if (frame == null) {
                            olderFrame?.recycle()
                            previousFrame?.recycle()
                            olderFrame = null
                            previousFrame = null
                            previousMotionPixels = null
                            additions.add(
                                JSONObject()
                                    .put("timestamp_ms", timeMs)
                                    .put("decoded", false)
                                    .put("players", JSONArray())
                                    .put("ball", JSONObject().put("detected", false))
                            )
                            timeMs += frameIntervalMs
                            sampleIndex++
                            continue
                        }

                        val ball = if (olderFrame != null && previousFrame != null) {
                            ballDetector.detect(frame, previousFrame!!, olderFrame!!)
                        } else {
                            null
                        }

                        val players = JSONArray()
                        if (sampleIndex % POSE_SAMPLE_EVERY_N_FRAMES == 0) {
                            val poses = poseAnalyzer.detect(frame, timeMs)
                            tracker.assign(poses).forEach { tracked ->
                                players.put(playerToJson(tracked.playerId, tracked.pose, courtCorners))
                            }
                        }

                        val miniature = Bitmap.createScaledBitmap(
                            frame,
                            MOTION_WIDTH,
                            MOTION_HEIGHT,
                            true
                        )
                        val pixels = IntArray(MOTION_WIDTH * MOTION_HEIGHT)
                        miniature.getPixels(
                            pixels,
                            0,
                            MOTION_WIDTH,
                            0,
                            0,
                            MOTION_WIDTH,
                            MOTION_HEIGHT
                        )
                        miniature.recycle()

                        var greenPixels = 0
                        var brightnessTotal = 0.0
                        var changedPixels = 0
                        val previous = previousMotionPixels
                        for (index in pixels.indices) {
                            val pixel = pixels[index]
                            val red = Color.red(pixel)
                            val green = Color.green(pixel)
                            val blue = Color.blue(pixel)
                            if (green > 60 && green > red * 1.12 && green > blue * 1.08) {
                                greenPixels++
                            }
                            brightnessTotal += red * 0.299 + green * 0.587 + blue * 0.114
                            if (previous != null) {
                                val difference = abs(red - Color.red(previous[index])) +
                                    abs(green - Color.green(previous[index])) +
                                    abs(blue - Color.blue(previous[index]))
                                if (difference > PIXEL_CHANGE_THRESHOLD) changedPixels++
                            }
                        }
                        val pixelCount = pixels.size.toDouble()
                        val sample = JSONObject()
                            .put("timestamp_ms", timeMs)
                            .put("decoded", true)
                            .put("green_pct", greenPixels * 100.0 / pixelCount)
                            .put(
                                "motion_pct",
                                if (previous == null) 0.0 else changedPixels * 100.0 / pixelCount
                            )
                            .put("brightness", brightnessTotal / pixelCount)
                            .put("players", players)

                        if (ball != null) {
                            val ballJson = JSONObject()
                                .put("detected", true)
                                .put("x", ball.x)
                                .put("y", ball.y)
                                .put("confidence", ball.confidence)
                            if (courtCorners.size == 4) {
                                val measurement = CourtProjector.project(courtCorners, ball.x, ball.y)
                                if (measurement != null) {
                                    ballJson.put("court_x_m", measurement.xMeters)
                                        .put("court_y_m", measurement.yMeters)
                                        .put("player_end", measurement.playerEnd)
                                        .put("baseline_distance_m", measurement.baselineDistanceMeters)
                                }
                            }
                            sample.put("ball", ballJson)
                        } else {
                            sample.put("ball", JSONObject().put("detected", false))
                        }
                        additions.add(sample)
                        previousMotionPixels = pixels

                        olderFrame?.recycle()
                        olderFrame = previousFrame
                        previousFrame = frame

                        timeMs += frameIntervalMs
                        sampleIndex++
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalProjectStore.updateProgress(
                applicationContext,
                projectId,
                "failed",
                ((startMs * 100L) / durationMs).toInt(),
                error.message ?: "On-device model inference failed."
            )
            return@withContext Result.failure()
        } finally {
            olderFrame?.recycle()
            previousFrame?.recycle()
            retriever.release()
        }

        if (isStopped) return@withContext Result.retry()
        if (!LocalProjectStore.isCurrentAnalysisRun(applicationContext, projectId, runId)) {
            return@withContext Result.success()
        }

        LocalProjectStore.appendSamples(applicationContext, projectId, additions)
        val recentSamples = LocalProjectStore.load(applicationContext, projectId)
            ?.optJSONArray("samples")
            ?.let { samples ->
                (0 until samples.length()).mapNotNull { samples.optJSONObject(it) }
            }
            ?: emptyList()
        val trajectory = recentSamples.mapNotNull(::toBallTrajectoryObservation)
        val eventCandidates = TennisEventCandidateAnalyzer.deriveRecentCandidates(trajectory)
        LocalProjectStore.appendEventCandidates(
            applicationContext,
            projectId,
            eventCandidates.map { candidate ->
                JSONObject()
                    .put("event_id", candidate.eventId)
                    .put("timestamp_ms", candidate.timestampMs)
                    .put("event_type", candidate.eventType)
                    .put("source", candidate.source)
                    .put("confidence", candidate.confidence)
                    .put("review_status", candidate.reviewStatus)
                    .put("note", candidate.note)
                    .put("turn_angle_degrees", candidate.turnAngleDegrees)
                    .put("ball_x", candidate.ballX)
                    .put("ball_y", candidate.ballY)
            }
        )

        val completed = nextStartMs >= durationMs
        val progress = if (completed) 100 else ((nextStartMs * 100L) / durationMs).toInt()
        LocalProjectStore.updateProgress(
            applicationContext,
            projectId,
            if (completed) "completed" else "processing",
            progress
        )
        setProgress(workDataOf(KEY_PROGRESS_PCT to progress))

        if (isStopped) return@withContext Result.retry()
        if (!LocalProjectStore.isCurrentAnalysisRun(applicationContext, projectId, runId)) {
            return@withContext Result.success()
        }

        if (!completed) {
            val nextRequest = OneTimeWorkRequestBuilder<LocalAnalysisWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROJECT_ID to projectId,
                        KEY_RUN_ID to runId,
                        KEY_START_MS to nextStartMs,
                        KEY_FRAME_INTERVAL_MS to frameIntervalMs
                    )
                )
                .addTag(tag(projectId))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                workName(projectId, runId, nextStartMs),
                ExistingWorkPolicy.KEEP,
                nextRequest
            )
        } else if (LocalProjectStore.load(applicationContext, projectId)
                ?.optJSONArray("samples")?.length() == 0
        ) {
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

    private fun toBallTrajectoryObservation(sample: JSONObject): BallTrajectoryObservation? {
        val ball = sample.optJSONObject("ball") ?: return null
        if (!ball.optBoolean("detected", false)) return null
        val x = ball.optDouble("x", Double.NaN)
        val y = ball.optDouble("y", Double.NaN)
        val confidence = ball.optDouble("confidence", 0.0)
        if (!x.isFinite() || !y.isFinite()) return null

        val wrists = ArrayList<Pair<Double, Double>>()
        val players = sample.optJSONArray("players") ?: JSONArray()
        for (index in 0 until players.length()) {
            val player = players.optJSONObject(index) ?: continue
            for (key in listOf("left_wrist", "right_wrist")) {
                val wrist = player.optJSONObject(key) ?: continue
                if (wrist.optBoolean("visible", false)) {
                    val wristX = wrist.optDouble("x", Double.NaN)
                    val wristY = wrist.optDouble("y", Double.NaN)
                    if (wristX.isFinite() && wristY.isFinite()) {
                        wrists.add(wristX to wristY)
                    }
                }
            }
        }
        return BallTrajectoryObservation(
            timestampMs = sample.optLong("timestamp_ms"),
            x = x,
            y = y,
            confidence = confidence,
            visibleWristPoints = wrists
        )
    }

    private fun decodeFrame(
        retriever: MediaMetadataRetriever,
        timestampMs: Long
    ): Bitmap? {
        val timeUs = timestampMs.coerceAtLeast(0L) * 1000L
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                MODEL_WIDTH,
                MODEL_HEIGHT
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return null

        if (decoded.width == MODEL_WIDTH && decoded.height == MODEL_HEIGHT) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, MODEL_WIDTH, MODEL_HEIGHT, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun readCourtCorners(project: JSONObject): List<Pair<Float, Float>> {
        val corners = project.optJSONArray("court_corners") ?: return emptyList()
        if (corners.length() != 4) return emptyList()
        return (0 until 4).mapNotNull { index ->
            val point = corners.optJSONObject(index) ?: return@mapNotNull null
            point.optDouble("x").toFloat() to point.optDouble("y").toFloat()
        }.takeIf { it.size == 4 } ?: emptyList()
    }

    private fun playerToJson(
        playerId: String,
        pose: PlayerPose,
        courtCorners: List<Pair<Float, Float>>
    ): JSONObject {
        val player = JSONObject()
            .put("player_id", playerId)
            .put("foot_x", pose.footX)
            .put("foot_y", pose.footY)
            .put("confidence", pose.confidence)
            .put("box_left", pose.boxLeft)
            .put("box_top", pose.boxTop)
            .put("box_right", pose.boxRight)
            .put("box_bottom", pose.boxBottom)
            .put("left_wrist", wristJson(pose.leftWristX, pose.leftWristY))
            .put("right_wrist", wristJson(pose.rightWristX, pose.rightWristY))

        if (courtCorners.size == 4) {
            val measurement = CourtProjector.project(courtCorners, pose.footX, pose.footY)
            if (measurement != null) {
                player.put("court_x_m", measurement.xMeters)
                    .put("court_y_m", measurement.yMeters)
                    .put("player_end", measurement.playerEnd)
                    .put("baseline_distance_m", measurement.baselineDistanceMeters)
            }
        }
        return player
    }

    private fun wristJson(x: Float, y: Float): JSONObject =
        JSONObject()
            .put("visible", x >= 0f && y >= 0f)
            .put("x", x)
            .put("y", y)

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_START_MS = "start_ms"
        const val KEY_FRAME_INTERVAL_MS = "frame_interval_ms"
        const val KEY_PROGRESS_PCT = "progress_pct"
        const val KEY_RUN_ID = "run_id"
        private const val LEGACY_RUN_ID = "legacy"
        private const val DEFAULT_FRAME_INTERVAL_MS = 200L
        private const val CHUNK_DURATION_MS = 5_000L
        private const val POSE_SAMPLE_EVERY_N_FRAMES = 2
        private const val MODEL_WIDTH = 640
        private const val MODEL_HEIGHT = 360
        private const val MOTION_WIDTH = 160
        private const val MOTION_HEIGHT = 90
        private const val PIXEL_CHANGE_THRESHOLD = 72

        fun tag(projectId: String): String = "tvi-local-analysis-" + projectId
        fun workName(projectId: String, runId: String, startMs: Long): String =
            "tvi-local-analysis-" + projectId + "-" + runId + "-" + startMs

        fun workName(projectId: String, startMs: Long): String =
            workName(projectId, LEGACY_RUN_ID, startMs)
    }
}
