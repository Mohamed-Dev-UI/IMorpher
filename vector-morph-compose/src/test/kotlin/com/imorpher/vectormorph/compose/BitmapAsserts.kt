package com.imorpher.vectormorph.compose

import android.graphics.Bitmap

/** Shared pixel-comparison helpers for the Robolectric render tests. */
internal object BitmapAsserts {

    /** 0 = identical pixels, 1 = maximally different. */
    fun meanAbsDifference(a: Bitmap, b: Bitmap): Float {
        require(a.width == b.width && a.height == b.height)
        var sum = 0L
        val total = a.width * a.height
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += kotlin.math.abs((pa ushr 24 and 0xFF) - (pb ushr 24 and 0xFF)) +
                    kotlin.math.abs((pa ushr 16 and 0xFF) - (pb ushr 16 and 0xFF)) +
                    kotlin.math.abs((pa ushr 8 and 0xFF) - (pb ushr 8 and 0xFF)) +
                    kotlin.math.abs(pa and 0xFF - pb and 0xFF)
            }
        }
        return sum / (255f * 4f * total)
    }

    fun paintedCount(bitmap: Bitmap): Int {
        var painted = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) painted++
            }
        }
        return painted
    }

    fun meanAlpha(bitmap: Bitmap): Float {
        var sum = 0L
        val total = bitmap.width * bitmap.height
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                sum += (bitmap.getPixel(x, y) ushr 24) and 0xFF
            }
        }
        return sum / (255f * total)
    }

    /**
     * Where a frame's lit pixels landed and how many there are: the normalized (0..1) centroid
     * of every non-transparent pixel + its count. Centroids are NaN when nothing was painted;
     * assert [count] > 0 before trusting them. Reveals anchor probes via `litCentroidX/Y()`.
     */
    fun litPixelsKey(bitmap: Bitmap): LitPixelsKey {
        var count = 0
        var sumX = 0L
        var sumY = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) {
                    count++
                    sumX += x
                    sumY += y
                }
            }
        }
        if (count == 0) return LitPixelsKey(0, Float.NaN, Float.NaN)
        return LitPixelsKey(
            count,
            (sumX.toFloat() / count + 0.5f) / bitmap.width,
            (sumY.toFloat() / count + 0.5f) / bitmap.height,
        )
    }
}

/** Normalized lit-pixel centroid + count (see [BitmapAsserts.litPixelsKey]). */
internal data class LitPixelsKey(val count: Int, val centroidX: Float, val centroidY: Float)

/** Receiver-style form of [BitmapAsserts.litPixelsKey]. */
internal fun Bitmap.litPixelsKey(): LitPixelsKey = BitmapAsserts.litPixelsKey(this)

/** Horizontal (0..1) centroid of the frame's lit pixels. */
internal fun Bitmap.litCentroidX(): Float = BitmapAsserts.litPixelsKey(this).centroidX

/** Vertical (0..1) centroid of the frame's lit pixels. */
internal fun Bitmap.litCentroidY(): Float = BitmapAsserts.litPixelsKey(this).centroidY

