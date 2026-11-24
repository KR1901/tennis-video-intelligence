package com.kr1901.tennisvideo

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

internal data class PlayerCourtSummary(
    val playerId: String,
    val observations: Int,
    val behindBaselinePercent: Double,
    val averageSignedBaselineDistanceMeters: Double,
    val sampledMovementMeters: Double
)

internal data class LocationBreakdown(
    val counts: Map<String, Int>,
    val total: Int
) {
    fun percent(label: String): Double =
        if (total == 0) 0.0 else (counts[label] ?: 0) * 100.0 / total
}

internal data class ReturnPositionSummary(
    val playerId: String,
    val serveZone: String,
    val observations: Int,
    val averageSignedBaselineDistanceMeters: Double,
    val behindBaselinePercent: Double
)

internal data class RallyReviewSummary(
    val completedRallies: Int,
    val averageDurationSeconds: Double,
    val averageReviewedStrokes: Double,
    val ralliesWithStrokeLabels: Int
)

internal data class TennisMatchAnalytics(
    val players: List<PlayerCourtSummary>,
    val serveZones: LocationBreakdown,
    val shotDepths: LocationBreakdown,
    val shotDirections: LocationBreakdown,
    val returnPositions: List<ReturnPositionSummary>,
    val rallies: RallyReviewSummary,
    val acceptedEvents: Int,
    val acceptedLocatedEvents: Int,
    val pendingCandidates: Int,
    val calibratedPlayerObservations: Int
) {
    companion object {
        fun empty(): TennisMatchAnalytics = TennisMatchAnalytics(
            players = emptyList(),
            serveZones = LocationBreakdown(emptyMap(), 0),
            shotDepths = LocationBreakdown(emptyMap(), 0),
            shotDirections = LocationBreakdown(emptyMap(), 0),
            returnPositions = emptyList(),
            rallies = RallyReviewSummary(0, 0.0, 0.0, 0),
            acceptedEvents = 0,
            acceptedLocatedEvents = 0,
            pendingCandidates = 0,
            calibratedPlayerObservations = 0
        )
    }
}

/**
 * Aggregates observations and manually reviewed events only.
 *
 * It intentionally does not infer event labels, scores, player identity,
 * or official match statistics. Event placement bins describe where a
 * user-confirmed event was marked on the calibrated court.
 */
internal object TennisMatchAnalyticsCalculator {
    private const val HALF_COURT_WIDTH_M = 5.485
    private const val HALF_COURT_LENGTH_M = 11.885
    private const val MAX_TRACK_GAP_MS = 2_000L
    private const val MAX_SINGLE_STEP_METERS = 8.0
    private const val HALF_SERVICE_WIDTH_M = 4.115
    private const val SERVICE_LINE_DISTANCE_FROM_NET_M = 6.4
    private const val INNER_T_ZONE_M = 1.37
    private const val BODY_ZONE_M = 2.74
    private const val MAX_RETURN_POSITION_WINDOW_MS = 1_200L

    fun calculate(
        samples: Sequence<JSONObject>,
        events: JSONArray
    ): TennisMatchAnalytics {
        val accepted = ArrayList<ReviewedEvent>()
        var pendingCandidates = 0
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            when (event.optString("review_status", "needs_review")) {
                "needs_review" -> pendingCandidates++
                "accepted" -> {
                    val type = normalizeType(event.optString("event_type"))
                    val time = event.optLong("timestamp_ms", -1L)
                    if (type.isNotBlank() && time >= 0L) {
                        val x = event.optDouble("court_x_m", Double.NaN)
                        val y = event.optDouble("court_y_m", Double.NaN)
                        accepted.add(
                            ReviewedEvent(
                                eventId = event.optString("event_id"),
                                timestampMs = time,
                                type = type,
                                x = x,
                                y = y,
                                playerEnd = event.optString("player_end").takeIf { it == "near" || it == "far" },
                                hasCourtLocation = isValidCourtLocation(x, y)
                            )
                        )
                    }
                }
            }
        }
        accepted.sortBy { it.timestampMs }
        val locatedServeEvents = accepted.filter { it.hasCourtLocation && isServeType(it.type) }
        val nearestReturnObservation = HashMap<Pair<String, String>, NearestReturnObservation>()

