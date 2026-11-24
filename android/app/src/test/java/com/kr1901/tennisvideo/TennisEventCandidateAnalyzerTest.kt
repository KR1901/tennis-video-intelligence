package com.kr1901.tennisvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TennisEventCandidateAnalyzerTest {
    @Test
    fun detectsSharpBallDirectionTurnAsUnconfirmedCandidate() {
        val samples = listOf(
            observation(0L, 0.1, 0.3, 0.92),
            observation(200L, 0.3, 0.3, 0.94),
            observation(400L, 0.1, 0.3, 0.91)
        )

        val candidates = TennisEventCandidateAnalyzer.deriveRecentCandidates(samples)

        assertEquals(1, candidates.size)
        assertEquals("possible_ball_direction_change", candidates[0].eventType)
        assertEquals("needs_review", candidates[0].reviewStatus)
        assertTrue(candidates[0].confidence in 0.0..1.0)
    }

    @Test
    fun doesNotInferAnEventFromStraightBallMotion() {
        val samples = listOf(
            observation(0L, 0.1, 0.4, 0.92),
            observation(200L, 0.2, 0.4, 0.93),
            observation(400L, 0.3, 0.4, 0.94)
        )
        assertTrue(TennisEventCandidateAnalyzer.deriveRecentCandidates(samples).isEmpty())
    }

    @Test
    fun ignoresLowConfidenceBallObservations() {
        val samples = listOf(
            observation(0L, 0.1, 0.4, 0.2),
            observation(200L, 0.3, 0.4, 0.2),
            observation(400L, 0.1, 0.4, 0.2)
        )
        assertTrue(TennisEventCandidateAnalyzer.deriveRecentCandidates(samples).isEmpty())
    }

    @Test
    fun labelsTurnNearWristAsPossibleRacketContact() {
        val samples = listOf(
            observation(0L, 0.1, 0.3, 0.92),
            observation(200L, 0.3, 0.3, 0.94),
            observation(400L, 0.1, 0.3, 0.91, listOf(0.11 to 0.31))
        )

        val candidates = TennisEventCandidateAnalyzer.deriveRecentCandidates(samples)

        assertEquals(1, candidates.size)
        assertEquals("possible_racket_contact", candidates[0].eventType)
        assertEquals("needs_review", candidates[0].reviewStatus)
    }

    private fun observation(
        time: Long,
        x: Double,
        y: Double,
        confidence: Double,
        wrists: List<Pair<Double, Double>> = emptyList()
    ) = BallTrajectoryObservation(time, x, y, confidence, wrists)
}
