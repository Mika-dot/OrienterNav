package com.mikadot.osmlocnav

import android.graphics.BitmapFactory
import kotlin.math.sqrt

data class FrameQuality(
    val acceptable: Boolean,
    val message: String,
    val exposureNudge: Int = 0,
)

/** Cheap on-device gate that avoids sending unusable sun-blinded frames. */
object FrameQualityGate {
    fun assess(jpeg: ByteArray): FrameQuality {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return FrameQuality(false, "Кадр камеры повреждён")
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > 320) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: return FrameQuality(false, "Не удалось прочитать кадр")
        try {
            val width = bitmap.width
            val height = bitmap.height
            var sum = 0.0
            var sum2 = 0.0
            var dark = 0
            var bright = 0
            var edges = 0.0
            var count = 0
            val step = 2
            for (y in 1 until height step step) {
                var previous = -1.0
                for (x in 1 until width step step) {
                    val pixel = bitmap.getPixel(x, y)
                    val luma = 0.2126 * ((pixel shr 16) and 255) + 0.7152 * ((pixel shr 8) and 255) + 0.0722 * (pixel and 255)
                    sum += luma
                    sum2 += luma * luma
                    if (luma < 18) dark++
                    if (luma > 244) bright++
                    if (previous >= 0) edges += kotlin.math.abs(luma - previous)
                    previous = luma
                    count++
                }
            }
            if (count == 0) return FrameQuality(false, "Пустой кадр")
            val mean = sum / count
            val deviation = sqrt((sum2 / count - mean * mean).coerceAtLeast(0.0))
            val brightRatio = bright.toDouble() / count
            val darkRatio = dark.toDouble() / count
            val edgeScore = edges / count
            return when {
                brightRatio > 0.42 || mean > 218 -> FrameQuality(false, "Камеру слепит солнце — продолжаю по ИНС", -1)
                darkRatio > 0.58 || mean < 28 -> FrameQuality(false, "Кадр слишком тёмный — продолжаю по ИНС", 1)
                deviation < 16 || edgeScore < 2.2 -> FrameQuality(false, "Кадр размыт или без деталей — продолжаю по ИНС")
                else -> FrameQuality(true, "Кадр пригоден")
            }
        } finally {
            bitmap.recycle()
        }
    }
}
