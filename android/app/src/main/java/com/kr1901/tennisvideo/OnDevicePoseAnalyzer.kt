package com.kr1901.tennisvideo

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max
import kotlin.math.min

internal data class PlayerPose(
    val footX: Float,
    val footY: Float,
    val confidence: Float,
    val leftWristX: Float,
    val leftWristY: Float,
    val rightWristX: Float,
    val rightWristY: Float,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float
)

internal class OnDevicePoseAnalyzer(context: Context) : AutoCloseable {
    private val landmarker: PoseLandmarker

    init {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(MAX_PLAYERS)
            .setMinPoseDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinPosePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap, timestampMs: Long): List<PlayerPose> {
        check(!bitmap.isRecycled) { "Pose analysis received a recycled video frame." }

        // MediaPipe's BitmapImageContainer recycles the Bitmap when MPImage.close()
        // releases its storage. Never wrap the worker's shared decoder frame directly:
        // TrackNet and motion analysis still need that frame after pose inference.
        val ownedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Unable to copy video frame for pose analysis.")
        val image = try {
            BitmapImageBuilder(ownedBitmap).build()
        } catch (error: Throwable) {
            if (!ownedBitmap.isRecycled) ownedBitmap.recycle()
            throw error
        }

        return try {
            val result = landmarker.detectForVideo(image, timestampMs)
            result.landmarks().mapNotNull { landmarks -> extractPlayerPose(landmarks) }
                .sortedBy { it.footX }
                .take(MAX_PLAYERS)
        } finally {
            // This recycles only ownedBitmap; the caller retains its original frame.
            image.close()
        }
    }

    override fun close() {
        landmarker.close()
    }

    private fun extractPlayerPose(landmarks: List<NormalizedLandmark>): PlayerPose? {
        if (landmarks.size < LANDMARK_COUNT) return null

        val leftAnkle = visiblePoint(landmarks, LEFT_ANKLE)
            ?: visiblePoint(landmarks, LEFT_HEEL)
            ?: visiblePoint(landmarks, LEFT_FOOT_INDEX)
        val rightAnkle = visiblePoint(landmarks, RIGHT_ANKLE)
            ?: visiblePoint(landmarks, RIGHT_HEEL)
            ?: visiblePoint(landmarks, RIGHT_FOOT_INDEX)
        val footPoints = listOfNotNull(leftAnkle, rightAnkle)
        if (footPoints.isEmpty()) return null

        val footX = footPoints.map { it.first }.average().toFloat()
        val footY = footPoints.maxOf { it.second }
        if (footX !in 0f..1f || footY !in 0f..1f) return null

        val visible = landmarks.mapNotNull { point ->
            val score = confidence(point)
            if (score >= LANDMARK_VISIBILITY_THRESHOLD && point.x() in 0f..1f && point.y() in 0f..1f) {
                point
            } else {
                null
            }
        }
        if (visible.size < MIN_VISIBLE_LANDMARKS) return null

        val confidence = (
            footPoints.map { it.third.toDouble() }.average() *
                visible.size.toDouble() / LANDMARK_COUNT.toDouble()
            ).coerceIn(0.0, 1.0).toFloat()

        val leftWrist = pointOrFallback(landmarks, LEFT_WRIST)
        val rightWrist = pointOrFallback(landmarks, RIGHT_WRIST)

        return PlayerPose(
            footX = footX,
            footY = footY,
            confidence = confidence,
            leftWristX = leftWrist.first,
            leftWristY = leftWrist.second,
            rightWristX = rightWrist.first,
            rightWristY = rightWrist.second,
            boxLeft = visible.minOf { it.x() }.coerceIn(0f, 1f),
            boxTop = visible.minOf { it.y() }.coerceIn(0f, 1f),
            boxRight = visible.maxOf { it.x() }.coerceIn(0f, 1f),
            boxBottom = visible.maxOf { it.y() }.coerceIn(0f, 1f)
        )
    }

    private fun visiblePoint(
        landmarks: List<NormalizedLandmark>,
        index: Int
    ): Triple<Float, Float, Float>? {
        val point = landmarks.getOrNull(index) ?: return null
        val score = confidence(point)
        if (score < LANDMARK_VISIBILITY_THRESHOLD) return null
        if (point.x() !in 0f..1f || point.y() !in 0f..1f) return null
        return Triple(point.x(), point.y(), score)
    }

    private fun pointOrFallback(landmarks: List<NormalizedLandmark>, index: Int): Pair<Float, Float> {
        val point = visiblePoint(landmarks, index) ?: return -1f to -1f
        return point.first to point.second
    }

    private fun confidence(point: NormalizedLandmark): Float {
        return max(
            point.visibility().orElse(0f),
            point.presence().orElse(0f)
        )
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_lite.task"
        private const val MAX_PLAYERS = 2
        private const val LANDMARK_COUNT = 33
        private const val MIN_VISIBLE_LANDMARKS = 12
        private const val LANDMARK_VISIBILITY_THRESHOLD = 0.20f
        private const val MIN_DETECTION_CONFIDENCE = 0.25f
        private const val MIN_PRESENCE_CONFIDENCE = 0.25f
        private const val MIN_TRACKING_CONFIDENCE = 0.35f

        private const val LEFT_WRIST = 15
        private const val RIGHT_WRIST = 16
        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28
        private const val LEFT_HEEL = 29
        private const val RIGHT_HEEL = 30
        private const val LEFT_FOOT_INDEX = 31
        private const val RIGHT_FOOT_INDEX = 32
    }
}
