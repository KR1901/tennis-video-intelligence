package com.kr1901.tennisvideo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TennisMatchAnalyticsTest {
    @Test
    fun playerBaselineSummaryUsesCalibratedFootObservations() {
        val analytics = TennisMatchAnalyticsCalculator.calculate(
            sequenceOf(
                sample(0L, player("P1", 0.4, 12.5, 0.615), player("P2", -0.5, -12.5, 0.615)),
                sample(400L, player("P1", 0.5, 11.5, -0.385), player("P2", -0.4, -11.5, -0.385))
            ),
            JSONArray()
        )

        val p1 = analytics.players.first { it.playerId == "P1" }
        assertEquals(2, p1.observations)
        assertEquals(50.0, p1.behindBaselinePercent, 0.001)
        assertEquals(0.115, p1.averageSignedBaselineDistanceMeters, 0.001)
        assertEquals(1.005, p1.sampledMovementMeters, 0.01)
    }

    @Test
    fun servePlacementBinsCountOnlyAcceptedLocatedServeEvents() {
        val events = JSONArray()
            .put(event(100L, "serve", "accepted", 0.5, 9.0))
            .put(event(200L, "serve", "accepted", 2.0, -9.0))
            .put(event(300L, "serve", "accepted", 3.5, 9.0))
            .put(event(400L, "serve", "needs_review", 0.5, 9.0))

        val result = TennisMatchAnalyticsCalculator.calculate(emptySequence(), events)

        assertEquals(3, result.serveZones.total)
        assertEquals(1, result.serveZones.counts["T"])
        assertEquals(1, result.serveZones.counts["Body"])
        assertEquals(1, result.serveZones.counts["Wide"])
        assertEquals(1, result.pendingCandidates)
    }

    @Test
    fun returnerPositionUsesClosestTrackOnServeTargetHalf() {
        val events = JSONArray()
            .put(event(1_000L, "serve", "accepted", 0.5, 9.0))
        val samples = sequenceOf(
            sample(
                900L,
                player("P1", 0.4, 10.0, -1.885),
                player("P2", -0.5, -10.0, -1.885)
            )
        )

        val result = TennisMatchAnalyticsCalculator.calculate(samples, events)

        assertEquals(1, result.returnPositions.size)
        val returner = result.returnPositions.single()
        assertEquals("P1", returner.playerId)
        assertEquals("T", returner.serveZone)
        assertEquals(1, returner.observations)
        assertEquals(-1.885, returner.averageSignedBaselineDistanceMeters, 0.001)
    }

    @Test
    fun shotPlacementBinsDescribeCourtLocation() {
        val events = JSONArray()
            .put(event(100L, "forehand", "accepted", -3.0, 2.5))
            .put(event(200L, "backhand", "accepted", 0.0, 6.0))
            .put(event(300L, "volley", "accepted", 3.0, 10.0))
            .put(event(400L, "forehand", "rejected", 3.0, 10.0))

        val result = TennisMatchAnalyticsCalculator.calculate(emptySequence(), events)

        assertEquals(3, result.shotDepths.total)
        assertEquals(1, result.shotDepths.counts["Short"])
        assertEquals(1, result.shotDepths.counts["Mid-court"])
        assertEquals(1, result.shotDepths.counts["Deep"])
        assertEquals(1, result.shotDirections.counts["Left"])
        assertEquals(1, result.shotDirections.counts["Centre"])
        assertEquals(1, result.shotDirections.counts["Right"])
    }

    @Test
    fun rallyMetricsRequireReviewedPointBoundariesAndCountManualStrokes() {
        val events = JSONArray()
            .put(event(0L, "point_start", "accepted"))
            .put(event(1_000L, "serve", "accepted"))
            .put(event(1_500L, "forehand", "accepted"))
            .put(event(2_000L, "backhand", "accepted"))
            .put(event(3_000L, "point_end", "accepted"))
            .put(event(4_000L, "forehand", "accepted"))
            .put(event(5_000L, "point_start", "needs_review"))
            .put(event(6_000L, "point_end", "accepted"))

        val result = TennisMatchAnalyticsCalculator.calculate(emptySequence(), events)

        assertEquals(1, result.rallies.completedRallies)
        assertEquals(3.0, result.rallies.averageDurationSeconds, 0.001)
        assertEquals(3.0, result.rallies.averageReviewedStrokes, 0.001)
        assertEquals(1, result.rallies.ralliesWithStrokeLabels)
    }

    @Test
    fun observationsOutsideAllowedCourtEnvelopeAreIgnored() {
        val result = TennisMatchAnalyticsCalculator.calculate(
            sequenceOf(sample(0L, player("P1", 0.0, 100.0, 88.115))),
            JSONArray()
        )

        assertTrue(result.players.isEmpty())
        assertEquals(0, result.calibratedPlayerObservations)
    }

    private fun sample(timestamp: Long, vararg players: JSONObject): JSONObject =
        JSONObject()
            .put("timestamp_ms", timestamp)
            .put("players", JSONArray().apply { players.forEach(::put) })

    private fun player(id: String, x: Double, y: Double, baseline: Double): JSONObject =
        JSONObject()
            .put("player_id", id)
            .put("court_x_m", x)
            .put("court_y_m", y)
            .put("baseline_distance_m", baseline)

    private fun event(
        timestamp: Long,
        type: String,
        status: String,
        x: Double = Double.NaN,
        y: Double = Double.NaN
    ): JSONObject = JSONObject()
        .put("timestamp_ms", timestamp)
        .put("event_type", type)
        .put("review_status", status)
        .apply {
            if (x.isFinite() && y.isFinite()) {
                put("court_x_m", x)
                put("court_y_m", y)
                put("player_end", if (y >= 0.0) "near" else "far")
            }
        }
}
