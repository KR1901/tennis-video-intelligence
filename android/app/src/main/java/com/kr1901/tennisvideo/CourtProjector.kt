package com.kr1901.tennisvideo

import kotlin.math.abs

internal data class CourtPositionMeasurement(
    val xMeters: Double,
    val yMeters: Double,
    val playerEnd: String,
    val baselineDistanceMeters: Double
)

internal object CourtProjector {
    private const val HALF_DOUBLES_WIDTH_M = 5.485
    private const val HALF_COURT_LENGTH_M = 11.885
    private const val PIVOT_EPSILON = 1e-10

    // Image corner order: far-left, far-right, near-right, near-left.
    // Court plane origin is at the net centre; +Y is the near half.
    private val targetCorners = listOf(
        -HALF_DOUBLES_WIDTH_M to -HALF_COURT_LENGTH_M,
        HALF_DOUBLES_WIDTH_M to -HALF_COURT_LENGTH_M,
        HALF_DOUBLES_WIDTH_M to HALF_COURT_LENGTH_M,
        -HALF_DOUBLES_WIDTH_M to HALF_COURT_LENGTH_M
    )

    fun project(
        imageCorners: List<Pair<Float, Float>>,
        imageX: Float,
        imageY: Float
    ): CourtPositionMeasurement? {
        if (imageCorners.size != 4) return null
        val coefficients = solveHomography(imageCorners) ?: return null
        val x = imageX.toDouble()
        val y = imageY.toDouble()
        val denominator = coefficients[6] * x + coefficients[7] * y + 1.0
        if (abs(denominator) < PIVOT_EPSILON) return null

        val courtX = (coefficients[0] * x + coefficients[1] * y + coefficients[2]) / denominator
        val courtY = (coefficients[3] * x + coefficients[4] * y + coefficients[5]) / denominator
        if (!courtX.isFinite() || !courtY.isFinite()) return null

        val playerEnd = if (courtY >= 0.0) "near" else "far"
        val baselineDistance = if (courtY >= 0.0) {
            courtY - HALF_COURT_LENGTH_M
        } else {
            -HALF_COURT_LENGTH_M - courtY
        }
        return CourtPositionMeasurement(courtX, courtY, playerEnd, baselineDistance)
    }

    private fun solveHomography(corners: List<Pair<Float, Float>>): DoubleArray? {
        val matrix = Array(8) { DoubleArray(9) }
        for (index in 0 until 4) {
            val imageX = corners[index].first.toDouble()
            val imageY = corners[index].second.toDouble()
            val courtX = targetCorners[index].first
            val courtY = targetCorners[index].second
            val row = index * 2

            matrix[row][0] = imageX
            matrix[row][1] = imageY
            matrix[row][2] = 1.0
            matrix[row][6] = -courtX * imageX
            matrix[row][7] = -courtX * imageY
            matrix[row][8] = courtX

            matrix[row + 1][3] = imageX
            matrix[row + 1][4] = imageY
            matrix[row + 1][5] = 1.0
            matrix[row + 1][6] = -courtY * imageX
            matrix[row + 1][7] = -courtY * imageY
            matrix[row + 1][8] = courtY
        }

        // Gaussian elimination with partial pivoting for the 8 unknowns.
        for (column in 0 until 8) {
            var pivotRow = column
            for (row in column + 1 until 8) {
                if (abs(matrix[row][column]) > abs(matrix[pivotRow][column])) {
                    pivotRow = row
                }
            }
            if (abs(matrix[pivotRow][column]) < PIVOT_EPSILON) return null
            if (pivotRow != column) {
                val temporary = matrix[column]
                matrix[column] = matrix[pivotRow]
                matrix[pivotRow] = temporary
            }

            val pivot = matrix[column][column]
            for (entry in column until 9) matrix[column][entry] /= pivot

            for (row in 0 until 8) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (abs(factor) < PIVOT_EPSILON) continue
                for (entry in column until 9) {
                    matrix[row][entry] -= factor * matrix[column][entry]
                }
            }
        }
        return DoubleArray(8) { matrix[it][8] }
    }
}
