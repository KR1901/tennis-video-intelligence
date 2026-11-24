package com.kr1901.tennisvideo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    private fun directory(context: Context): File =
        File(context.filesDir, "local_projects").apply { mkdirs() }

    private fun projectFile(context: Context, id: String): File =
        File(directory(context), id + ".json")

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
            sampleLogFile(context, id).delete()
        }
    }

    @Synchronized
    fun appendSamples(context: Context, id: String, additions: List<JSONObject>) {
        val project = load(context, id) ?: return
        val lastTimestamp = project.optLong("last_sample_timestamp_ms", -1L)
        val accepted = additions
            .filter { it.optLong("timestamp_ms", -1L) > lastTimestamp }
            .sortedBy { it.optLong("timestamp_ms") }
        if (accepted.isEmpty()) return

        sampleLogFile(context, id).appendText(
            accepted.joinToString(separator = "", postfix = "") { it.toString() + "\\n" }
        )

        val samples = project.optJSONArray("samples") ?: JSONArray()
        accepted.forEach(samples::put)
        val retained = JSONArray()
        val firstRetained = (samples.length() - MAX_RECENT_SAMPLES).coerceAtLeast(0)
        for (index in firstRetained until samples.length()) {
            retained.put(samples.getJSONObject(index))
        }
        project.put("samples", retained)
        project.put("sample_count", project.optInt("sample_count", 0) + accepted.size)
        project.put("last_sample_timestamp_ms", accepted.last().optLong("timestamp_ms"))
        save(context, project)
    }

    @Synchronized
    fun updateProgress(context: Context, id: String, status: String, progressPct: Int, error: String = "") {
        update(context, id) { project ->
            project.put("analysis_status", status)
            project.put("progress_pct", progressPct.coerceIn(0, 100))
            project.put("analysis_error", error)
        }
    }

    @Synchronized
    fun setCourtCorners(context: Context, id: String, points: List<Pair<Float, Float>>) {
        update(context, id) { project ->
            val corners = JSONArray()
            points.take(4).forEach { point ->
                corners.put(JSONObject().put("x", point.first).put("y", point.second))
            }
            project.put("court_corners", corners)
            if (points.isEmpty()) project.put("manual_positions", JSONArray())
        }
    }

    @Synchronized
    fun exportCsv(context: Context, id: String): String {
        val project = load(context, id) ?: error("Project was not found")
        val samples = project.optJSONArray("samples") ?: JSONArray()
        val csv = StringBuilder("timestamp_ms,green_pct,motion_pct,brightness\n")
        for (index in 0 until samples.length()) {
            val sample = samples.optJSONObject(index) ?: continue
            csv.append(sample.optLong("timestamp_ms")).append(',')
                .append(sample.optDouble("green_pct")).append(',')
                .append(sample.optDouble("motion_pct")).append(',')
                .append(sample.optDouble("brightness")).append('\n')
        }
        return csv.toString()
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
    fun exportPositionsCsv(context: Context, id: String): String {
        val project = load(context, id) ?: error("Project was not found")
        val positions = project.optJSONArray("manual_positions") ?: JSONArray()
        val csv = StringBuilder("timestamp_ms,player_end,court_x_m,court_y_m,signed_baseline_distance_m,interpretation\n")
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

    private fun sampleLogFile(context: Context, id: String): File =
        File(directory(context), id + ".samples.ndjson")

    private fun update(context: Context, id: String, change: (JSONObject) -> Unit) {
        val project = load(context, id) ?: return
        change(project)
        save(context, project)
    }

    private fun save(context: Context, project: JSONObject) {
        val file = projectFile(context, project.optString("id"))
        file.writeText(project.toString())
    }

    private const val MAX_RECENT_SAMPLES = 600
    private const val MAX_RECENT_EVENTS = 1_000
}
