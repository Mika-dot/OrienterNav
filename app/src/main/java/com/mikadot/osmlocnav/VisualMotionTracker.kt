package com.mikadot.osmlocnav

import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.sqrt

data class VisualMotionObservation(
    val moving: Boolean,
    val yawDeltaDegrees: Double,
    val confidence: Double,
)

/**
 * Lightweight frame-to-frame motion cue. It deliberately does not estimate
 * metres from a monocular camera: scale is unobservable without calibration.
 * It tells the fusion engine whether the car is moving and contributes only a
 * small turn cue; absolute road position still comes from MapLoc/GPS.
 */
class VisualMotionTracker(
    private val width: Int = 56,
    private val height: Int = 36,
) {
    private var previous: FloatArray? = null

    fun reset() {
        previous = null
    }

    fun observe(jpeg: ByteArray): VisualMotionObservation? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        val signature = FloatArray(width * height)
        try {
            var sum = 0.0
            for (y in 0 until height) {
                val sourceY = ((y + 0.5) * bitmap.height / height).toInt().coerceIn(0, bitmap.height - 1)
                for (x in 0 until width) {
                    val sourceX = ((x + 0.5) * bitmap.width / width).toInt().coerceIn(0, bitmap.width - 1)
                    val pixel = bitmap.getPixel(sourceX, sourceY)
                    val luma = (0.2126 * ((pixel shr 16) and 255) +
                        0.7152 * ((pixel shr 8) and 255) + 0.0722 * (pixel and 255)).toFloat()
                    signature[y * width + x] = luma
                    sum += luma
                }
            }
            val mean = sum / signature.size
            var variance = 0.0
            signature.forEach { variance += (it - mean) * (it - mean) }
            val scale = sqrt(variance / signature.size).coerceAtLeast(12.0)
            for (i in signature.indices) signature[i] = ((signature[i] - mean) / scale).toFloat()
        } finally {
            bitmap.recycle()
        }

        val old = previous
        previous = signature
        if (old == null) return null

        var bestShift = 0
        var bestError = Double.POSITIVE_INFINITY
        var zeroError = Double.POSITIVE_INFINITY
        for (shift in -5..5) {
            var error = 0.0
            var count = 0
            for (y in 4 until height - 3) {
                for (x in 6 until width - 6) {
                    val oldX = x + shift
                    if (oldX !in 0 until width) continue
                    error += abs(signature[y * width + x] - old[y * width + oldX])
                    count++
                }
            }
            val normalized = if (count == 0) Double.POSITIVE_INFINITY else error / count
            if (shift == 0) zeroError = normalized
            if (normalized < bestError) {
                bestError = normalized
                bestShift = shift
            }
        }

        val moving = bestError > 0.27
        val confidence = if (moving) {
            ((bestError - 0.18) / 0.55).coerceIn(0.45, 1.0)
        } else {
            (1.0 - bestError / 0.34).coerceIn(0.30, 0.95)
        }
        val shiftIsUseful = zeroError - bestError > 0.055 && abs(bestShift) >= 1
        val yaw = if (shiftIsUseful) -bestShift * (70.0 / width) else 0.0
        return VisualMotionObservation(moving, yaw, confidence)
    }
}
