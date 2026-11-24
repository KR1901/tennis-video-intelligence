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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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
    var directVideoUrl by remember { mutableStateOf("") }
    var downloadWorkId by remember { mutableStateOf<UUID?>(null) }
    var downloadStatus by remember { mutableStateOf("") }
    var downloadPercent by remember { mutableStateOf(0f) }
    var timestampText by remember { mutableStateOf("0") }
    var frameTimestampMs by remember { mutableStateOf(0L) }
    var markPlayerPosition by remember { mutableStateOf(false) }
    var markEventLocation by remember { mutableStateOf(false) }
    var manualEventType by remember { mutableStateOf("serve") }
    var manualEventNote by remember { mutableStateOf("") }
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
            markEventLocation = false
            courtCorners.clear()
            val storedCorners = saved.optJSONArray("court_corners") ?: JSONArray()
            for (index in 0 until storedCorners.length()) {
                val point = storedCorners.optJSONObject(index) ?: continue
                courtCorners.add(point.optDouble("x").toFloat() to point.optDouble("y").toFloat())
            }
        }
    }

    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences("tvi_local_projects", Context.MODE_PRIVATE)
        val lastId = preferences.getString("last_project_id", null)
        if (!lastId.isNullOrBlank()) restoreProject(lastId)
        val savedDownloadId = preferences.getString("active_download_work_id", null)
        if (!savedDownloadId.isNullOrBlank()) {
            downloadWorkId = runCatching { UUID.fromString(savedDownloadId) }.getOrNull()
        }
    }

    LaunchedEffect(downloadWorkId) {
        val workId = downloadWorkId ?: return@LaunchedEffect
        val workManager = WorkManager.getInstance(context)
        while (isActive) {
            val info = withContext(Dispatchers.IO) {
                runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
            }
            if (info != null) {
                val bytesDone = info.progress.getLong(DirectVideoDownloadWorker.KEY_BYTES_DONE, 0L)
                val percent = info.progress.getInt(DirectVideoDownloadWorker.KEY_PERCENT, -1)
                downloadPercent = if (percent >= 0) (percent / 100f).coerceIn(0f, 1f) else 0f
                val total = info.progress.getLong(DirectVideoDownloadWorker.KEY_TOTAL_BYTES, -1L)
                downloadStatus = if (total > 0L) {
                    "Downloading " + formatBytes(bytesDone) + " of " + formatBytes(total) +
                        " · " + (percent.coerceAtLeast(0)) + "%"
                } else if (bytesDone > 0L) {
                    "Downloaded " + formatBytes(bytesDone)
                } else {
                    "Waiting for a network connection..."
                }

                when (info.state) {
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        val createdId = info.outputData.getString(DirectVideoDownloadWorker.KEY_PROJECT_ID)
                        if (!createdId.isNullOrBlank()) {
                            restoreProject(createdId)
                            message = "Direct video downloaded and opened locally. No video was uploaded to a server."
                        }
                        downloadStatus = "Direct video import complete."
                        context.getSharedPreferences("tvi_local_projects", Context.MODE_PRIVATE)
                            .edit().remove("active_download_work_id").apply()
                        downloadWorkId = null
                        busy = false
                        break
                    }
                    androidx.work.WorkInfo.State.FAILED,
                    androidx.work.WorkInfo.State.CANCELLED -> {
                        val error = info.outputData.getString(DirectVideoDownloadWorker.KEY_ERROR)
                        downloadStatus = if (info.state == androidx.work.WorkInfo.State.CANCELLED) {
                            "Direct video download cancelled."
                        } else {
                            "Direct video download failed."
                        }
                        message = error ?: downloadStatus
                        context.getSharedPreferences("tvi_local_projects", Context.MODE_PRIVATE)
                            .edit().remove("active_download_work_id").apply()
                        downloadWorkId = null
                        busy = false
                        break
                    }
                    else -> {
                        busy = true
                    }
                }
            }
            delay(1_000L)
        }
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

    val eventsCsvExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri ->
            val id = projectId
            if (uri != null && id != null) {
                scope.launch {
                    try {
                        val csv = withContext(Dispatchers.IO) {
                            LocalProjectStore.exportEventsCsv(context, id)
                        }
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                                ?: error("Could not open the selected destination")
                        }
                        message = "Event review CSV exported."
                    } catch (error: Exception) {
                        message = "Event export failed: " + (error.message ?: "unknown error")
                    }
                }
            }
        }
    )

    val sampleArray = project?.optJSONArray("samples") ?: JSONArray()
    val recentSampleCount = sampleArray.length()
    val sampleCount = project?.optInt("sample_count", recentSampleCount) ?: recentSampleCount
    val manualPositionArray = project?.optJSONArray("manual_positions") ?: JSONArray()
    val manualPositionCount = manualPositionArray.length()
    val eventArray = project?.optJSONArray("events") ?: JSONArray()
    val eventCount = eventArray.length()
    val reviewableEventCount = (0 until eventCount).count {
        eventArray.optJSONObject(it)?.optString("review_status") == "needs_review"
    }
    val latestManualPosition = if (manualPositionCount > 0) {
        manualPositionArray.optJSONObject(manualPositionCount - 1)
    } else {
        null
    }
    val latestSample = sampleArray.optJSONObject(recentSampleCount - 1)
    val latestPlayers = latestSample?.optJSONArray("players") ?: JSONArray()
    var hasCalibratedPlayers = false
    for (index in 0 until latestPlayers.length()) {
        if (latestPlayers.optJSONObject(index)?.has("court_x_m") == true) {
            hasCalibratedPlayers = true
            break
        }
    }
    val latestBall = latestSample?.optJSONObject("ball")
    var greenTotal = 0.0
    var motionTotal = 0.0
    var greenCount = 0
    var motionCount = 0
    var ballDetectionCount = 0
    for (index in 0 until recentSampleCount) {
        val sample = sampleArray.optJSONObject(index) ?: continue
        if (sample.has("green_pct")) {
            greenTotal += sample.optDouble("green_pct")
            greenCount++
        }
        if (sample.has("motion_pct")) {
            motionTotal += sample.optDouble("motion_pct")
            motionCount++
        }
        if (sample.optJSONObject("ball")?.optBoolean("detected", false) == true) {
            ballDetectionCount++
        }
    }
    val averageGreen = if (greenCount > 0) greenTotal / greenCount else 0.0
    val averageMotion = if (motionCount > 0) motionTotal / motionCount else 0.0
    val ballDetectionRate = if (recentSampleCount > 0) ballDetectionCount * 100.0 / recentSampleCount else 0.0
    val status = project?.optString("analysis_status", "not_started") ?: "not_started"
    val progress = project?.optInt("progress_pct", 0) ?: 0
    val durationMs = project?.optLong("duration_ms", 0L) ?: 0L
    val previewOverlaySample = (0 until recentSampleCount)
        .mapNotNull { sampleArray.optJSONObject(it) }
        .minByOrNull { abs(it.optLong("timestamp_ms") - frameTimestampMs) }
        ?.takeIf { abs(it.optLong("timestamp_ms") - frameTimestampMs) <= 700L }

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
                    Divider()
                    OutlinedTextField(
                        value = directVideoUrl,
                        onValueChange = { directVideoUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Direct HTTPS video URL") },
                        placeholder = { Text("https://example.com/match.mp4") },
                        supportingText = {
                            Text("Optional: imports a direct video-file link over HTTPS. This does not fetch YouTube/watch pages, playlists, DRM streams or subscription videos.")
                        },
                        singleLine = true,
                        enabled = !busy
                    )
                    Button(
                        onClick = {
                            val parsed = runCatching { java.net.URI(directVideoUrl.trim()) }.getOrNull()
                            if (parsed == null || !parsed.scheme.equals("https", ignoreCase = true) ||
                                parsed.host.isNullOrBlank() || parsed.userInfo != null
                            ) {
                                message = "Enter a valid direct HTTPS video-file URL."
                            } else {
                                val constraints = Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                                val request = OneTimeWorkRequestBuilder<DirectVideoDownloadWorker>()
                                    .setInputData(
                                        workDataOf(DirectVideoDownloadWorker.KEY_URL to directVideoUrl.trim())
                                    )
                                    .setConstraints(constraints)
                                    .setBackoffCriteria(
                                        BackoffPolicy.EXPONENTIAL,
                                        30L,
                                        TimeUnit.SECONDS
                                    )
                                    .build()
                                WorkManager.getInstance(context).enqueueUniqueWork(
                                    "tvi-direct-video-" + request.id,
                                    ExistingWorkPolicy.KEEP,
                                    request
                                )
                                context.getSharedPreferences("tvi_local_projects", Context.MODE_PRIVATE)
                                    .edit().putString("active_download_work_id", request.id.toString()).apply()
                                downloadWorkId = request.id
                                downloadPercent = 0f
                                downloadStatus = "Waiting for a network connection..."
                                busy = true
                                message = "Direct HTTPS download queued. The file will be stored locally and analysed on this phone."
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download direct video link")
                    }
                    if (downloadWorkId != null) {
                        LinearProgressIndicator(
                            progress = { downloadPercent },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(downloadStatus, color = AppMuted, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                downloadWorkId?.let { WorkManager.getInstance(context).cancelWorkById(it) }
                            },
                            enabled = busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel download")
                        }
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
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    val playerArray = previewOverlaySample?.optJSONArray("players") ?: JSONArray()
                                    for (playerIndex in 0 until playerArray.length()) {
                                        val player = playerArray.optJSONObject(playerIndex) ?: continue
                                        val boxLeft = player.optDouble("box_left", -1.0).toFloat()
                                        val boxTop = player.optDouble("box_top", -1.0).toFloat()
                                        val boxRight = player.optDouble("box_right", -1.0).toFloat()
                                        val boxBottom = player.optDouble("box_bottom", -1.0).toFloat()
                                        if (boxLeft >= 0f && boxTop >= 0f && boxRight <= 1f && boxBottom <= 1f) {
                                            drawRect(
                                                color = Color.Cyan.copy(alpha = 0.75f),
                                                topLeft = Offset(boxLeft * size.width, boxTop * size.height),
                                                size = Size(
                                                    (boxRight - boxLeft) * size.width,
                                                    (boxBottom - boxTop) * size.height
                                                ),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                        val footX = player.optDouble("foot_x", -1.0).toFloat()
                                        val footY = player.optDouble("foot_y", -1.0).toFloat()
                                        if (footX in 0f..1f && footY in 0f..1f) {
                                            drawCircle(
                                                Color.Cyan,
                                                radius = 7f,
                                                center = Offset(footX * size.width, footY * size.height)
                                            )
                                        }
                                    }
                                    val ball = previewOverlaySample?.optJSONObject("ball")
                                    if (ball?.optBoolean("detected", false) == true) {
                                        val ballX = ball.optDouble("x", -1.0).toFloat()
                                        val ballY = ball.optDouble("y", -1.0).toFloat()
                                        if (ballX in 0f..1f && ballY in 0f..1f) {
                                            drawCircle(
                                                Color.Yellow,
                                                radius = 10f,
                                                center = Offset(ballX * size.width, ballY * size.height),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                                            )
                                            drawCircle(
                                                Color.Yellow,
                                                radius = 3f,
                                                center = Offset(ballX * size.width, ballY * size.height)
                                            )
                                        }
                                    }
                                }
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(projectId, courtCorners.size, markPlayerPosition, markEventLocation, manualEventType, manualEventNote) {
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
                                                } else if (markPlayerPosition || markEventLocation) {
                                                    val id = projectId
                                                    val measurement = CourtProjector.project(courtCorners.toList(), x, y)
                                                    if (id == null || measurement == null) {
                                                        message = "This point could not be projected. Check that the four court corners are distinct and correctly ordered."
                                                    } else {
                                                        val timestamp = frameTimestampMs
                                                        val savePlayerPoint = markPlayerPosition
                                                        markPlayerPosition = false
                                                        markEventLocation = false
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                if (savePlayerPoint) {
                                                                    LocalProjectStore.addManualPosition(
                                                                        context,
                                                                        id,
                                                                        timestamp,
                                                                        x,
                                                                        y,
                                                                        measurement
                                                                    )
                                                                } else {
                                                                    LocalProjectStore.addManualEvent(
                                                                        context,
                                                                        id,
                                                                        timestamp,
                                                                        manualEventType.trim(),
                                                                        manualEventNote.trim(),
                                                                        measurement
                                                                    )
                                                                }
                                                            }
                                                            project = withContext(Dispatchers.IO) {
                                                                LocalProjectStore.load(context, id)
                                                            }
                                                            message = if (savePlayerPoint) {
                                                                "Saved " + measurement.playerEnd +
                                                                    "-side position: " +
                                                                    String.format(Locale.US, "x %.2f m, y %.2f m; baseline distance %.2f m", measurement.xMeters, measurement.yMeters, measurement.baselineDistanceMeters) +
                                                                    ". Positive means behind the baseline; negative means inside the court."
                                                            } else {
                                                                "Saved reviewed " + manualEventType + " location at " +
                                                                    String.format(Locale.US, "x %.2f m, y %.2f m", measurement.xMeters, measurement.yMeters) + "."
                                                            }
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
                                onClick = {
                                    markPlayerPosition = !markPlayerPosition
                                    markEventLocation = false
                                },
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
                            OutlinedButton(
                                onClick = {
                                    markEventLocation = !markEventLocation
                                    markPlayerPosition = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (markEventLocation) "Cancel event-location tap" else "Mark serve / shot location on this frame")
                            }
                            if (markEventLocation) {
                                Text(
                                    "The next tap records the event type shown in the event-review section below at this timestamp and saves its court x/y metres. Use this for a manually identified serve or shot landing point; it is not automatically detected.",
                                    color = TennisGreen,
                                    fontSize = 12.sp
                                )
                            }
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
                            "The app runs MediaPipe Pose Landmarker on video frames to propose up to two player poses and TrackNet V1 to propose tennis-ball locations on a 5 fps sampling grid. Player feet can be projected into court metres only after you calibrate the four corners. Candidate contacts or trajectory changes remain unconfirmed until you review them.",
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
                                MetricValue("Players now", latestPlayers.length().toString())
                                MetricValue("Ball detections", String.format(Locale.US, "%.1f%%", ballDetectionRate))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MetricValue("Green coverage", String.format(Locale.US, "%.1f%%", averageGreen))
                                MetricValue("Motion signal", String.format(Locale.US, "%.1f%%", averageMotion))
                                MetricValue("Recent frames", recentSampleCount.toString())
                            }
                            Text(
                                "Pose and ball detections are model proposals. Camera cuts, zooms, occlusions and low frame rates can break tracks; automatic tactical events are not confirmed statistics.",
                                color = AppMuted,
                                fontSize = 11.sp
                            )
                            OutlinedButton(
                                onClick = { csvExporter.launch("tennis-video-analysis-timeline.csv") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Export full analysis timeline as CSV") }
                            OutlinedButton(
                                onClick = { eventsCsvExporter.launch("tennis-video-event-review.csv") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Export event candidates and reviews as CSV") }
                        }
                        if (status == "failed") {
                            Text(project?.optString("analysis_error", "Analysis failed") ?: "Analysis failed", color = Color(0xFFB3261E))
                        }
                    }
                }
            }

            if (hasCalibratedPlayers) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("4 · Live mini-court", fontWeight = FontWeight.Bold, color = AppInk)
                        Text(
                            "Latest calibrated player positions. The manual homography assumes the camera view has not panned, zoomed or cut since calibration.",
                            color = AppMuted,
                            fontSize = 12.sp
                        )
                        CourtMiniMap(players = latestPlayers)
                        for (index in 0 until latestPlayers.length()) {
                            val player = latestPlayers.optJSONObject(index) ?: continue
                            if (!player.has("court_x_m")) continue
                            Text(
                                player.optString("player_id") + " · " +
                                    String.format(Locale.US, "x %.2f m, y %.2f m · baseline %+.2f m",
                                        player.optDouble("court_x_m"),
                                        player.optDouble("court_y_m"),
                                        player.optDouble("baseline_distance_m")),
                                color = AppInk,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("5 · Event review", fontWeight = FontWeight.Bold, color = AppInk)
                    Text(
                        "$reviewableEventCount candidate event(s) require review. These are not official or confirmed tennis events.",
                        color = AppMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        "Edit the event-type field below before accepting a candidate to relabel it. The app saves the corrected label as user-reviewed.",
                        color = AppMuted,
                        fontSize = 11.sp
                    )
                    val firstEvent = (eventCount - 12).coerceAtLeast(0)
                    for (index in firstEvent until eventCount) {
                        val event = eventArray.optJSONObject(index) ?: continue
                        val eventId = event.optString("event_id")
                        val eventStatus = event.optString("review_status", "needs_review")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                formatDuration(event.optLong("timestamp_ms")) + " · " +
                                    event.optString("event_type").replace('_', ' '),
                                color = AppInk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                "Confidence " + String.format(Locale.US, "%.0f%%", event.optDouble("confidence") * 100.0) +
                                    " · " + eventStatus.replace('_', ' '),
                                color = AppMuted,
                                fontSize = 11.sp
                            )
                            Text(event.optString("note"), color = AppMuted, fontSize = 11.sp)
                            if (eventStatus == "needs_review") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val id = projectId
                                            if (id != null) scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    LocalProjectStore.reviewEvent(context, id, eventId, true, manualEventType.trim())
                                                    LocalProjectStore.load(context, id)
                                                }.also { project = it }
                                                message = "Candidate accepted as a reviewed event. Verify its type before using the metric."
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Accept") }
                                    OutlinedButton(
                                        onClick = {
                                            val id = projectId
                                            if (id != null) scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    LocalProjectStore.reviewEvent(context, id, eventId, false)
                                                    LocalProjectStore.load(context, id)
                                                }.also { project = it }
                                                message = "Candidate rejected."
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Reject") }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = manualEventType,
                        onValueChange = { manualEventType = it },
                        label = { Text("Manual event type") },
                        supportingText = { Text("Example: serve, return, forehand, backhand, volley, bounce, point_start, point_end, winner, unforced_error.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualEventNote,
                        onValueChange = { manualEventNote = it },
                        label = { Text("Review note (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val id = projectId
                            val timestampMs = ((timestampText.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
                                .coerceAtMost(durationMs)
                            if (id != null && manualEventType.isNotBlank()) scope.launch {
                                withContext(Dispatchers.IO) {
                                    LocalProjectStore.addManualEvent(
                                        context,
                                        id,
                                        timestampMs,
                                        manualEventType.trim(),
                                        manualEventNote.trim()
                                    )
                                    LocalProjectStore.load(context, id)
                                }.also { project = it }
                                manualEventNote = ""
                                message = "Manual event saved as reviewed."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add manual event at selected timestamp") }
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy and current limits", fontWeight = FontWeight.Bold, color = AppInk)
                    Text(
                        "Videos are processed on this phone and are not uploaded. Pose and TrackNet model files are downloaded during the CI build from their published model sources and packaged in the APK. The installed app does not need a network connection for analysis. Project metadata and measurements stay in app-private storage.",
                        color = AppMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        "Player poses and ball locations are automatic model proposals, not a validated identity or ball-tracking system. Player court metres depend on a correct four-corner calibration and a fixed camera. The event detector only flags possible direction changes or contact candidates; review/confirm event labels before counting serves, returns, winners or errors. No automatic court-line detector or camera-pan compensation is implemented yet.",
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
private fun CourtMiniMap(players: JSONArray) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2B765B))
    ) {
        val left = size.width * 0.18f
        val right = size.width * 0.82f
        val top = size.height * 0.07f
        val bottom = size.height * 0.93f
        val courtWidth = right - left
        val courtHeight = bottom - top
        fun mapX(value: Double) = (left + ((value + 5.485) / 10.97) * courtWidth).toFloat()
        fun mapY(value: Double) = (top + ((value + 11.885) / 23.77) * courtHeight).toFloat()

        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(courtWidth, courtHeight),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
        val singlesLeft = mapX(-4.115)
        val singlesRight = mapX(4.115)
        drawLine(Color.White, Offset(singlesLeft, top), Offset(singlesLeft, bottom), strokeWidth = 2f)
        drawLine(Color.White, Offset(singlesRight, top), Offset(singlesRight, bottom), strokeWidth = 2f)
        val netY = mapY(0.0)
        drawLine(Color.White, Offset(left, netY), Offset(right, netY), strokeWidth = 3f)
        for (y in listOf(-6.4, 6.4)) {
            val serviceY = mapY(y)
            drawLine(Color.White, Offset(singlesLeft, serviceY), Offset(singlesRight, serviceY), strokeWidth = 2f)
        }
        drawLine(
            Color.White,
            Offset((singlesLeft + singlesRight) / 2f, mapY(-6.4)),
            Offset((singlesLeft + singlesRight) / 2f, mapY(6.4)),
            strokeWidth = 1.5f
        )
        for (index in 0 until players.length()) {
            val player = players.optJSONObject(index) ?: continue
            if (!player.has("court_x_m") || !player.has("court_y_m")) continue
            val x = player.optDouble("court_x_m")
            val y = player.optDouble("court_y_m")
            if (x !in -5.485..5.485 || y !in -11.885..11.885) continue
            val point = Offset(mapX(x), mapY(y))
            drawCircle(if (player.optString("player_id") == "P1") Color(0xFFFFD166) else Color(0xFF80D7FF), 10f, point)
            drawCircle(Color.White, 3f, point)
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