        val accumulators = linkedMapOf("P1" to PlayerAccumulator("P1"), "P2" to PlayerAccumulator("P2"))
        var calibratedPlayerObservations = 0
        samples.forEach sampleLoop@{ sample ->
            val players = sample.optJSONArray("players") ?: JSONArray()
            val sampleTimestamp = sample.optLong("timestamp_ms", -1L)
            if (sampleTimestamp < 0L) return@sampleLoop
            for (index in 0 until players.length()) {
                val player = players.optJSONObject(index) ?: continue
                val id = player.optString("player_id")
                val accumulator = accumulators[id] ?: continue
                val x = player.optDouble("court_x_m", Double.NaN)
                val y = player.optDouble("court_y_m", Double.NaN)
                val signedBaseline = player.optDouble("baseline_distance_m", Double.NaN)
                if (!isValidCourtObservation(x, y, signedBaseline)) continue
                accumulator.add(sampleTimestamp, x, y, signedBaseline)
                calibratedPlayerObservations++

                val playerEnd = player.optString("player_end").takeIf { it == "near" || it == "far" }
                    ?: if (y >= 0.0) "near" else "far"
                locatedServeEvents.forEach serveLoop@{ serve ->
                    if (serve.playerEnd != playerEnd) return@serveLoop
                    val deltaMs = abs(sampleTimestamp - serve.timestampMs)
                    if (deltaMs > MAX_RETURN_POSITION_WINDOW_MS) return@serveLoop
                    val key = serve.eventId to id
                    val previous = nearestReturnObservation[key]
                    if (previous == null || deltaMs < previous.deltaMs) {
                        nearestReturnObservation[key] = NearestReturnObservation(
                            deltaMs = deltaMs,
                            signedBaselineDistanceMeters = signedBaseline
                        )
                    }
                }
            }
        }

        val serveCounts = linkedMapOf(
            "T" to 0,
            "Body" to 0,
            "Wide" to 0,
            "Outside service box" to 0
        )
        val depthCounts = linkedMapOf("Short" to 0, "Mid-court" to 0, "Deep" to 0)
        val directionCounts = linkedMapOf("Left" to 0, "Centre" to 0, "Right" to 0)
        var locatedEvents = 0

        accepted.filter { it.hasCourtLocation }.forEach { event ->
            locatedEvents++
            if (isServeType(event.type)) {
                val zone = serveZone(event.x, event.y)
                serveCounts[zone] = (serveCounts[zone] ?: 0) + 1
            }
            if (isShotPlacementType(event.type)) {
                val depth = shotDepth(event.y)
                val direction = shotDirection(event.x)
                depthCounts[depth] = (depthCounts[depth] ?: 0) + 1
                directionCounts[direction] = (directionCounts[direction] ?: 0) + 1
            }
        }

        val returnAccumulators = HashMap<Pair<String, String>, ReturnAccumulator>()
        locatedServeEvents.forEach serveLoop@{ serve ->
            val zone = serveZone(serve.x, serve.y)
            listOf("P1", "P2").forEach playerLoop@{ playerId ->
                val nearest = nearestReturnObservation[serve.eventId to playerId] ?: return@playerLoop
                val key = playerId to zone
                returnAccumulators.getOrPut(key) { ReturnAccumulator(playerId, zone) }
                    .add(nearest.signedBaselineDistanceMeters)
            }
        }
        val returnSummaries = returnAccumulators.values
            .map { it.toSummary() }
            .sortedWith(compareBy<ReturnPositionSummary> { it.playerId }.thenBy { it.serveZone })

