package com.kr1901.tennisvideo

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Suggests the outer bounds of a court-coloured region for manual confirmation.
 * This is not a court-line detector: green backgrounds, ads and lighting can confuse it.
 */
internal object CourtCalibrationSuggester {
    fun suggest(bitmap: Bitmap): List<Pair<Float, Float>>? {
        val width = SUGGESTION_WIDTH
        val height = max(1, (bitmap.height * width.toFloat() / bitmap.width).toInt())
            .coerceAtMost(SUGGESTION_MAX_HEIGHT)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)

            val leftEdges = IntArray(height) { width }
            val rightEdges = IntArray(height) { -1 }
            val greenCounts = IntArray(height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    if (green > GREEN_MIN &&
                        green > red * GREEN_RED_RATIO &&
                        green > blue * GREEN_BLUE_RATIO
                    ) {
                        greenCounts[y]++
                        leftEdges[y] = min(leftEdges[y], x)
                        rightEdges[y] = max(rightEdges[y], x)
                    }
                }
            }

            val minimumRowCoverage = (width * MIN_ROW_GREEN_FRACTION).toInt()
            val rows = (0 until height).filter { y ->
                greenCounts[y] >= minimumRowCoverage &&
                    rightEdges[y] - leftEdges[y] >= width * MIN_REGION_WIDTH_FRACTION
            }
            if (rows.size < height * MIN_VALID_ROWS_FRACTION) return null

            val segments = ArrayList<IntRange>()
            var start = rows.first()
            var previous = start
            for (row in rows.drop(1)) {
                if (row - previous > MAX_ROW_GAP) {
                    segments.add(start..previous)
                    start = row
                }
                previous = row
            }
            segments.add(start..previous)
            val best = segments.maxByOrNull { it.last - it.first } ?: return null
            if (best.last - best.first < height * MIN_REGION_HEIGHT_FRACTION) return null

            val band = max(2, (best.last - best.first) / 20)
            val topRows = (best.first..min(best.last, best.first + band)).toList()
            val bottomRows = (max(best.first, best.last - band)..best.last).toList()
            val topLeft = median(topRows.map { leftEdges[it] }.filter { it < width })
            val topRight = median(topRows.map { rightEdges[it] }.filter { it >= 0 })
            val bottomLeft = median(bottomRows.map { leftEdges[it] }.filter { it < width })
            val bottomRight = median(bottomRows.map { rightEdges[it] }.filter { it >= 0 })
            if (topRight <= topLeft || bottomRight <= bottomLeft) return null

            return listOf(
                (topLeft.toFloat() / width) to (best.first.toFloat() / height),
                (topRight.toFloat() / width) to (best.first.toFloat() / height),
                (bottomRight.toFloat() / width) to (best.last.toFloat() / height),
                (bottomLeft.toFloat() / width) to (best.last.toFloat() / height)
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return -1
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private const val SUGGESTION_WIDTH = 320
    private const val SUGGESTION_MAX_HEIGHT = 240
    private const val GREEN_MIN = 45
    private const val GREEN_RED_RATIO = 1.08
    private const val GREEN_BLUE_RATIO = 1.04
    private const val MIN_ROW_GREEN_FRACTION = 0.08
    private const val MIN_REGION_WIDTH_FRACTION = 0.35
    private const val MIN_VALID_ROWS_FRACTION = 0.12
    private const val MIN_REGION_HEIGHT_FRACTION = 0.18
    private const val MAX_ROW_GAP = 3
}
