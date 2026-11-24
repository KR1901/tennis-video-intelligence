package com.kr1901.tennisvideo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

internal data class BallObservation(
    val x: Float,
    val y: Float,
    val confidence: Float
)

internal class TrackNetBallDetector(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val model = copyModelAsset(context, MODEL_ASSET_PATH, MODEL_FILE_NAME)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(INFERENCE_THREADS)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(model.absolutePath, options)
        }
        inputName = session.inputNames.firstOrNull()
            ?: error("TrackNet ONNX model does not expose an input tensor")
    }

    fun detect(current: Bitmap, previous: Bitmap, oldest: Bitmap): BallObservation? {
        val frames = listOf(current, previous, oldest)
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)

        frames.forEach { source ->
            val scaled = if (source.width == INPUT_WIDTH && source.height == INPUT_HEIGHT) {
                source
            } else {
                Bitmap.createScaledBitmap(source, INPUT_WIDTH, INPUT_HEIGHT, true)
            }
            scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
            // The reference TrackNet input concatenates OpenCV BGR images along HWC
            // and then converts to CHW. Write three complete channel planes per frame.
            for (channel in 0 until 3) {
                pixels.forEach { pixel ->
                    val value = when (channel) {
                        0 -> Color.blue(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.red(pixel)
                    }
                    inputBuffer.put(value / 255f)
                }
            }
            if (scaled !== source) scaled.recycle()
        }
        inputBuffer.rewind()

        val tensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1L, 9L, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
        )
        try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as? OnnxTensor
                    ?: error("TrackNet returned an unexpected output")
                val heatmap = output.getFloatBuffer() ?: error("TrackNet output was not a float heatmap")
                if (heatmap.remaining() < OUTPUT_PIXELS) {
                    error("TrackNet output was shorter than the declared 640 x 360 heatmap")
                }

                var maxIndex = -1
                var maxValue = 0f
                for (index in 0 until OUTPUT_PIXELS) {
                    val score = heatmap.get(index)
                    if (score > maxValue) {
                        maxValue = score
                        maxIndex = index
                    }
                }
                if (maxIndex < 0 || maxValue < MIN_BALL_PROBABILITY) return null

                val peakX = maxIndex % INPUT_WIDTH
                val peakY = maxIndex / INPUT_WIDTH
                val minimum = max(MIN_BALL_PROBABILITY, maxValue * PEAK_RELATIVE_THRESHOLD)
                var totalWeight = 0.0
                var weightedX = 0.0
                var weightedY = 0.0
                for (y in (peakY - PEAK_RADIUS).coerceAtLeast(0)..(peakY + PEAK_RADIUS).coerceAtMost(INPUT_HEIGHT - 1)) {
                    for (x in (peakX - PEAK_RADIUS).coerceAtLeast(0)..(peakX + PEAK_RADIUS).coerceAtMost(INPUT_WIDTH - 1)) {
                        val score = heatmap.get(y * INPUT_WIDTH + x)
                        if (score >= minimum) {
                            val weight = score.toDouble()
                            weightedX += x * weight
                            weightedY += y * weight
                            totalWeight += weight
                        }
                    }
                }
                val x = if (totalWeight > 0.0) weightedX / totalWeight else peakX.toDouble()
                val y = if (totalWeight > 0.0) weightedY / totalWeight else peakY.toDouble()
                return BallObservation(
                    x = (x / INPUT_WIDTH).toFloat().coerceIn(0f, 1f),
                    y = (y / INPUT_HEIGHT).toFloat().coerceIn(0f, 1f),
                    confidence = maxValue.coerceIn(0f, 1f)
                )
            }
        } finally {
            tensor.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/tracknet_v1.onnx"
        private const val MODEL_FILE_NAME = "tracknet_v1.onnx"
        private const val INPUT_WIDTH = 640
        private const val INPUT_HEIGHT = 360
        private const val INPUT_FLOATS = 9 * INPUT_WIDTH * INPUT_HEIGHT
        private const val OUTPUT_PIXELS = INPUT_WIDTH * INPUT_HEIGHT
        private const val INFERENCE_THREADS = 2
        private const val MIN_BALL_PROBABILITY = 0.12f
        private const val PEAK_RELATIVE_THRESHOLD = 0.60f
        private const val PEAK_RADIUS = 9

        private fun copyModelAsset(context: Context, assetPath: String, fileName: String): File {
            val directory = File(context.filesDir, "models").apply { mkdirs() }
            val output = File(directory, fileName)
            if (!output.isFile || output.length() == 0L) {
                val temporary = File(directory, fileName + ".tmp")
                context.assets.open(assetPath).use { input ->
                    temporary.outputStream().buffered().use { target -> input.copyTo(target) }
                }
                if (!temporary.renameTo(output)) {
                    temporary.delete()
                    error("Could not install the TrackNet model into private app storage")
                }
            }
            return output
        }
    }
}
