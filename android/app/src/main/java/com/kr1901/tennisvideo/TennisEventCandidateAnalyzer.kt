package com.kr1901.tennisvideo

import kotlin.math.acos
import kotlin.math.sqrt

internal data class BallTrajectoryObservation(
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val confidence: Double,
    val visibleWristPoints: List<Pair<Double, Double>> = emptyList()
)

internal data class TennisEventCandidate(
    val eventId: String,
    val timestampMs: Long,
    val eventType: String,
    val confidence: Double,
    val turnAngleDegrees: Double,
    val ballX: Double,
    val ballY: Double,
    val source: String = "TrackNet V1 ball trajectory heuristic",
    val reviewStatus: String = "needs_review",
    val note: String = "A ball trajectory turn was detected. This is a review candidate, not a confirmed hit or bounce."
)

internal object TennisEventCandidateAnalyzer {
    fun deriveRecentCandidates(samples: List<BallTrajectoryObservation>): List<TennisEventCandidate> {
        if (samples.size < 3) return emptyList()
        val output = ArrayList<TennisEventCandidate>()
        var lastCandidateTimestamp = -MIN_CANDIDATE_SPACING_MS

        for (index in 2 until samples.size) {
            val first = samples[index - 2]
            val second = samples[index - 1]
            val third = samples[index]
            if (minOf(first.confidence, second.confidence, third.confidence) < MIN_BALL_CONFIDENCE) {
                continue
            }

            val v1x = second.x - first.x
            val v1y = second.y - first.y
            val v2x = third.x - second.x
            val v2y = third.y - second.y
            val speed1 = sqrt(v1x * v1x + v1y * v1y)
            val speed2 = sqrt(v2x * v2x + v2y * v2y)
            if (speed1 < MIN_FRAME_DISPLACEMENT || speed2 < MIN_FRAME_DISPLACEMENT) continue

            val cosine = ((v1x * v2x + v1y * v2y) / (speed1 * speed2)).coerceIn(-1.0, 1.0)
            val turnAngle = acos(cosine)
            if (turnAngle < MIN_TURN_ANGLE_RADIANS) continue

            val timestamp = third.timestampMs
            if (timestamp - lastCandidateTimestamp < MIN_CANDIDATE_SPACING_MS) continue

            val nearestWrist = nearestWristDistance(third.visibleWristPoints, third.x, third.y)
            val type = when {
                nearestWrist <= RACKET_PROXIMITY_THRESHOLD -> "possible_racket_contact"
                verticalVelocityReversed(v1y, v2y) -> "possible_bounce_or_contact"
                else -> "possible_ball_direction_change"
            }
            val turnStrength = (turnAngle / Math.PI).coerceIn(0.0, 1.0)
            val confidence = (minOf(first.confidence, second.confidence, third.confidence) *
                (0.55 + 0.45 * turnStrength)).coerceIn(0.0, 1.0)

            output.add(
                TennisEventCandidate(
                    eventId = "candidate-" + type + "-" + timestamp,
                    timestampMs = timestamp,
                    eventType = type,
                    confidence = confidence,
                    turnAngleDegrees = Math.toDegrees(turnAngle),
                    ballX = third.x,
                    ballY = third.y
                )
            )
            lastCandidateTimestamp = timestamp
        }
        return output
    }

    private fun nearestWristDistance(
        wrists: List<Pair<Double, Double>>,
        ballX: Double,
        ballY: Double
    ): Double {
        return wrists.minOfOrNull { (x, y) ->
            val dx = x - ballX
            val dy = y - ballY
            sqrt(dx * dx + dy * dy)
        } ?: Double.POSITIVE_INFINITY
    }

    private fun verticalVelocityReversed(first: Double, second: Double): Boolean =
        (first < -MIN_VERTICAL_SPEED && second > MIN_VERTICAL_SPEED) ||
            (first > MIN_VERTICAL_SPEED && second < -MIN_VERTICAL_SPEED)

    private const val MIN_BALL_CONFIDENCE = 0.58
    private const val MIN_FRAME_DISPLACEMENT = 0.002
    private const val MIN_TURN_ANGLE_RADIANS = 2.00
    private const val MIN_VERTICAL_SPEED = 0.002
    private const val RACKET_PROXIMITY_THRESHOLD = 0.045
    private const val MIN_CANDIDATE_SPACING_MS = 500L
}
