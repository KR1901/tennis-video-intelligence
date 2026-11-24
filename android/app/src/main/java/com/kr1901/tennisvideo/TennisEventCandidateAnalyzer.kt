package com.kr1901.tennisvideo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.acos
import kotlin.math.sqrt

internal object TennisEventCandidateAnalyzer {
    fun deriveRecentCandidates(samples: List<JSONObject>): List<JSONObject> {
        if (samples.size < 3) return emptyList()
        val output = ArrayList<JSONObject>()
        var lastCandidateTimestamp = -MIN_CANDIDATE_SPACING_MS

        for (index in 2 until samples.size) {
            val first = ballPoint(samples[index - 2]) ?: continue
            val second = ballPoint(samples[index - 1]) ?: continue
            val third = ballPoint(samples[index]) ?: continue
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

            val timestamp = samples[index].optLong("timestamp_ms")
            if (timestamp - lastCandidateTimestamp < MIN_CANDIDATE_SPACING_MS) continue

            val nearestWrist = nearestWristDistance(samples[index], third.x, third.y)
            val type = if (nearestWrist <= RACKET_PROXIMITY_THRESHOLD) {
                "possible_racket_contact"
            } else if (verticalVelocityReversed(v1y, v2y)) {
                "possible_bounce_or_contact"
            } else {
                "possible_ball_direction_change"
            }
            val turnStrength = (turnAngle / Math.PI).coerceIn(0.0, 1.0)
            val confidence = (minOf(first.confidence, second.confidence, third.confidence) *
                (0.55 + 0.45 * turnStrength)).coerceIn(0.0, 1.0)

            output.add(
                JSONObject()
                    .put("event_id", "candidate-" + type + "-" + timestamp)
                    .put("timestamp_ms", timestamp)
                    .put("event_type", type)
                    .put("source", "TrackNet V1 ball trajectory heuristic")
                    .put("confidence", confidence)
                    .put("review_status", "needs_review")
                    .put("note", "A ball trajectory turn was detected. This is a review candidate, not a confirmed hit or bounce.")
                    .put("turn_angle_degrees", Math.toDegrees(turnAngle))
                    .put("ball_x", third.x)
                    .put("ball_y", third.y)
            )
            lastCandidateTimestamp = timestamp
        }
        return output
    }

    private fun ballPoint(sample: JSONObject): BallPoint? {
        val ball = sample.optJSONObject("ball") ?: return null
        if (!ball.optBoolean("detected", false)) return null
        return BallPoint(
            x = ball.optDouble("x", Double.NaN),
            y = ball.optDouble("y", Double.NaN),
            confidence = ball.optDouble("confidence", 0.0)
        ).takeIf { it.x.isFinite() && it.y.isFinite() }
    }

    private fun nearestWristDistance(sample: JSONObject, ballX: Double, ballY: Double): Double {
        val players = sample.optJSONArray("players") ?: JSONArray()
        var nearest = Double.POSITIVE_INFINITY
        for (index in 0 until players.length()) {
            val player = players.optJSONObject(index) ?: continue
            listOf("left_wrist", "right_wrist").forEach { key ->
                val wrist = player.optJSONObject(key) ?: return@forEach
                if (!wrist.optBoolean("visible", false)) return@forEach
                val dx = wrist.optDouble("x") - ballX
                val dy = wrist.optDouble("y") - ballY
                nearest = minOf(nearest, sqrt(dx * dx + dy * dy))
            }
        }
        return nearest
    }

    private fun verticalVelocityReversed(first: Double, second: Double): Boolean =
        (first < -MIN_VERTICAL_SPEED && second > MIN_VERTICAL_SPEED) ||
            (first > MIN_VERTICAL_SPEED && second < -MIN_VERTICAL_SPEED)

    private data class BallPoint(
        val x: Double,
        val y: Double,
        val confidence: Double
    )

    private const val MIN_BALL_CONFIDENCE = 0.58
    private const val MIN_FRAME_DISPLACEMENT = 0.002
    private const val MIN_TURN_ANGLE_RADIANS = 2.00
    private const val MIN_VERTICAL_SPEED = 0.002
    private const val RACKET_PROXIMITY_THRESHOLD = 0.045
    private const val MIN_CANDIDATE_SPACING_MS = 500L
}
