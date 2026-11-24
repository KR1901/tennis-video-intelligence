package com.kr1901.tennisvideo

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kr1901.tennisvideo.data.VideoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

private val CourtGreen = Color(0xFF16A085)
private val Ink = Color(0xFF17222D)
private val Muted = Color(0xFF64717C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = CourtGreen,
                    onPrimary = Color.White,
                    secondary = Ink,
                    background = Color(0xFFF5F7F8),
                    surface = Color.White,
                    onSurface = Ink,
                )
            ) {
                TennisVideoApp()
            }
        }
    }
}

@Composable
private fun TennisVideoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiBaseUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedName by remember { mutableStateOf("No video selected") }
    var videoId by remember { mutableStateOf<String?>(null) }
    var calibrationId by remember { mutableStateOf<String?>(null) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var timestampText by remember { mutableStateOf("10.0") }
    var playerEnd by remember { mutableStateOf("near") }
    var selectedPlayerPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var mediaSummary by remember { mutableStateOf("Video metadata has not been inspected.") }
    var measurementSummary by remember { mutableStateOf("No position measured yet.") }
    var statusMessage by remember { mutableStateOf("Connect to the backend to begin.") }
    var busy by remember { mutableStateOf(false) }
    val calibrationPoints = remember { mutableStateListOf<Pair<Float, Float>>() }
    var analysisId by remember { mutableStateOf<String?>(null) }
    var analysisStatus by remember { mutableStateOf("Not started") }
    var analysisProgress by remember { mutableStateOf(0) }
    var analysisSummary by remember { mutableStateOf<JSONObject?>(null) }
    var reportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val eventCandidates = remember { mutableStateListOf<JSONObject>() }

    val reportSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/svg+xml"),
        onResult = { uri ->
            val bytes = reportBytes
            if (uri != null && bytes != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    statusMessage = "SVG report saved."
                } catch (error: Exception) {
                    statusMessage = "Could not save SVG report: ${error.message}"
                }
            }
        }
    )

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                selectedUri = uri
                analysisId = null
                analysisSummary = null
                eventCandidates.clear()
                analysisStatus = "Not started"
                selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
                videoId = null
                calibrationId = null
                frameBitmap = null
                calibrationPoints.clear()
                selectedPlayerPoint = null
                analysisId = null
                analysisSummary = null
                eventCandidates.clear()
                analysisStatus = "Not started"
                statusMessage = "Video selected. Upload it to start."
            }
        }
    )

    fun api(): VideoApi = VideoApi(apiBaseUrl, context.contentResolver)

    fun refreshAnalysis() {
        val id = analysisId ?: return
        scope.launch {
            busy = true
            statusMessage = "Refreshing analysis..."
            try {
                val response = withContext(Dispatchers.IO) { api().getAnalysis(id) }
                analysisStatus = response.optString("status", "unknown")
                analysisProgress = response.optInt("progress_pct", 0)
                analysisSummary = response.optJSONObject("summary")
                if (analysisStatus == "completed") {
                    val eventResponse = withContext(Dispatchers.IO) { api().getAnalysisEvents(id) }
                    eventCandidates.clear()
                    val array = eventResponse.optJSONArray("events") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let(eventCandidates::add)
                    }
                    statusMessage = "Analysis complete. Review event candidates before using event metrics."
                } else if (analysisStatus == "failed") {
                    statusMessage = "Analysis failed: ${response.optString("error", "Unknown error")}"
                } else {
                    statusMessage = "Analysis ${analysisStatus.lowercase()} · $analysisProgress%"
                }
            } catch (error: Exception) {
                statusMessage = "Analysis refresh failed: ${error.message}"
            } finally {
                busy = false
            }
        }
    }



    fun reviewCandidate(event: JSONObject, eventType: String?) {
        val id = analysisId ?: return
        val eventId = event.optString("event_id")
        if (eventId.isBlank()) return
        scope.launch {
            busy = true
            statusMessage = "Saving event review..."
            try {
                withContext(Dispatchers.IO) {
                    api().reviewEvent(
                        id,
                        eventId,
                        eventType,
                        accepted = eventType != null,
                        note = "Reviewed by the Android user."
                    )
                }
                refreshAnalysis()
            } catch (error: Exception) {
                statusMessage = "Event review failed: ${error.message}"
                busy = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Tennis Video Intelligence",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Text(
                    text = "Video upload · court calibration · positioning prototype",
                    color = Muted,
                    fontSize = 13.sp
                )
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionHeading("1", "Backend connection")
                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = { apiBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Backend URL") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                statusMessage = "Checking backend..."
                                try {
                                    val response = withContext(Dispatchers.IO) { api().health() }
                                    statusMessage = "Backend connected: ${response.optString("status", "ok")}"
                                } catch (error: Exception) {
                                    statusMessage = "Connection failed: ${error.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check backend")
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionHeading("2", "Upload a match")
                    Text(selectedName, color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { videoPicker.launch(arrayOf("video/*")) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Choose video")
                        }
                        Button(
                            onClick = {
                                val uri = selectedUri
                                if (uri != null) {
                                    scope.launch {
                                        busy = true
                                        statusMessage = "Uploading video..."
                                        try {
                                            val response = withContext(Dispatchers.IO) {
                                                api().uploadVideo(uri)
                                            }
                                            videoId = response.getString("video_id")
                                            analysisId = null
                                            analysisSummary = null
                                            eventCandidates.clear()
                                            calibrationId = null
                                            calibrationPoints.clear()
                                            selectedPlayerPoint = null
                                            statusMessage = "Upload complete. Video ID: $videoId"
                                        } catch (error: Exception) {
                                            statusMessage = "Upload failed: ${error.message}"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            },
                            enabled = selectedUri != null && !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Upload")
                        }
                    }
                    if (videoId != null) {
                        Text("Video ID: $videoId", color = CourtGreen, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    statusMessage = "Inspecting video with ffprobe..."
                                    try {
                                        val response = withContext(Dispatchers.IO) {
                                            api().inspectVideo(videoId!!)
                                        }
                                        mediaSummary = buildString {
                                            append("${response.optInt("width")} × ${response.optInt("height")}")
                                            append(" · ${response.optDouble("frame_rate", 0.0).let { String.format(Locale.US, "%.2f fps", it) }}")
                                            append(" · ${response.optDouble("duration_seconds", 0.0).let { String.format(Locale.US, "%.1f s", it) }}")
                                            append(" · ${response.optString("video_codec", "unknown")}")
                                        }
                                        statusMessage = "Media inspection complete."
                                    } catch (error: Exception) {
                                        statusMessage = "Inspection failed: ${error.message}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Inspect video")
                        }
                        Text(mediaSummary, color = Muted, fontSize = 12.sp)
                    }
                }
            }

            if (videoId != null) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SectionHeading("3", "Preview a frame")
                        OutlinedTextField(
                            value = timestampText,
                            onValueChange = { timestampText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Timestamp in seconds") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val timestamp = timestampText.toDoubleOrNull()
                                if (timestamp == null || timestamp < 0) {
                                    statusMessage = "Enter a valid timestamp of zero or more seconds."
                                } else {
                                    scope.launch {
                                        busy = true
                                        statusMessage = "Extracting frame..."
                                        try {
                                            frameBitmap = withContext(Dispatchers.IO) {
                                                api().getFrame(videoId!!, timestamp)
                                            }
                                            calibrationPoints.clear()
                                            selectedPlayerPoint = null
                                            statusMessage = "Frame ready. Tap the four court corners in order."
                                        } catch (error: Exception) {
                                            statusMessage = "Frame extraction failed: ${error.message}"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Load frame")
                        }

                        val bitmap = frameBitmap
                        if (bitmap != null) {
                            Text(
                                text = "Frame ${bitmap.width} × ${bitmap.height} pixels",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            OutlinedButton(
                                onClick = {
                                    val id = videoId
                                    val timestamp = timestampText.toDoubleOrNull()
                                    if (id != null && timestamp != null && timestamp >= 0) {
                                        scope.launch {
                                            busy = true
                                            statusMessage = "Suggesting court corners..."
                                            try {
                                                val response = withContext(Dispatchers.IO) {
                                                    api().detectCourt(id, timestamp)
                                                }
                                                val candidate = response.optJSONObject("candidate")
                                                val points = candidate?.optJSONArray("image_points")
                                                if (points != null && points.length() == 4) {
                                                    calibrationPoints.clear()
                                                    for (index in 0 until points.length()) {
                                                        val point = points.optJSONArray(index)
                                                        if (point != null && point.length() == 2) {
                                                            calibrationPoints.add(
                                                                point.optDouble(0).toFloat() to
                                                                    point.optDouble(1).toFloat()
                                                            )
                                                        }
                                                    }
                                                    statusMessage = "Court suggestion loaded. Verify the corner order visually before saving."
                                                } else {
                                                    statusMessage = "No court candidate found. Select the four corners manually."
                                                }
                                            } catch (error: Exception) {
                                                statusMessage = "Court detection failed: ${error.message}"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                },
                                enabled = !busy && frameBitmap != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Suggest court corners automatically")
                            }
                            FrameTapPreview(
                                bitmap = bitmap,
                                selectedPoints = calibrationPoints.toList(),
                                selectedPlayerPoint = selectedPlayerPoint,
                                onTap = { point ->
                                    if (calibrationId == null) {
                                        if (calibrationPoints.size < 4) {
                                            calibrationPoints.add(point)
                                            statusMessage = "Court corner ${calibrationPoints.size} of 4 selected."
                                        }
                                    } else {
                                        selectedPlayerPoint = point
                                        statusMessage = "Player ground point selected."
                                    }
                                }
                            )
                        }
                    }
                }

                if (frameBitmap != null) {
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SectionHeading("4", "Calibrate the court")
                            if (calibrationId == null) {
                                Text(
                                    text = "Tap four outer court corners in this exact order:",
                                    color = Muted,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "1 Far-left  →  2 Far-right  →  3 Near-right  →  4 Near-left",
                                    color = Ink,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${calibrationPoints.size} / 4 corners selected",
                                    color = CourtGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            calibrationPoints.clear()
                                            statusMessage = "Corner selections cleared."
                                        },
                                        enabled = calibrationPoints.isNotEmpty() && !busy,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Clear taps")
                                    }
                                    Button(
                                        onClick = {
                                            val id = videoId
                                            if (id != null && calibrationPoints.size == 4) {
                                                scope.launch {
                                                    busy = true
                                                    statusMessage = "Saving manual court calibration..."
                                                    try {
                                                        val timestamp = timestampText.toDouble()
                                                        val response = withContext(Dispatchers.IO) {
                                                            api().createCalibration(
                                                                id,
                                                                timestamp,
                                                                calibrationPoints.toList()
                                                            )
                                                        }
                                                        calibrationId = response.getString("calibration_id")
                                                        selectedPlayerPoint = null
                                                        statusMessage = "Calibration saved. Tap a player's feet / ground-contact point."
                                                    } catch (error: Exception) {
                                                        statusMessage = "Calibration failed: ${error.message}"
                                                    } finally {
                                                        busy = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = calibrationPoints.size == 4 && !busy,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Save calibration")
                                    }
                                }
                            } else {
                                Text("Calibration ID: $calibrationId", color = CourtGreen, fontSize = 12.sp)
                                Text(
                                    "Tap the midpoint between the player's feet (ground-contact point), not their torso.",
                                    color = Muted,
                                    fontSize = 13.sp
                                )
                                Text(
                                    if (selectedPlayerPoint == null) {
                                        "No player point selected."
                                    } else {
                                        "Selected image point: (${selectedPlayerPoint!!.first.toInt()}, ${selectedPlayerPoint!!.second.toInt()}) px"
                                    },
                                    color = Ink,
                                    fontSize = 13.sp
                                )
                                Text("Which end of the court is this player on?", color = Muted, fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = playerEnd == "near",
                                        onClick = { playerEnd = "near" }
                                    )
                                    Text("Near baseline")
                                    Spacer(Modifier.width(12.dp))
                                    RadioButton(
                                        selected = playerEnd == "far",
                                        onClick = { playerEnd = "far" }
                                    )
                                    Text("Far baseline")
                                }
                                Button(
                                    onClick = {
                                        val id = videoId
                                        val calibration = calibrationId
                                        val point = selectedPlayerPoint
                                        if (id != null && calibration != null && point != null) {
                                            scope.launch {
                                                busy = true
                                                statusMessage = "Projecting point into court coordinates..."
                                                try {
                                                    val response = withContext(Dispatchers.IO) {
                                                        api().projectPoint(id, calibration, point, playerEnd)
                                                    }
                                                    val distance = response.getDouble("signed_baseline_distance_m")
                                                    val feet = distance * 3.28084
                                                    val relation = when {
                                                        distance > 0.01 -> "behind the baseline"
                                                        distance < -0.01 -> "inside the baseline"
                                                        else -> "on the baseline"
                                                    }
                                                    measurementSummary = String.format(
                                                        Locale.US,
                                                        "%+.2f m (%+.2f ft) %s",
                                                        distance,
                                                        feet,
                                                        relation
                                                    )
                                                    statusMessage = "Point projected using manual court calibration."
                                                } catch (error: Exception) {
                                                    statusMessage = "Projection failed: ${error.message}"
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = selectedPlayerPoint != null && !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Measure baseline distance")
                                }
                                Text("Last measurement", color = Muted, fontSize = 12.sp)
                                Text(
                                    measurementSummary,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CourtGreen
                                )
                            }
                        }
                    }
                }
            }


            if (videoId != null) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SectionHeading("5", "Automatic match analysis")
                        Text(
                            "Run court, player-position and tennis-ball candidate detection on the uploaded match. A verified manual calibration is recommended.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    statusMessage = "Starting automatic analysis..."
                                    try {
                                        val response = withContext(Dispatchers.IO) {
                                            api().startAnalysis(videoId!!, calibrationId)
                                        }
                                        analysisId = response.getString("analysis_id")
                                        analysisStatus = response.optString("status", "queued")
                                        analysisProgress = response.optInt("progress_pct", 0)
                                        analysisSummary = null
                                        eventCandidates.clear()
                                        statusMessage = "Analysis queued. Refresh for progress."
                                    } catch (error: Exception) {
                                        statusMessage = "Could not start analysis: ${error.message}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start a new analysis")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(analysisStatus, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("$analysisProgress%", color = CourtGreen, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { refreshAnalysis() }, enabled = !busy) {
                                Text("Refresh")
                            }
                        }
                        analysisSummary?.let { summary ->
                            val players = summary.optJSONObject("players") ?: JSONObject()
                            val near = players.optJSONObject("near_player") ?: JSONObject()
                            val far = players.optJSONObject("far_player") ?: JSONObject()
                            Text("Baseline positioning (metres)", color = Ink, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlayerMetric("Near player", near)
                                PlayerMetric("Far player", far)
                            }
                            val calibration = summary.optJSONObject("calibration") ?: JSONObject()
                            Text(
                                "Court calibration: ${calibration.optString("mode", "unknown")}" +
                                    if (calibration.optBoolean("requires_confirmation", true)) " · confirm required" else "",
                                color = Muted,
                                fontSize = 11.sp
                            )
                            Text(
                                "Player-detection coverage: ${String.format(Locale.US, "%.1f%%", summary.optDouble("player_detection_coverage_pct", 0.0))} · " +
                                    "Ball candidates: ${summary.optInt("ball_candidates")}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            val heatmaps = summary.optJSONObject("position_heatmaps")
                            if (heatmaps != null) {
                                Text("Position heatmaps", color = Ink, fontWeight = FontWeight.Bold)
                                CourtHeatmap(parseGrid(heatmaps.optJSONArray("near_player")))
                                Text("Near player", color = Muted, fontSize = 10.sp)
                                CourtHeatmap(parseGrid(heatmaps.optJSONArray("far_player")))
                                Text("Far player", color = Muted, fontSize = 10.sp)
                            }
                            val reviewed = summary.optJSONObject("reviewed_metrics") ?: JSONObject()
                            val serves = reviewed.optJSONObject("serve_placement") ?: JSONObject()
                            val shots = reviewed.optJSONObject("shot_placement") ?: JSONObject()
                            val rallies = reviewed.optJSONObject("rally_length") ?: JSONObject()
                            val winnerError = reviewed.optJSONObject("winner_error_classification") ?: JSONObject()
                            Text(
                                "Reviewed serves: ${serves.optInt("confirmed_serves")} · " +
                                    "Reviewed bounces: ${shots.optInt("confirmed_bounces")}",
                                color = Ink,
                                fontSize = 12.sp
                            )
                            Text(
                                "Completed points: ${rallies.optInt("completed_points")} · " +
                                    "Winners / forced / unforced errors: ${winnerError.optInt("winners")} / " +
                                    "${winnerError.optInt("forced_errors")} / ${winnerError.optInt("unforced_errors")}",
                                color = Muted,
                                fontSize = 11.sp
                            )
                            OutlinedButton(
                                onClick = {
                                    val id = analysisId
                                    if (id != null) {
                                        scope.launch {
                                            busy = true
                                            try {
                                                reportBytes = withContext(Dispatchers.IO) {
                                                    api().getAnalysisReportSvg(id)
                                                }
                                                reportSaver.launch("tennis-match-report.svg")
                                            } catch (error: Exception) {
                                                statusMessage = "Report export failed: ${error.message}"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                },
                                enabled = analysisStatus == "completed" && !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save SVG court report")
                            }
                        }
                    }
                }
            }

            if (analysisId != null && analysisStatus == "completed") {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SectionHeading("6", "Review event candidates")
                        Text(
                            "Only human-accepted, explicitly labelled events contribute to serve, shot, rally and winner/error statistics.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                        if (eventCandidates.isEmpty()) {
                            Text("No trajectory-change candidates were detected in this video.", color = Muted, fontSize = 12.sp)
                        }
                        eventCandidates.take(12).forEach { event ->
                            Card(shape = RoundedCornerShape(10.dp)) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "${event.optString("event_type")} · ${String.format(Locale.US, "%.2f s", event.optDouble("timestamp_seconds"))}",
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        "Automatic candidate only. Check the video before accepting.",
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(onClick = { reviewCandidate(event, "serve_bounce") }, enabled = !busy) { Text("Serve") }
                                        OutlinedButton(onClick = { reviewCandidate(event, "shot_bounce") }, enabled = !busy) { Text("Bounce") }
                                        OutlinedButton(onClick = { reviewCandidate(event, "racket_contact") }, enabled = !busy) { Text("Contact") }
                                        OutlinedButton(onClick = { reviewCandidate(event, null) }, enabled = !busy) { Text("Reject") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(onClick = { reviewCandidate(event, "winner") }, enabled = !busy) { Text("Winner") }
                                        OutlinedButton(onClick = { reviewCandidate(event, "forced_error") }, enabled = !busy) { Text("Forced error") }
                                        OutlinedButton(onClick = { reviewCandidate(event, "unforced_error") }, enabled = !busy) { Text("Unforced error") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.height(22.dp).width(22.dp))
                    Column {
                        Text("Activity", fontWeight = FontWeight.Bold, color = Ink)
                        Text(statusMessage, color = Muted, fontSize = 13.sp)
                    }
                }
            }

            Text(
                text = "Reliability note: generic HOG and colour-blob detections are unverified candidates, not validated tennis-specific models. Confirm court calibration and event labels before using statistics.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}


@Composable
private fun PlayerMetric(title: String, player: JSONObject) {
    Card(modifier = Modifier.width(155.dp), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = Muted, fontSize = 11.sp)
            val value = player.opt("average_baseline_distance_m")
            val text = if (value == null || value == JSONObject.NULL) {
                "N/A"
            } else {
                String.format(Locale.US, "%+.2f m", player.optDouble("average_baseline_distance_m"))
            }
            Text(text, color = CourtGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Behind: ${String.format(Locale.US, "%.0f%%", player.optDouble("behind_baseline_pct", 0.0))}",
                color = Ink,
                fontSize = 11.sp
            )
            Text("Samples: ${player.optInt("samples")}", color = Muted, fontSize = 10.sp)
        }
    }
}

private fun parseGrid(array: JSONArray?): List<List<Int>> {
    if (array == null) return emptyList()
    return buildList {
        for (rowIndex in 0 until array.length()) {
            val row = array.optJSONArray(rowIndex) ?: continue
            add(buildList {
                for (columnIndex in 0 until row.length()) add(row.optInt(columnIndex))
            })
        }
    }
}

@Composable
private fun CourtHeatmap(grid: List<List<Int>>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        drawRect(Color(0xFF167A5B))
        if (grid.isNotEmpty()) {
            val maximum = grid.flatten().maxOrNull()?.coerceAtLeast(1) ?: 1
            val columns = grid.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
            val cellWidth = size.width / columns
            val cellHeight = size.height / grid.size
            grid.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, value ->
                    if (value > 0) {
                        drawRect(
                            color = Color(0xFFFFD166).copy(
                                alpha = (value.toFloat() / maximum).coerceIn(0.15f, 0.85f)
                            ),
                            topLeft = Offset(columnIndex * cellWidth, rowIndex * cellHeight),
                            size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight)
                        )
                    }
                }
            }
        }
        drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.8f), Offset(size.width * 0.125f, 0f), Offset(size.width * 0.125f, size.height), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.8f), Offset(size.width * 0.875f, 0f), Offset(size.width * 0.875f, size.height), 1.dp.toPx())
        drawRect(Color.White, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun SectionHeading(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CourtGreen.copy(alpha = 0.12f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(number, color = CourtGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(9.dp))
        Text(title, fontWeight = FontWeight.Bold, color = Ink, fontSize = 17.sp)
    }
}

@Composable
private fun FrameTapPreview(
    bitmap: Bitmap,
    selectedPoints: List<Pair<Float, Float>>,
    selectedPlayerPoint: Pair<Float, Float>?,
    onTap: (Pair<Float, Float>) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFE6EAEC))
            .pointerInput(bitmap, onTap) {
                detectTapGestures { tap ->
                    val containerWidth = size.width.toFloat()
                    val containerHeight = size.height.toFloat()
                    if (containerWidth > 0f && containerHeight > 0f) {
                        val scale = min(
                            containerWidth / bitmap.width.toFloat(),
                            containerHeight / bitmap.height.toFloat()
                        )
                        val imageWidth = bitmap.width * scale
                        val imageHeight = bitmap.height * scale
                        val left = (containerWidth - imageWidth) / 2f
                        val top = (containerHeight - imageHeight) / 2f
                        val x = (tap.x - left) / scale
                        val y = (tap.y - top) / scale
                        if (x in 0f..bitmap.width.toFloat() && y in 0f..bitmap.height.toFloat()) {
                            onTap(x to y)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Video frame. Tap to select a court corner or player position.",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = min(
                size.width / bitmap.width.toFloat(),
                size.height / bitmap.height.toFloat()
            )
            val imageWidth = bitmap.width * scale
            val imageHeight = bitmap.height * scale
            val left = (size.width - imageWidth) / 2f
            val top = (size.height - imageHeight) / 2f

            selectedPoints.forEach { point ->
                val center = Offset(left + point.first * scale, top + point.second * scale)
                drawCircle(CourtGreen, radius = 8.dp.toPx(), center = center)
                drawCircle(
                    Color.White,
                    radius = 8.dp.toPx(),
                    center = center,
                    style = Stroke(2.dp.toPx())
                )
            }
            selectedPlayerPoint?.let { point ->
                val center = Offset(left + point.first * scale, top + point.second * scale)
                drawCircle(Color.Red, radius = 9.dp.toPx(), center = center)
                drawCircle(
                    Color.White,
                    radius = 9.dp.toPx(),
                    center = center,
                    style = Stroke(2.dp.toPx())
                )
            }
        }
    }
}
