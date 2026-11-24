package com.kr1901.tennisvideo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TennisEventCandidateAnalyzerTest {
    @Test
    fun detectsSharpBallDirectionTurnAsUnconfirmedCandidate() {
        val samples = listOf(
            sample(0L, 0.1, 0.3, 0.92),
            sample(200L, 0.3, 0.3, 0.94),
            sample(400L, 0.1, 0.3, 0.91)
        )

        val candidates = TennisEventCandidateAnalyzer.deriveRecentCandidates(samples)

        assertEquals(1, candidates.size)
        assertEquals("possible_ball_direction_change", candidates[0].optString("event_type"))
        assertEquals("needs_review", candidates[0].optString("review_status"))
        assertTrue(candidates[0].optDouble("confidence") in 0.0..1.0)
    }

    @Test
    fun doesNotInferAnEventFromStraightBallMotion() {
        val samples = listOf(
            sample(0L, 0.1, 0.4, 0.92),
            sample(200L, 0.2, 0.4, 0.93),
            sample(400L, 0.3, 0.4, 0.94)
        )
        assertTrue(TennisEventCandidateAnalyzer.deriveRecentCandidates(samples).isEmpty())
    }

    @Test
    fun ignoresLowConfidenceBallObservations() {
        val samples = listOf(
            sample(0L, 0.1, 0.4, 0.2),
            sample(200L, 0.3, 0.4, 0.2),
            sample(400L, 0.1, 0.4, 0.2)
        )
        assertTrue(TennisEventCandidateAnalyzer.deriveRecentCandidates(samples).isEmpty())
    }

    private fun sample(time: Long, x: Double, y: Double, confidence: Double) =
        JSONObject()
            .put("timestamp_ms", time)
            .put(
                "ball",
                JSONObject()
                    .put("detected", true)
                    .put("x", x)
                    .put("y", y)
                    .put("confidence", confidence)
            )
            .put("players", org.json.JSONArray())
}
