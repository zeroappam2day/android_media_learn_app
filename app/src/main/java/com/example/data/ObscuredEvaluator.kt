package com.example.data

import android.graphics.Bitmap
import kotlin.math.abs

data class VisualEvidence(val score: Double, val evaluator: String, val matched: Boolean)

/** Deterministic, offline image-quality evaluator. The threshold belongs to the user's typed rule. */
class ObscuredEvaluator {
    val version = "edge-flat-v1"

    fun supports(media: VideoItem): Boolean =
        media.mimeType?.startsWith("image/") == true && media.isCurrentlyAvailable && !media.isPending && !media.isTrashed

    fun evaluate(bitmap: Bitmap, threshold: Double): VisualEvidence {
        require(threshold in 0.0..1.0)
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return VisualEvidence(1.0, version, true)
        val stepX = maxOf(1, width / 96)
        val stepY = maxOf(1, height / 96)
        var edges = 0.0
        var samples = 0
        var y = stepY
        while (y < height - stepY) {
            var x = stepX
            while (x < width - stepX) {
                val center = luminance(bitmap.getPixel(x, y))
                edges += abs(center - luminance(bitmap.getPixel(x + stepX, y)))
                edges += abs(center - luminance(bitmap.getPixel(x, y + stepY)))
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return VisualEvidence(1.0, version, true)
        val edgeQuality = (edges / (samples * 2.0 * 255.0)).coerceIn(0.0, 1.0)
        val score = (1.0 - edgeQuality * 4.0).coerceIn(0.0, 1.0)
        return VisualEvidence(score, version, score >= threshold)
    }

    private fun luminance(pixel: Int): Int {
        val r = pixel shr 16 and 0xff
        val g = pixel shr 8 and 0xff
        val b = pixel and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
