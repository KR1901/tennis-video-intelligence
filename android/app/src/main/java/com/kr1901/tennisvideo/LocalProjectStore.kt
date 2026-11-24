package com.kr1901.tennisvideo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

internal data class LocalVideoMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val mimeType: String
)

internal object LocalProjectStore {
    private const val PREFS = "tvi_local_projects"
    private const val LAST_PROJECT_ID = "last_project_id"
    private const val MAX_RECENT_SAMPLES = 600
    private const val MAX_RECENT_EVENTS = 1_000

    private fun directory(context: Context): File =
        File(context.filesDir, "local_projects").apply { mkdirs() }

    private fun projectFile(context: Context, id: String): File =
        File(directory(context), id + ".json")

    private fun sampleLogFile(context: Context, id: String): File =
        File(directory(context), id + ".samples.ndjson")

    @Synchronized
    fun create(context: Context, uri: Uri, metadata: LocalVideoMetadata): JSONObject {
        val id = UUID.randomUUID().toString()
        val project = JSONObject()
            .put("id", id)
            .put("display_name", metadata.displayName)
            .put("content_uri", uri.toString())
            .put("size_bytes", metadata.sizeBytes)
            .put("duration_ms", metadata.durationMs)
            .put("width", metadata.width)
            .put("height", metadata.height)
            .put("rotation", metadata.rotation)
            .put("mime_type", metadata.mimeType)
            .put("created_at_ms", System.currentTimeMillis())
            .put("analysis_status", "not_started")
            .put("progress_pct", 0)
            .put("samples", JSONArray())
            .put("sample_count", 0)
            .put("last_sample_timestamp_ms", -1L)
            .put("events", JSONArray())
            .put("court_corners", JSONArray())
            .put("manual_positions", JSONArray())
        save(context, project)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LAST_PROJECT_ID, id).apply()
        return project
    }

    @Synchronized
    fun listProjects(context: Context): List<JSONObject> {
        return directory(context).listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            ?.toList()
            .orEmpty()
    }

    @Synchronized
    fun storageUsageBytes(context: Context): Long {
        fun totalBytes(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf(::totalBytes) ?: 0L
        }
        return totalBytes(directory(context)) +
            totalBytes(File(context.filesDir, "remote_videos"))
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val project = load(context, id) ?: return false
        val sourceUri = runCatching { Uri.parse(project.optString("content_uri")) }.getOrNull()
        if (sourceUri?.scheme.equals("file", ignoreCase = true)) {
            val sourceFile = sourceUri?.path?.let(::File)
            val privateDownloads = File(context.filesDir, "remote_videos").canonicalFile
            if (sourceFile != null) {
                val canonical = sourceFile.canonicalFile
                if (canonical.path.startsWith(privateDownloads.path + File.separator)) {
                    canonical.delete()
                }
            }
        }
        val removedProject = projectFile(context, id).delete()
        sampleLogFile(context, id).delete()
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_PROJECT_ID, null) == id) {
            preferences.edit().remove(LAST_PROJECT_ID).apply()
        }
        return removedProject
    }

    @Synchronized
    fun load(context: Context, id: String): JSONObject? {
        val file = projectFile(context, id)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    @Synchronized
    fun loadLast(context: Context): JSONObject? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_PROJECT_ID, null) ?: return null
        return load(context, id)
    }

    @Synchronized
    fun prepareAnalysis(context: Context, id: String) {
        update(context, id) { project ->
            project.put("analysis_status", "queued")
            project.put("analysis_error", "")
            project.put("progress_pct", 0)
            project.put("samples", JSONArray())
            project.put("sample_count", 0)
            project.put("last_sample_timestamp_ms", -1L)
            project.put("events", JSONArray())
        }
        sampleLogFile(context, id).delete()
    }

    /**
     * Append the full analysis stream to NDJSON, retaining only a bounded tail in project JSON.
     * This avoids rewriting a multi-hour timeline on every WorkManager chunk.
     */
    @Synchronized
    fun appendSamples(context: Context, id: String, additions: List<JSONObject>) {
        val project = load(context, id) ?: return
        val lastTimestamp = project.optLong("last_sample_timestamp_ms", -1L)
        val accepted = additions
            .filter { it.optLong("timestamp_ms", -1L) > lastTimestamp }
            .sortedBy { it.optLong("timestamp_ms") }
        if (accepted.isEmpty()) return

        sampleLogFile(context, id).appendText(
            accepted.joinToString(separator = "", postfix = "") { it.toString() + "\n" }
        )

        val combined = project.optJSONArray("samples") ?: JSONArray()
        accepted.forEach { combined.put(it) }
        val retained = JSONArray()
        val firstRetained = (combined.length() - MAX_RECENT_SAMPLES).coerceAtLeast(0)
        for (index in firstRetained until combined.length()) {
            retained.put(combined.getJSONObject(index))
        }
        project.put("samples", retained)
        project.put("sample_count", project.optInt("sample_count", 0) + accepted.size)
        project.put("last_sample_timestamp_ms", accepted.last().optLong("timestamp_ms"))
        save(context, project)
    }

    @Synchronized
    fun updateProgress(
        context: Context,
        id: String,
        status: String,
        progressPct: Int,
        error: String = ""
    ) {
        update(context, id) { project ->
            project.put("analysis_status", status)
            project.put("progress_pct", progressPct.coerceIn(0, 100))
            project.put("analysis_error", error)
        }
    }

    @Synchronized
    fun setCourtCorners(context: Context, id: String, points: List<Pair<Float, Float>>) {
        val project = load(context, id) ?: return
        val corners = JSONArray()
        points.take(4).forEach { point ->
            corners.put(JSONObject().put("x", point.first).put("y", point.second))
        }
        project.put("court_corners", corners)
        if (points.isEmpty()) project.put("manual_positions", JSONArray())

        val validCorners = points.takeIf { it.size == 4 }
        if (validCorners != null) {
            val manualPositions = project.optJSONArray("manual_positions") ?: JSONArray()
            for (index in 0 until manualPositions.length()) {
                val position = manualPositions.optJSONObject(index) ?: continue
                val measurement = CourtProjector.project(
                    validCorners,
                    position.optDouble("image_x").toFloat(),
                    position.optDouble("image_y").toFloat()
                )
                applyMeasurement(position, measurement)
            }
            project.put("manual_positions", manualPositions)
        }
        val events = project.optJSONArray("events") ?: JSONArray()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val imageX = event.optDouble("image_x", event.optDouble("ball_x", Double.NaN))
            val imageY = event.optDouble("image_y", event.optDouble("ball_y", Double.NaN))
            when {
                points.isEmpty() -> applyMeasurement(event, null)
                validCorners != null && imageX.isFinite() && imageY.isFinite() -> {
                    applyMeasurement(
                        event,
                        CourtProjector.project(validCorners, imageX.toFloat(), imageY.toFloat())
                    )
                }
                // Preserve legacy/manual event coordinates when no raw image point
                // was stored, because they cannot be safely recalculated.
            }
        }
        project.put("events", events)

        if (validCorners != null || points.isEmpty()) {
            val log = sampleLogFile(context, id)
            if (log.isFile) {
                val temporary = File(directory(context), id + ".samples.ndjson.tmp")
                temporary.bufferedWriter().use { writer ->
                    log.forEachLine { line ->
                        val sample = runCatching { JSONObject(line) }.getOrNull()
                        if (sample != null) {
                            applyCourtCalibration(sample, validCorners)
                            writer.append(sample.toString()).append('\n')
                        } else {
                            writer.append(line).append('\n')
                        }
                    }
                }
                if (!temporary.renameTo(log)) {
                    log.delete()
                    if (!temporary.renameTo(log)) {
                        temporary.delete()
                        error("Could not save the recalibrated sample timeline.")
                    }
                }
            }
            val samples = project.optJSONArray("samples") ?: JSONArray()
            for (index in 0 until samples.length()) {
                val sample = samples.optJSONObject(index) ?: continue
                applyCourtCalibration(sample, validCorners)
            }
            project.put("samples", samples)
        }
        save(context, project)
    }

    private fun applyCourtCalibration(
        sample: JSONObject,
        corners: List<Pair<Float, Float>>?
    ) {
        val players = sample.optJSONArray("players") ?: JSONArray()
        for (index in 0 until players.length()) {
            val player = players.optJSONObject(index) ?: continue
            val measurement = if (corners != null &&
                player.has("foot_x") && player.has("foot_y")
            ) {
                CourtProjector.project(
                    corners,
                    player.optDouble("foot_x").toFloat(),
                    player.optDouble("foot_y").toFloat()
                )
            } else {
                null
            }
            applyMeasurement(player, measurement)
        }

        val ball = sample.optJSONObject("ball")
        if (ball != null) {
            val measurement = if (corners != null && ball.optBoolean("detected", false)) {
                CourtProjector.project(
                    corners,
                    ball.optDouble("x").toFloat(),
                    ball.optDouble("y").toFloat()
                )
            } else {
                null
            }
            applyMeasurement(ball, measurement)
        }
    }

    private fun applyMeasurement(
        target: JSONObject,
        measurement: CourtPositionMeasurement?
    ) {
        if (measurement == null) {
            target.remove("court_x_m")
            target.remove("court_y_m")
            target.remove("player_end")
            target.remove("baseline_distance_m")
            return
        }
        target.put("court_x_m", measurement.xMeters)
            .put("court_y_m", measurement.yMeters)
            .put("player_end", measurement.playerEnd)
            .put("baseline_distance_m", measurement.baselineDistanceMeters)
    }

    @Synchronized
    fun addManualPosition(
        context: Context,
        id: String,
        timestampMs: Long,
        imageX: Float,
        imageY: Float,
        measurement: CourtPositionMeasurement
    ) {
        update(context, id) { project ->
            val positions = project.optJSONArray("manual_positions") ?: JSONArray()
            positions.put(
                JSONObject()
                    .put("timestamp_ms", timestampMs)
                    .put("image_x", imageX)
                    .put("image_y", imageY)
                    .put("court_x_m", measurement.xMeters)
                    .put("court_y_m", measurement.yMeters)
                    .put("player_end", measurement.playerEnd)
                    .put("baseline_distance_m", measurement.baselineDistanceMeters)
            )
            project.put("manual_positions", positions)
        }
    }

    @Synchronized
    fun lastPlayerTracks(
        context: Context,
        id: String,
        beforeTimestampMs: Long = Long.MAX_VALUE
    ): List<PlayerTrackState> {
        val samples = load(context, id)?.optJSONArray("samples") ?: return emptyList()
        for (index in samples.length() - 1 downTo 0) {
            val sample = samples.optJSONObject(index) ?: continue
            if (sample.optLong("timestamp_ms", Long.MAX_VALUE) >= beforeTimestampMs) continue
            val players = sample.optJSONArray("players") ?: continue
            val tracks = ArrayList<PlayerTrackState>()
            for (playerIndex in 0 until players.length()) {
                val player = players.optJSONObject(playerIndex) ?: continue
                val playerId = player.optString("player_id")
                if (playerId.isNotBlank() && player.has("foot_x") && player.has("foot_y")) {
                    tracks.add(
                        PlayerTrackState(
                            playerId,
                            player.optDouble("foot_x").toFloat(),
                            player.optDouble("foot_y").toFloat()
                        )
                    )
                }
            }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    @Synchronized
    fun appendEventCandidates(context: Context, id: String, additions: List<JSONObject>) {
        update(context, id) { project ->
            val events = project.optJSONArray("events") ?: JSONArray()
            val cornersJson = project.optJSONArray("court_corners") ?: JSONArray()
            val corners = if (cornersJson.length() == 4) {
                (0 until 4).mapNotNull { index ->
                    val point = cornersJson.optJSONObject(index) ?: return@mapNotNull null
                    point.optDouble("x").toFloat() to point.optDouble("y").toFloat()
                }.takeIf { it.size == 4 }
            } else {
                null
            }
            val knownIds = HashSet<String>()
            for (index in 0 until events.length()) {
                knownIds.add(events.optJSONObject(index)?.optString("event_id") ?: "")
            }
            additions.forEach { event ->
                val eventId = event.optString("event_id")
                if (eventId.isNotBlank() && eventId !in knownIds) {
                    if (corners != null) {
                        val imageX = event.optDouble("image_x", event.optDouble("ball_x", Double.NaN))
                        val imageY = event.optDouble("image_y", event.optDouble("ball_y", Double.NaN))
                        if (imageX.isFinite() && imageY.isFinite()) {
                            applyMeasurement(
                                event,
                                CourtProjector.project(corners, imageX.toFloat(), imageY.toFloat())
                            )
                        }
                    }
                    events.put(event)
                    knownIds.add(eventId)
                }
            }
            val retained = JSONArray()
            val firstRetained = (events.length() - MAX_RECENT_EVENTS).coerceAtLeast(0)
            for (index in firstRetained until events.length()) {
                retained.put(events.getJSONObject(index))
            }
            project.put("events", retained)
        }
    }

    @Synchronized
    fun reviewEvent(
        context: Context,
        id: String,
        eventId: String,
        accepted: Boolean,
        eventType: String? = null
    ) {
        update(context, id) { project ->
            val events = project.optJSONArray("events") ?: JSONArray()
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                if (event.optString("event_id") == eventId) {
                    event.put("review_status", if (accepted) "accepted" else "rejected")
                    if (accepted && !eventType.isNullOrBlank()) {
                        event.put("event_type", eventType)
                    }
                    break
                }
            }
            project.put("events", events)
        }
    }

    @Synchronized
    fun addManualEvent(
        context: Context,
        id: String,
        timestampMs: Long,
        eventType: String,
        note: String,
        measurement: CourtPositionMeasurement? = null,
        imageX: Float? = null,
        imageY: Float? = null
    ) {
        update(context, id) { project ->
            val events = project.optJSONArray("events") ?: JSONArray()
            events.put(
                JSONObject()
                    .put("event_id", "manual-" + UUID.randomUUID())
                    .put("timestamp_ms", timestampMs)
                    .put("event_type", eventType)
                    .put("source", "manual_user_entry")
                    .put("confidence", 1.0)
                    .put("review_status", "accepted")
                    .put("note", note)
                    .apply {
                        if (imageX != null && imageY != null) {
                            put("image_x", imageX)
                            put("image_y", imageY)
                        }
                        if (measurement != null) {
                            put("court_x_m", measurement.xMeters)
                            put("court_y_m", measurement.yMeters)
                            put("player_end", measurement.playerEnd)
                            put("baseline_distance_m", measurement.baselineDistanceMeters)
                        }
                    }
            )
            project.put("events", events)
        }
    }

    /**
     * Computes review-backed metrics from the complete NDJSON timeline, not just
     * the 600-row recent window kept in the project JSON for fast UI refreshes.
     */
    @Synchronized
    fun calculateAnalytics(context: Context, id: String): TennisMatchAnalytics? {
        val project = load(context, id) ?: return null
        val events = project.optJSONArray("events") ?: JSONArray()
        val log = sampleLogFile(context, id)
        if (log.isFile) {
            return log.useLines { lines ->
                TennisMatchAnalyticsCalculator.calculate(
                    lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() },
                    events
                )
            }
        }
        val samples = project.optJSONArray("samples") ?: JSONArray()
        return TennisMatchAnalyticsCalculator.calculate(
            (0 until samples.length()).asSequence().mapNotNull { samples.optJSONObject(it) },
            events
        )
    }

    /**
     * Builds a bounded, whole-match report snapshot by evenly sampling the
     * persistent NDJSON log. The full source log is never loaded into memory.
     */
    @Synchronized
    fun loadReportSnapshot(
        context: Context,
        id: String,
        maxSamples: Int = 1_800
    ): JSONObject? {
        val project = load(context, id) ?: return null
        val reportProject = JSONObject(project.toString())
        calculateAnalytics(context, id)?.let { analytics ->
            reportProject.put("match_analytics", analyticsToJson(analytics))
        }
        val log = sampleLogFile(context, id)
        if (!log.isFile || maxSamples <= 0) return reportProject

        val expectedCount = project.optInt("sample_count", 0).coerceAtLeast(0)
        if (expectedCount <= maxSamples) {
            val complete = JSONArray()
            log.forEachLine { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let(complete::put)
            }
            return reportProject.put("samples", complete)
        }

        val stride = kotlin.math.ceil(expectedCount.toDouble() / maxSamples.toDouble())
            .toInt().coerceAtLeast(1)
        val selected = JSONArray()
        var index = 0
        var lastSample: JSONObject? = null
        log.forEachLine { line ->
            val sample = runCatching { JSONObject(line) }.getOrNull()
            if (sample != null) {
                lastSample = sample
                if (index % stride == 0 && selected.length() < maxSamples) {
                    selected.put(sample)
                }
                index++
            }
        }
        if (lastSample != null) {
            val lastTime = lastSample!!.optLong("timestamp_ms")
            val alreadyLast = selected.length() > 0 &&
                selected.optJSONObject(selected.length() - 1)?.optLong("timestamp_ms") == lastTime
            if (!alreadyLast) selected.put(lastSample)
        }
        return reportProject
            .put("samples", selected)
            .put("report_sample_count", index)
    }

    private fun analyticsToJson(analytics: TennisMatchAnalytics): JSONObject {
        fun countMap(counts: Map<String, Int>): JSONObject =
            JSONObject().apply { counts.forEach { (key, value) -> put(key, value) } }

        val players = JSONArray()
        analytics.players.forEach { player ->
            players.put(
                JSONObject()
                    .put("player_id", player.playerId)
                    .put("observations", player.observations)
                    .put("behind_baseline_pct", player.behindBaselinePercent)
                    .put("mean_signed_baseline_m", player.averageSignedBaselineDistanceMeters)
                    .put("sampled_movement_m", player.sampledMovementMeters)
            )
        }
        val returnPositions = JSONArray()
        analytics.returnPositions.forEach { position ->
            returnPositions.put(
                JSONObject()
                    .put("player_id", position.playerId)
                    .put("serve_zone", position.serveZone)
                    .put("observations", position.observations)
                    .put("mean_signed_baseline_m", position.averageSignedBaselineDistanceMeters)
                    .put("behind_baseline_pct", position.behindBaselinePercent)
            )
        }
        return JSONObject()
            .put("players", players)
            .put("return_positions", returnPositions)
            .put("serve_zones", countMap(analytics.serveZones.counts))
            .put("serve_zone_total", analytics.serveZones.total)
            .put("shot_depths", countMap(analytics.shotDepths.counts))
            .put("shot_depth_total", analytics.shotDepths.total)
            .put("shot_directions", countMap(analytics.shotDirections.counts))
            .put("shot_direction_total", analytics.shotDirections.total)
            .put("rallies_completed", analytics.rallies.completedRallies)
            .put("rally_avg_duration_s", analytics.rallies.averageDurationSeconds)
            .put("rally_avg_reviewed_strokes", analytics.rallies.averageReviewedStrokes)
            .put("accepted_events", analytics.acceptedEvents)
            .put("accepted_located_events", analytics.acceptedLocatedEvents)
            .put("pending_candidates", analytics.pendingCandidates)
            .put("calibrated_player_observations", analytics.calibratedPlayerObservations)
    }

    @Synchronized
    fun exportCsv(context: Context, id: String): String {
        if (load(context, id) == null) error("Project was not found")
        val csv = StringBuilder(
            "timestamp_ms,green_pct,motion_pct,brightness,ball_x,ball_y,ball_confidence,players_json\n"
        )
        val file = sampleLogFile(context, id)
        if (file.isFile) {
            file.forEachLine { line ->
                val sample = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val ball = sample.optJSONObject("ball")
                val playersJson = sample.optJSONArray("players")?.toString() ?: "[]"
                csv.append(sample.optLong("timestamp_ms")).append(',')
                    .append(sample.optDouble("green_pct")).append(',')
                    .append(sample.optDouble("motion_pct")).append(',')
                    .append(sample.optDouble("brightness")).append(',')
                    .append(ball?.optDouble("x") ?: "").append(',')
                    .append(ball?.optDouble("y") ?: "").append(',')
                    .append(ball?.optDouble("confidence") ?: "").append(',')
                    .append('"').append(playersJson.replace("\"", "\"\"")).append('"')
                    .append('\n')
            }
        }
        return csv.toString()
    }

    @Synchronized
    fun exportEventsCsv(context: Context, id: String): String {
        val events = load(context, id)?.optJSONArray("events") ?: error("Project was not found")
        val csv = StringBuilder(
            "timestamp_ms,event_type,confidence,review_status,source,player_end,court_x_m,court_y_m,signed_baseline_distance_m,image_x,image_y,note\n"
        )
        fun field(value: String): String =
            "\"" + value.replace("\"", "\"\"") + "\""
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            csv.append(event.optLong("timestamp_ms")).append(',')
                .append(field(event.optString("event_type"))).append(',')
                .append(event.optDouble("confidence")).append(',')
                .append(field(event.optString("review_status"))).append(',')
                .append(field(event.optString("source"))).append(',')
                .append(field(event.optString("player_end"))).append(',')
                .append(if (event.has("court_x_m")) event.optDouble("court_x_m") else "").append(',')
                .append(if (event.has("court_y_m")) event.optDouble("court_y_m") else "").append(',')
                .append(if (event.has("baseline_distance_m")) event.optDouble("baseline_distance_m") else "").append(',')
                .append(if (event.has("image_x")) event.optDouble("image_x") else "").append(',')
                .append(if (event.has("image_y")) event.optDouble("image_y") else "").append(',')
                .append(field(event.optString("note")))
                .append('\n')
        }
        return csv.toString()
    }

    @Synchronized
    fun exportMetricsCsv(context: Context, id: String): String {
        val analytics = calculateAnalytics(context, id) ?: error("Project was not found")
        val csv = StringBuilder("category,metric,value,unit,observations,definition\n")
        fun row(category: String, metric: String, value: String, unit: String, observations: Int, definition: String) {
            csv.append(csvField(category)).append(',')
                .append(csvField(metric)).append(',')
                .append(csvField(value)).append(',')
                .append(csvField(unit)).append(',')
                .append(observations).append(',')
                .append(csvField(definition)).append('\n')
        }
        analytics.players.forEach { player ->
            row("Player position", player.playerId + " behind baseline", "%.1f".format(Locale.US, player.behindBaselinePercent), "%", player.observations,
                "Percent of calibrated pose observations whose signed baseline distance is zero or positive.")
            row("Player position", player.playerId + " mean signed baseline distance", "%.3f".format(Locale.US, player.averageSignedBaselineDistanceMeters), "m", player.observations,
                "Positive means behind the baseline; negative means inside the court.")
            row("Player movement", player.playerId + " sampled path length", "%.2f".format(Locale.US, player.sampledMovementMeters), "m", player.observations,
                "Sum of consecutive sampled foot-point displacements, excluding long time gaps and implausibly large jumps.")
        }
        analytics.serveZones.counts.forEach { (zone, count) ->
            row("Serve placement", zone, count.toString(), "accepted events", analytics.serveZones.total,
                "Manually reviewed serve locations binned by their calibrated court x position; outside-box points are reported separately.")
        }
        analytics.returnPositions.forEach { position ->
            row(
                "Return position",
                position.playerId + " after " + position.serveZone + " serve",
                String.format(Locale.US, "%+.3f", position.averageSignedBaselineDistanceMeters),
                "m signed baseline distance",
                position.observations,
                "Closest calibrated pose on the serve target half within 1.2 seconds of an accepted located serve marker. P1/P2 labels are temporary and may swap."
            )
        }
        analytics.shotDepths.counts.forEach { (depth, count) ->
            row("Shot placement", depth, count.toString(), "accepted events", analytics.shotDepths.total,
                "Manually reviewed shot/contact locations binned by distance from the net.")
        }
        analytics.shotDirections.counts.forEach { (direction, count) ->
            row("Shot placement", direction, count.toString(), "accepted events", analytics.shotDirections.total,
                "Left/centre/right is relative to the calibrated court x-axis, not the player's facing direction.")
        }
        row("Rally review", "Completed point start/end pairs", analytics.rallies.completedRallies.toString(), "rallies", analytics.rallies.completedRallies,
            "Counts only accepted manual point_start and point_end markers.")
        row("Rally review", "Average rally duration", "%.2f".format(Locale.US, analytics.rallies.averageDurationSeconds), "seconds", analytics.rallies.completedRallies,
            "Mean time between accepted point_start and point_end markers.")
        row("Rally review", "Average labelled strokes", "%.2f".format(Locale.US, analytics.rallies.averageReviewedStrokes), "strokes", analytics.rallies.ralliesWithStrokeLabels,
            "Mean manually accepted serve/return/forehand/backhand/volley/shot/contact labels between point boundaries.")
        row("Review coverage", "Accepted event labels", analytics.acceptedEvents.toString(), "events", analytics.acceptedEvents,
            "Accepted manual or reviewed candidates; unreviewed candidates are excluded from placement counts.")
        row("Review coverage", "Accepted located event labels", analytics.acceptedLocatedEvents.toString(), "events", analytics.acceptedEvents,
            "Accepted event labels that have valid court x/y coordinates.")
        row("Review coverage", "Awaiting review", analytics.pendingCandidates.toString(), "events", analytics.pendingCandidates,
            "Model-generated candidates that have not been accepted or rejected.")
        return csv.toString()
    }

    private fun csvField(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    @Synchronized
    fun exportPositionsCsv(context: Context, id: String): String {
        val positions = load(context, id)?.optJSONArray("manual_positions") ?: error("Project was not found")
        val csv = StringBuilder(
            "timestamp_ms,player_end,court_x_m,court_y_m,signed_baseline_distance_m,interpretation\n"
        )
        for (index in 0 until positions.length()) {
            val position = positions.optJSONObject(index) ?: continue
            val distance = position.optDouble("baseline_distance_m")
            val interpretation = if (distance >= 0.0) "behind_baseline" else "inside_court"
            csv.append(position.optLong("timestamp_ms")).append(',')
                .append(position.optString("player_end")).append(',')
                .append(position.optDouble("court_x_m")).append(',')
                .append(position.optDouble("court_y_m")).append(',')
                .append(distance).append(',')
                .append(interpretation).append('\n')
        }
        return csv.toString()
    }

    private fun update(context: Context, id: String, change: (JSONObject) -> Unit) {
        val project = load(context, id) ?: return
        change(project)
        save(context, project)
    }

    private fun save(context: Context, project: JSONObject) {
        val file = projectFile(context, project.optString("id"))
        file.writeText(project.toString())
    }
}
