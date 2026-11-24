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
            .put("court_corners", JSONArray())
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
        }
    }

    @Synchronized
    fun appendSamples(context: Context, id: String, additions: List<JSONObject>) {
        update(context, id) { project ->
            val samples = project.optJSONArray("samples") ?: JSONArray()
            val knownTimes = HashSet<Long>()
            for (index in 0 until samples.length()) {
                knownTimes.add(samples.optJSONObject(index)?.optLong("timestamp_ms") ?: -1L)
            }
            additions.forEach { sample ->
                val timestamp = sample.optLong("timestamp_ms", -1L)
                if (timestamp >= 0L && !knownTimes.contains(timestamp)) {
                    samples.put(sample)
                    knownTimes.add(timestamp)
                }
            }
            project.put("samples", samples)
        }
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