        return TennisMatchAnalytics(
            players = accumulators.values.filter { it.observations > 0 }.map { it.toSummary() },
            serveZones = LocationBreakdown(serveCounts, serveCounts.values.sum()),
            shotDepths = LocationBreakdown(depthCounts, depthCounts.values.sum()),
            shotDirections = LocationBreakdown(directionCounts, directionCounts.values.sum()),
            returnPositions = returnSummaries,
            rallies = rallySummary(accepted),
            acceptedEvents = accepted.size,
            acceptedLocatedEvents = locatedEvents,
            pendingCandidates = pendingCandidates,
            calibratedPlayerObservations = calibratedPlayerObservations
        )
    }

    private fun isValidCourtObservation(x: Double, y: Double, signedBaseline: Double): Boolean =
        x.isFinite() && y.isFinite() && signedBaseline.isFinite() &&
            abs(x) <= HALF_COURT_WIDTH_M + 2.0 &&
            abs(y) <= HALF_COURT_LENGTH_M + 5.0

    private fun isValidCourtLocation(x: Double, y: Double): Boolean =
        x.isFinite() && y.isFinite() &&
            abs(x) <= HALF_COURT_WIDTH_M + 1.0 &&
            abs(y) <= HALF_COURT_LENGTH_M + 1.0

    private fun normalizeType(value: String): String =
        value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun isServeType(type: String): Boolean =
        type == "serve" || type.startsWith("serve_") || type.endsWith("_serve")

    private fun isShotPlacementType(type: String): Boolean =
        type.contains("forehand") ||
            type.contains("backhand") ||
            type.contains("volley") ||
            type == "return" ||
            type == "shot" ||
            type == "racket_contact" ||
            type == "possible_racket_contact" ||
            type == "contact" ||
            type == "winner" ||
            type == "unforced_error"

    private fun serveZone(x: Double, y: Double): String {
        val inServiceBox = abs(y) in SERVICE_LINE_DISTANCE_FROM_NET_M..HALF_COURT_LENGTH_M &&
            abs(x) <= HALF_SERVICE_WIDTH_M
        if (!inServiceBox) return "Outside service box"
        return when {
            abs(x) <= INNER_T_ZONE_M -> "T"
            abs(x) <= BODY_ZONE_M -> "Body"
            else -> "Wide"
        }
    }

    private fun shotDepth(y: Double): String = when {
        abs(y) < 4.0 -> "Short"
        abs(y) < 8.0 -> "Mid-court"
        else -> "Deep"
    }

    private fun shotDirection(x: Double): String = when {
        x < -1.37 -> "Left"
        x > 1.37 -> "Right"
        else -> "Centre"
    }

    private fun rallySummary(events: List<ReviewedEvent>): RallyReviewSummary {
        val completed = ArrayList<RallyRecord>()
        var start: Long? = null
        var strokes = 0
        var hasStrokeLabel = false

        events.forEach { event ->
            when {
                event.type == "point_start" -> {
                    start = event.timestampMs
                    strokes = 0
                    hasStrokeLabel = false
                }
                start != null && isRallyStrokeType(event.type) -> {
                    strokes++
                    hasStrokeLabel = true
                }
                start != null && event.type == "point_end" -> {
                    val began = start!!
                    if (event.timestampMs >= began) {
                        completed.add(
                            RallyRecord(
                                durationSeconds = (event.timestampMs - began) / 1000.0,
                                strokes = strokes,
                                hasStrokeLabel = hasStrokeLabel
                            )
                        )
                    }
                    start = null
                    strokes = 0
                    hasStrokeLabel = false
                }
            }
        }
        val withLabels = completed.filter { it.hasStrokeLabel }
        return RallyReviewSummary(
            completedRallies = completed.size,
            averageDurationSeconds = completed.map { it.durationSeconds }.averageOrZero(),
            averageReviewedStrokes = withLabels.map { it.strokes.toDouble() }.averageOrZero(),
            ralliesWithStrokeLabels = withLabels.size
        )
    }

    private fun isRallyStrokeType(type: String): Boolean =
        type in setOf("serve", "return", "forehand", "backhand", "volley", "shot", "contact", "racket_contact")

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()

    private data class ReviewedEvent(
        val eventId: String,
        val timestampMs: Long,
        val type: String,
        val x: Double,
        val y: Double,
        val playerEnd: String?,
        val hasCourtLocation: Boolean
    )

    private data class NearestReturnObservation(
        val deltaMs: Long,
        val signedBaselineDistanceMeters: Double
    )

    private class ReturnAccumulator(
        private val playerId: String,
        private val serveZone: String
    ) {
        private var observations = 0
        private var behind = 0
        private var distanceTotal = 0.0

        fun add(signedBaselineDistanceMeters: Double) {
            observations++
            distanceTotal += signedBaselineDistanceMeters
            if (signedBaselineDistanceMeters > 0.0) behind++
        }

        fun toSummary() = ReturnPositionSummary(
            playerId = playerId,
            serveZone = serveZone,
            observations = observations,
            averageSignedBaselineDistanceMeters = distanceTotal / observations,
            behindBaselinePercent = behind * 100.0 / observations
        )
    }

    private data class RallyRecord(
        val durationSeconds: Double,
        val strokes: Int,
        val hasStrokeLabel: Boolean
    )

    private class PlayerAccumulator(private val id: String) {
        var observations = 0
            private set
        private var behind = 0
        private var signedDistanceTotal = 0.0
        private var movement = 0.0
        private var previous: Triple<Long, Double, Double>? = null

        fun add(timestamp: Long, x: Double, y: Double, signedBaseline: Double) {
            observations++
            signedDistanceTotal += signedBaseline
            if (signedBaseline > 0.0) behind++
            val old = previous
            if (old != null) {
                val deltaMs = timestamp - old.first
                if (deltaMs in 1..MAX_TRACK_GAP_MS) {
                    val step = hypot(x - old.second, y - old.third)
                    if (step.isFinite() && step <= MAX_SINGLE_STEP_METERS) movement += step
                }
            }
            previous = Triple(timestamp, x, y)
        }

        fun toSummary(): PlayerCourtSummary = PlayerCourtSummary(
            playerId = id,
            observations = observations,
            behindBaselinePercent = if (observations == 0) 0.0 else behind * 100.0 / observations,
            averageSignedBaselineDistanceMeters = if (observations == 0) 0.0 else signedDistanceTotal / observations,
            sampledMovementMeters = movement
        )
    }
}
