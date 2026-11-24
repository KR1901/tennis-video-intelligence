package com.kr1901.tennisvideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

private val TennisGreen = Color(0xFF167D68)
private val AppInk = Color(0xFF18232D)
private val AppMuted = Color(0xFF65727E)
private val Page = Color(0xFFF2F5F9)
private const val MAX_LOCAL_VIDEO_BYTES = 4_768L * 1024L * 1024L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = TennisGreen,
                    onPrimary = Color.White,
                    secondary = AppInk,
                    background = Page,
                    surface = Color.White,
                    onSurface = AppInk
                )
            ) {
                LocalTennisVideoApp()
            }
        }
    }
}

@Composable
private fun LocalTennisVideoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<JSONObject?>(null) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a match video stored on this device to begin.") }
    var timestampText by remember { mutableStateOf("0") }
    var frameTimestampMs by remember { mutableStateOf(0L) }
    var markPlayerPosition by remember { mutableStateOf(false) }
    val courtCorners = remember { mutableStateListOf<Pair<Float, Float>>() }

    suspend fun restoreProject(id: String) {
        val saved = withContext(Dispatchers.IO) { LocalProjectStore.load(context, id) }
        project = saved
        if (saved != null) {
            val uri = Uri.parse(saved.optString("content_uri"))
            frameBitmap = withContext(Dispatchers.IO) {
                LocalVideoInspector.frameAt(context, uri, 0L)
            }
            frameTimestampMs = 0L
            markPlayerPosition = false
            courtCorners.clear()
            val storedCorners = saved.optJSONArray("court_corners") ?: JSONArray()
            for (index in 0 until storedCorners.length()) {
                val point = storedCorners.optJSONObject(index) ?: continue
                courtCorners.add(point.optDouble("x").toFloat() to point.optDouble("y").toFloat())
            }
        }
    }

    LaunchedEffect(Unit) {
        val lastId = context.getSharedPreferences("tvi_local_projects", Context.MODE_PRIVATE)
            .getString("last_project_id", null)
        if (!lastId.isNullOrBlank()) restoreProject(lastId)
    }

    val projectId = project?.optString("id")?.takeIf { it.isNotBlank() }
    LaunchedEffect(projectId) {
        val id = projectId ?: return@LaunchedEffect
        while (isActive) {
            delay(1500L)
            val latest = withContext(Dispatchers.IO) { LocalProjectStore.load(context, id) }
            if (latest != null) project = latest
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    message = "Reading the video from local storage..."
                    try {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val metadata = withContext(Dispatchers.IO) {
                            LocalVideoInspector.inspect(context, uri)
                        }
                        if (metadata.sizeBytes < 0L) {
                            message = "This provider does not report the file size. Choose a local file whose size Android can verify so the 5 GB ceiling can be enforced."
                        } else if (metadata.sizeBytes > MAX_LOCAL_VIDEO_BYTES) {
                            message = "This video is larger than the 5 GB app limit. Select a smaller file."
                        } else {
                            val created = withContext(Dispatchers.IO) {
                                LocalProjectStore.create(context, uri, metadata)
                            }
                            project = created
                            frameBitmap = null
                            courtCorners.clear()
                            timestampText = "0"
                            message = "Video opened locally. It has not been uploaded."
                            val newId = created.optString("id")
                            restoreProject(newId)
                        }
                    } catch (error: Exception) {
                        message = "Could not open this video: " + (error.message ?: "unknown error")
                    } finally {
                        busy = false
                    }
                }
            }
        }
    )

    val csvExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri ->
            val id = projectId
            if (uri != null && id != null) {
                scope.launch {
                    try {
                        val csv = withContext(Dispatchers.IO) {
                            LocalProjectStore.exportCsv(context, id)
                        }
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                                ?: error("Could not open the selected destination")
                        }
                        message = "CSV timeline exported."
                    } catch (error: Exception) {
                        message = "CSV export failed: " + (error.message ?: "unknown error")
                    }
                }
            }
        }
    )

    val positionsCsvExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri ->
            val id = projectId
            if (uri != null && id != null) {
                scope.launch {
                    try {
                        val csv = withContext(Dispatchers.IO) {
                            LocalProjectStore.exportPositionsCsv(context, id)
                        }
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                                ?: error("Could not open the selected destination")
                        }
                        message = "Manual court-position CSV exported."
                    } catch (error: Exception) {
                        message = "Position export failed: " + (error.message ?: "unknown error")
                    }
                }
            }
        }
    )

    val sampleArray = project?.optJSONArray("samples") ?: JSONArray()
    val sampleCount = sampleArray.length()
    val manualPositionArray = project?.optJSONArray("manual_positions") ?: JSONArray()
    val manualPositionCount = manualPositionArray.length()
    val latestManualPosition = if (manualPositionCount > 0) {
        manualPositionArray.optJSONObject(manualPositionCount - 1)
    } else {
        null
    }
    var greenTotal = 0.0
    var motionTotal = 0.0
    var greenCount = 0
    var motionCount = 0
    for (index in 0 until sampleCount) {
        val sample = sampleArray.optJSONObject(index) ?: continue
        if (sample.has("green_pct")) {
            greenTotal += sample.optDouble("green_pct")
            greenCount++
        }
        if (sample.has("motion_pct")) {
            motionTotal += sample.optDouble("motion_pct")
            motionCount++
        }
    }
    val averageGreen = if (greenCount > 0) greenTotal / greenCount else 0.0
    val averageMotion = if (motionCount > 0) motionTotal / motionCount else 0.0
    val status = project?.optString("analysis_status", "not_started") ?: "not_started"
    val progress = project?.optInt("progress_pct", 0) ?: 0
    val durationMs = project?.optLong("duration_ms", 0L) ?: 0L

    Surface(modifier = Modifier.fillMaxSize(), color = Page) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Tennis Video Intelligence",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppInk
                )
                Text(
                    text = "Private, on-device video workspace",
                    fontSize = 14.sp,
                    color = AppMuted
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F4F0)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No backend. No upload. No API key.", color = TennisGreen, fontWeight = FontWeight.Bold)
                    Text(
                        "The selected video stays in its original location on your phone. The app reads it directly and saves analysis data in its private app storage.",
                        color = AppInk,
                        fontSize = 13.sp
                    )
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1 · Select a match video", fontWeight = FontWeight.Bold, color = AppInk)
                    Text(
                        project?.optString("display_name") ?: "No video selected",
                        color = AppMuted,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose video from this device")
                    }
                    project?.let { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(formatDuration(item.optLong("duration_ms")), color = AppInk, fontSize = 12.sp)
                            Text(formatBytes(item.optLong("size_bytes")), color = AppInk, fontSize = 12.sp)
                        }
                        Text(
                            item.optInt("width").toString() + " × " + item.optInt("height").toString() +
                                " px · " + item.optString("mime_type", "video"),
                            color = AppMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            "Local processing limit: 4,768 MiB (just under 5 GB).",
                            color = AppMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (project != null) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2 · Inspect a frame", fontWeight = FontWeight.Bold, color = AppInk)
                        if (frameBitmap != null) {
                            val bitmap = frameBitmap!!
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio((bitmap.width.toFloat() / max(1, bitmap.height)).coerceIn(0.55f, 2.4f))
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Selected video frame",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(projectId, courtCorners.size, markPlayerPosition) {
                                            detectTapGestures { offset ->
                                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                                if (courtCorners.size < 4) {
                                                    courtCorners.add(x to y)
                                                    val id = projectId
                                                    if (id != null) {
                                                        val points = courtCorners.toList()
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                LocalProjectStore.setCourtCorners(context, id, points)
                                                            }
                                                            message = "Court corner " + points.size + " saved locally."
                                                        }
                                                    }
                                                } else if (markPlayerPosition) {
                                                    val id = projectId
                                                    val measurement = CourtProjector.project(courtCorners.toList(), x, y)
                                                    if (id == null || measurement == null) {
                                                        message = "This point could not be projected. Check that the four court corners are distinct and correctly ordered."
                                                    } else {
                                                        val timestamp = frameTimestampMs
                                                        markPlayerPosition = false
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                LocalProjectStore.addManualPosition(
                                                                    context,
                                                                    id,
                                                                    timestamp,
                                                                    x,
                                                                    y,
                                                                    measurement
                                                                )
                                                            }
                                                            project = withContext(Dispatchers.IO) {
                                                                LocalProjectStore.load(context, id)
                                                            }
                                                            message = "Saved " + measurement.playerEnd +
                                                                "-side position: " +
                                                                String.format(Locale.US, "x %.2f m, y %.2f m; baseline distance %.2f m", measurement.xMeters, measurement.yMeters, measurement.baselineDistanceMeters) +
                                                                ". Positive means behind the baseline; negative means inside the court."
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    val points = courtCorners.map {
                                        androidx.compose.ui.geometry.Offset(it.first * size.width, it.second * size.height)
                                    }
                                    for (index in 0 until points.lastIndex) {
                                        drawLine(Color.Yellow, points[index], points[index + 1], strokeWidth = 4f)
                                    }
                                    if (points.size == 4) drawLine(Color.Yellow, points.last(), points.first(), strokeWidth = 4f)
                                    points.forEach { drawCircle(Color.Yellow, radius = 7f, center = it) }
                                }
                            }
                            Text(
                                "Frame preview at " + formatDuration(frameTimestampMs) +
                                    ". Tap the four outer court corners in order: far-left, far-right, near-right, near-left. These points are saved locally.",
                                color = AppMuted,
                                fontSize = 12.sp
                            )
                        } else {
                            Text("Frame preview is not available yet.", color = AppMuted, fontSize = 13.sp)
                        }
                        OutlinedTextField(
                            value = timestampText,
                            onValueChange = { timestampText = it },
                            label = { Text("Preview time in seconds") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val item = project
                                    if (item != null) scope.launch {
                                        val seconds = timestampText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                                        val timeMs = (seconds * 1000.0).toLong().coerceAtMost(durationMs)
                                        busy = true
                                        try {
                                            val preview = withContext(Dispatchers.IO) {
                                                LocalVideoInspector.frameAt(
                                                    context,
                                                    Uri.parse(item.optString("content_uri")),
                                                    timeMs
                                                )
                                            }
                                            frameBitmap = preview
                                            if (preview != null) frameTimestampMs = timeMs
                                            message = "Frame preview updated."
                                        } catch (error: Exception) {
                                            message = "Frame extraction failed: " + (error.message ?: "unknown error")
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("Preview frame") }
                            OutlinedButton(
                                onClick = {
                                    courtCorners.clear()
                                    val id = projectId
                                    if (id != null) scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LocalProjectStore.setCourtCorners(context, id, emptyList())
                                            LocalProjectStore.load(context, id)
                                        }.also { project = it }
                                        markPlayerPosition = false
                                        message = "Court corners and measurements cleared."
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Clear corners") }
                        }
                        Text("Saved court corners: " + courtCorners.size + " / 4", color = AppMuted, fontSize = 12.sp)
                        if (courtCorners.size == 4) {
                            OutlinedButton(
                                onClick = { markPlayerPosition = !markPlayerPosition },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (markPlayerPosition) "Cancel player-point tap" else "Mark player foot on this frame")
                            }
                            Text(
                                if (markPlayerPosition) {
                                    "Tap the midpoint between the player's feet / ground-contact point. The point will be projected into court metres."
                                } else {
                                    "After calibration, you can manually tap a player's ground point on the current frame."
                                },
                                color = if (markPlayerPosition) TennisGreen else AppMuted,
                                fontSize = 12.sp
                            )
                        }
                        Text("Saved manual positions: " + manualPositionCount, color = AppMuted, fontSize = 12.sp)
                        latestManualPosition?.let { position ->
                            val signedDistance = position.optDouble("baseline_distance_m")
                            Text(
                                "Latest: " + position.optString("player_end") +
                                    " half · x " + String.format(Locale.US, "%.2f m", position.optDouble("court_x_m")) +
                                    " · y " + String.format(Locale.US, "%.2f m", position.optDouble("court_y_m")) +
                                    " · baseline " + String.format(Locale.US, "%+.2f m", signedDistance),
                                color = AppInk,
                                fontSize = 12.sp
                            )
                            Text(
                                if (signedDistance >= 0.0) "Player point is behind the baseline." else "Player point is inside the baseline.",
                                color = AppMuted,
                                fontSize = 11.sp
                            )
                            OutlinedButton(
                                onClick = { positionsCsvExporter.launch("tennis-video-manual-positions.csv") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Export manual court positions as CSV") }
                        }
                    }
                }

                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("3 · Analyse on this phone", fontWeight = FontWeight.Bold, color = AppInk)
                        Text(
                            "The current offline pass samples one frame per second at a small working resolution. It measures approximate green-pixel coverage and coarse frame-to-frame visual change. It does not yet detect or identify players, track the ball, or infer serves and shot locations.",
                            color = AppMuted,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                val id = projectId
                                if (id != null) scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            LocalProjectStore.prepareAnalysis(context, id)
                                        }
                                        val request = OneTimeWorkRequestBuilder<LocalAnalysisWorker>()
                                            .setInputData(
                                                workDataOf(
                                                    LocalAnalysisWorker.KEY_PROJECT_ID to id,
                                                    LocalAnalysisWorker.KEY_START_MS to 0L
                                                )
                                            )
                                            .addTag(LocalAnalysisWorker.tag(id))
                                            .build()
                                        WorkManager.getInstance(context).enqueueUniqueWork(
                                            LocalAnalysisWorker.workName(id, 0L),
                                            ExistingWorkPolicy.KEEP,
                                            request
                                        )
                                        project = withContext(Dispatchers.IO) { LocalProjectStore.load(context, id) }
                                        message = "On-device analysis queued. Android can continue the saved work after you leave the app."
                                    } catch (error: Exception) {
                                        message = "Could not start local analysis: " + (error.message ?: "unknown error")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && status != "queued" && status != "processing" && durationMs > 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (status == "completed") "Run analysis again" else "Start on-device analysis")
                        }
                        if (status == "queued" || status == "processing") {
                            LinearProgressIndicator(
                                progress = (progress / 100f).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            "Status: " + status.replace('_', ' ') + " · " + progress + "%",
                            color = AppInk,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        if (sampleCount > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MetricValue("Frames sampled", sampleCount.toString())
                                MetricValue("Green coverage", String.format(Locale.US, "%.1f%%", averageGreen))
                                MetricValue("Motion signal", String.format(Locale.US, "%.1f%%", averageMotion))
                            }
                            Text(
                                "These are frame-level signals, not player-position or shot-placement metrics.",
                                color = AppMuted,
                                fontSize = 11.sp
                            )
                            OutlinedButton(
                                onClick = {
                                    csvExporter.launch("tennis-video-sample-timeline.csv")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Export sampled timeline as CSV") }
                        }
                        if (status == "failed") {
                            Text(project?.optString("analysis_error", "Analysis failed") ?: "Analysis failed", color = Color(0xFFB3261E))
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy and current limits", fontWeight = FontWeight.Bold, color = AppInk)
                    Text(
                        "No video is sent to a server and no network permission is required. The source video stays where it is; only the project metadata, corner taps and sampled measurements are stored inside this app. Android may defer background work under battery or system restrictions.",
                        color = AppMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        "This is the offline foundation, not yet a validated tennis-tracking system. Player identity, ball trajectories, serve placement, return position, court-metre distances and tactical statistics still require dedicated on-device models and testing against labelled match footage.",
                        color = AppMuted,
                        fontSize = 12.sp
                    )
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Text("Working...", color = AppMuted, fontSize = 12.sp)
                }
            }
            Text(message, color = AppMuted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RowScope.MetricValue(label: String, value: String) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = AppInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = AppMuted, fontSize = 10.sp)
    }
}

private fun formatDuration(durationMs: Long): String {
    val total = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "Size unavailable"
    val value = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MiB", value)
}
