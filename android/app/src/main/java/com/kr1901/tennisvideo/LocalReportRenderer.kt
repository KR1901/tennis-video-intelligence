package com.kr1901.tennisvideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max

internal object LocalReportRenderer {
    private const val SVG_WIDTH = 960
    private const val SVG_HEIGHT = 1_180
    private const val PAGE_WIDTH = 1_240
    private const val PAGE_HEIGHT = 1_754
    private const val COURT_LEFT = 180f
    private const val COURT_TOP = 400f
    private const val COURT_WIDTH = 420f
    private const val COURT_HEIGHT = 910f
    private const val HALF_WIDTH_M = 5.485
    private const val HALF_LENGTH_M = 11.885

    fun renderSvg(project: JSONObject): String {
        val data = reportData(project)
        val court = StringBuilder()
        val left = 180.0
        val top = 190.0
        val width = 600.0
        val height = 950.0
        val right = left + width
        val bottom = top + height
        fun x(value: Double) = left + ((value + HALF_WIDTH_M) / (HALF_WIDTH_M * 2.0)) * width
        fun y(value: Double) = top + ((value + HALF_LENGTH_M) / (HALF_LENGTH_M * 2.0)) * height

        court.append("<rect x=\"$left\" y=\"$top\" width=\"$width\" height=\"$height\" fill=\"#267653\" stroke=\"#ffffff\" stroke-width=\"5\"/>")
        for (lineX in listOf(-4.115, 4.115)) {
            court.append("<line x1=\"${x(lineX)}\" y1=\"$top\" x2=\"${x(lineX)}\" y2=\"$bottom\" stroke=\"#ffffff\" stroke-width=\"3\"/>")
        }
        court.append("<line x1=\"$left\" y1=\"${y(0.0)}\" x2=\"$right\" y2=\"${y(0.0)}\" stroke=\"#ffffff\" stroke-width=\"6\"/>")
        for (serviceY in listOf(-6.4, 6.4)) {
            court.append("<line x1=\"${x(-4.115)}\" y1=\"${y(serviceY)}\" x2=\"${x(4.115)}\" y2=\"${y(serviceY)}\" stroke=\"#ffffff\" stroke-width=\"3\"/>")
        }
        court.append("<line x1=\"${x(0.0)}\" y1=\"${y(-6.4)}\" x2=\"${x(0.0)}\" y2=\"${y(6.4)}\" stroke=\"#ffffff\" stroke-width=\"2\"/>")

        val trailColors = mapOf("P1" to "#ffcf5c", "P2" to "#7edcff")
        for ((playerId, points) in data.playerTrails) {
            if (points.isEmpty()) continue
            val color = trailColors[playerId] ?: "#f4e5ff"
            if (points.size > 1) {
                val coordinates = points.joinToString(" ") { "${x(it.first)},${y(it.second)}" }
                court.append("<polyline points=\"$coordinates\" fill=\"none\" stroke=\"$color\" stroke-width=\"5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"0.72\"/>")
            }
            val last = points.last()
            court.append("<circle cx=\"${x(last.first)}\" cy=\"${y(last.second)}\" r=\"12\" fill=\"$color\" stroke=\"#17222d\" stroke-width=\"3\"/>")
        }

        for (ball in data.ballPoints) {
            court.append("<circle cx=\"${x(ball.first)}\" cy=\"${y(ball.second)}\" r=\"5\" fill=\"#fff36b\" opacity=\"0.55\"/>")
        }
        for (event in data.reviewedEvents) {
            if (!event.x.isFinite() || !event.y.isFinite()) continue
            val marker = if (event.type.contains("serve")) "#ff8a75" else "#ffffff"
            court.append("<circle cx=\"${x(event.x)}\" cy=\"${y(event.y)}\" r=\"11\" fill=\"$marker\" stroke=\"#10251d\" stroke-width=\"3\"/>")
        }

        val title = escapeXml(data.title)
        val builtAt = escapeXml(data.builtAt)
        val svg = StringBuilder()
        svg.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        svg.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$SVG_WIDTH" height="$SVG_HEIGHT" viewBox="0 0 $SVG_WIDTH $SVG_HEIGHT">""").append('\n')
        svg.append("""<rect width="100%" height="100%" fill="#f4f7fb"/>""")
        svg.append("""<text x="70" y="82" font-size="38" font-family="sans-serif" font-weight="700" fill="#17222d">$title</text>""")
        svg.append("""<text x="70" y="124" font-size="18" font-family="sans-serif" fill="#5b6a77">Local analysis report · $builtAt</text>""")
        svg.append("""<text x="70" y="153" font-size="16" font-family="sans-serif" fill="#17222d">${escapeXml(data.reviewSummary)}</text>""")
        svg.append("""<text x="70" y="176" font-size="13" font-family="sans-serif" fill="#5b6a77">${escapeXml(data.playerSummary)}</text>""")
        svg.append(court)
        svg.append("""<text x="70" y="1170" font-size="15" font-family="sans-serif" fill="#5b6a77">Model candidates are not official match statistics. Camera movement and occlusion may affect detection.</text>""")
        svg.append("</svg>")
        return svg.toString()
    }

    fun renderPng(project: JSONObject): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawPage(canvas, project)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    fun renderPdf(project: JSONObject): ByteArray {
        val document = PdfDocument()
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        )
        drawPage(page.canvas, project)
        document.finishPage(page)
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }

    private fun drawPage(canvas: Canvas, project: JSONObject) {
        val data = reportData(project)
        canvas.drawColor(android.graphics.Color.rgb(244, 247, 251))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(23, 34, 45)
        paint.textSize = 44f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(data.title.take(48), 68f, 94f, paint)
        paint.color = android.graphics.Color.rgb(90, 105, 118)
        paint.textSize = 22f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("Local analysis report · ${data.builtAt}", 68f, 135f, paint)
        paint.color = android.graphics.Color.rgb(23, 34, 45)
        paint.textSize = 18f
        canvas.drawText(data.reviewSummary.take(105), 68f, 184f, paint)
        paint.color = android.graphics.Color.rgb(90, 105, 118)
        paint.textSize = 16f
        canvas.drawText(data.playerSummary.take(125), 68f, 218f, paint)

        drawCourt(canvas, data, paint)

        var textY = 1_390f
        paint.color = android.graphics.Color.rgb(23, 34, 45)
        paint.textSize = 24f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Coverage and review", 68f, textY, paint)
        textY += 42f
        paint.textSize = 19f
        paint.typeface = Typeface.DEFAULT
        val summary = listOf(
            "Frames sampled: ${data.sampleCount}",
            "Player pose observations: ${data.playerObservationCount}",
            "Ball candidate observations: ${data.ballDetectionCount}",
            "Reviewed event labels: ${data.reviewedEvents.size}",
            "Unreviewed event candidates: ${data.pendingEventCount}"
        )
        summary.forEach { line ->
            canvas.drawText(line, 78f, textY, paint)
            textY += 31f
        }

        paint.color = android.graphics.Color.rgb(88, 104, 119)
        paint.textSize = 16f
        canvas.drawText(
            "Player/ball model outputs are review candidates, not validated official match statistics.",
            68f,
            PAGE_HEIGHT - 58f,
            paint
        )
    }

    private fun drawCourt(canvas: Canvas, data: ReportData, paint: Paint) {
        val right = COURT_LEFT + COURT_WIDTH
        val bottom = COURT_TOP + COURT_HEIGHT
        val courtRect = RectF(COURT_LEFT, COURT_TOP, right, bottom)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(38, 118, 83)
        canvas.drawRoundRect(courtRect, 5f, 5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = android.graphics.Color.WHITE
        canvas.drawRect(courtRect, paint)

        fun x(value: Double) = COURT_LEFT + ((value + HALF_WIDTH_M) / (HALF_WIDTH_M * 2.0) * COURT_WIDTH).toFloat()
        fun y(value: Double) = COURT_TOP + ((value + HALF_LENGTH_M) / (HALF_LENGTH_M * 2.0) * COURT_HEIGHT).toFloat()
        for (lineX in listOf(-4.115, 4.115)) {
            canvas.drawLine(x(lineX), COURT_TOP, x(lineX), bottom, paint)
        }
        canvas.drawLine(COURT_LEFT, y(0.0), right, y(0.0), paint)
        for (lineY in listOf(-6.4, 6.4)) {
            canvas.drawLine(x(-4.115), y(lineY), x(4.115), y(lineY), paint)
        }
        canvas.drawLine(x(0.0), y(-6.4), x(0.0), y(6.4), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        val colors = mapOf(
            "P1" to android.graphics.Color.rgb(255, 207, 92),
            "P2" to android.graphics.Color.rgb(126, 220, 255)
        )
        data.playerTrails.forEach { (playerId, points) ->
            if (points.size < 2) return@forEach
            paint.color = colors[playerId] ?: android.graphics.Color.WHITE
            val path = android.graphics.Path()
            path.moveTo(x(points.first().first), y(points.first().second))
            points.drop(1).forEach { path.lineTo(x(it.first), y(it.second)) }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            val last = points.last()
            canvas.drawCircle(x(last.first), y(last.second), 11f, paint)
            paint.style = Paint.Style.STROKE
        }

        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(255, 243, 107)
        data.ballPoints.forEach { canvas.drawCircle(x(it.first), y(it.second), 4f, paint) }
        data.reviewedEvents.forEach { event ->
            paint.color = if (event.type.contains("serve")) {
                android.graphics.Color.rgb(255, 138, 117)
            } else {
                android.graphics.Color.WHITE
            }
            canvas.drawCircle(x(event.x), y(event.y), 10f, paint)
            paint.color = android.graphics.Color.rgb(23, 34, 45)
            canvas.drawCircle(x(event.x), y(event.y), 4f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(23, 34, 45)
        paint.textSize = 18f
        canvas.drawText("Far baseline", COURT_LEFT, COURT_TOP - 18f, paint)
        canvas.drawText("Near baseline", COURT_LEFT, bottom + 30f, paint)
    }

    private fun reportData(project: JSONObject): ReportData {
        val samples = project.optJSONArray("samples") ?: JSONArray()
        val trails = linkedMapOf("P1" to ArrayList<Pair<Double, Double>>(), "P2" to ArrayList<Pair<Double, Double>>())
        val balls = ArrayList<Pair<Double, Double>>()
        var playerObservationCount = 0
        var ballDetectionCount = 0
        for (index in 0 until samples.length()) {
            val sample = samples.optJSONObject(index) ?: continue
            val players = sample.optJSONArray("players") ?: JSONArray()
            for (playerIndex in 0 until players.length()) {
                val player = players.optJSONObject(playerIndex) ?: continue
                val playerId = player.optString("player_id")
                val x = player.optDouble("court_x_m", Double.NaN)
                val y = player.optDouble("court_y_m", Double.NaN)
                if (playerId in trails && x.isFinite() && y.isFinite() &&
                    x in -HALF_WIDTH_M..HALF_WIDTH_M && y in -HALF_LENGTH_M..HALF_LENGTH_M
                ) {
                    trails.getValue(playerId).add(x to y)
                    playerObservationCount++
                }
            }
            val ball = sample.optJSONObject("ball")
            if (ball?.optBoolean("detected", false) == true) {
                val x = ball.optDouble("court_x_m", Double.NaN)
                val y = ball.optDouble("court_y_m", Double.NaN)
                if (x.isFinite() && y.isFinite() &&
                    x in -HALF_WIDTH_M..HALF_WIDTH_M && y in -HALF_LENGTH_M..HALF_LENGTH_M
                ) {
                    balls.add(x to y)
                    ballDetectionCount++
                }
            }
        }

        val events = project.optJSONArray("events") ?: JSONArray()
        val reviewedEvents = ArrayList<ReportEvent>()
        var pendingCount = 0
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            when (event.optString("review_status")) {
                "accepted" -> {
                    val x = event.optDouble("court_x_m", Double.NaN)
                    val y = event.optDouble("court_y_m", Double.NaN)
                    if (x.isFinite() && y.isFinite()) {
                        reviewedEvents.add(ReportEvent(event.optString("event_type"), x, y))
                    }
                }
                "needs_review" -> pendingCount++
            }
        }

        val matchMetrics = project.optJSONObject("match_analytics")
        val serveTotal = matchMetrics?.optInt("serve_zone_total", 0) ?: 0
        val shotTotal = matchMetrics?.optInt("shot_depth_total", 0) ?: 0
        val acceptedEvents = matchMetrics?.optInt("accepted_events", 0) ?: reviewedEvents.size
        val completedRallies = matchMetrics?.optInt("rallies_completed", 0) ?: 0
        val reviewSummary = "Reviewed annotations: $acceptedEvents accepted · $serveTotal serve locations · $shotTotal shot locations · $completedRallies complete rallies"
        val playerMetrics = matchMetrics?.optJSONArray("players") ?: JSONArray()
        val playerSummary = (0 until playerMetrics.length()).mapNotNull { index ->
            val player = playerMetrics.optJSONObject(index) ?: return@mapNotNull null
            player.optString("player_id") + " behind baseline " +
                String.format(Locale.US, "%.1f%%", player.optDouble("behind_baseline_pct")) +
                " · mean signed distance " +
                String.format(Locale.US, "%+.2f m", player.optDouble("mean_signed_baseline_m"))
        }.joinToString("  |  ").ifBlank { "No calibrated player-position summary available." }

        return ReportData(
            title = project.optString("display_name", "Tennis Video Analysis"),
            builtAt = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)
                .format(java.util.Date()),
            sampleCount = project.optInt("sample_count", samples.length()),
            playerObservationCount = playerObservationCount,
            ballDetectionCount = ballDetectionCount,
            pendingEventCount = pendingCount,
            reviewSummary = reviewSummary,
            playerSummary = playerSummary,
            playerTrails = trails,
            ballPoints = balls,
            reviewedEvents = reviewedEvents
        )
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class ReportData(
        val title: String,
        val builtAt: String,
        val sampleCount: Int,
        val playerObservationCount: Int,
        val ballDetectionCount: Int,
        val pendingEventCount: Int,
        val reviewSummary: String,
        val playerSummary: String,
        val playerTrails: Map<String, List<Pair<Double, Double>>>,
        val ballPoints: List<Pair<Double, Double>>,
        val reviewedEvents: List<ReportEvent>
    )

    private data class ReportEvent(
        val type: String,
        val x: Double,
        val y: Double
    )
}
