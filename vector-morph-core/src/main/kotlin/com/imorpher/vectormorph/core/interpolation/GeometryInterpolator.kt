package com.imorpher.vectormorph.core.interpolation

import com.imorpher.vectormorph.core.geometry.ArcLengthData

/**
 * Packed-geometry interpolation. Both sides of an aligned contour pair have identical segment
 * counts (8 floats each), so the morph is a pure control-point lerp into a reusable buffer.
 *
 * This is the only per-frame geometry math in the engine — no allocation, no measurement.
 */
object GeometryInterpolator {

    /** Lerps [from] into [out] at fraction [t]; [out] must be at least as long as [from]. */
    fun lerpCoords(from: FloatArray, to: FloatArray, t: Float, out: FloatArray) {
        val n = minOf(from.size, to.size, out.size)
        val mt = 1f - t
        for (i in 0 until n) {
            out[i] = from[i] * mt + to[i] * t
        }
    }

    /** Packs a contour's segments into a flat 8-floats-per-segment array. */
    fun pack(segments: List<com.imorpher.vectormorph.core.geometry.Cubic>): FloatArray {
        val out = FloatArray(segments.size * 8)
        for ((i, s) in segments.withIndex()) {
            val o = i * 8
            out[o] = s.x0; out[o + 1] = s.y0
            out[o + 2] = s.x1; out[o + 3] = s.y1
            out[o + 4] = s.x2; out[o + 5] = s.y2
            out[o + 6] = s.x3; out[o + 7] = s.y3
        }
        return out
    }

    /**
     * Maps a normalized draw progress to a normalized arc distance fraction according to
     * [mode]. BY_PATH_LENGTH/UNIFORM are linear (dash effects are arc-length based natively,
     * giving constant speed for free); BY_COMMAND lets every segment take equal time;
     * BY_PATH lets every contour take equal time.
     */
    fun mapDrawProgress(
        progress: Float,
        mode: com.imorpher.vectormorph.core.model.TimingMode,
        arc: ArcLengthData,
        contourIndex: Int,
        contourCount: Int,
    ): Float {
        if (arc.totalLength <= 0f || arc.segmentLengths.isEmpty()) return progress
        return when (mode) {
            com.imorpher.vectormorph.core.model.TimingMode.BY_PATH_LENGTH,
            com.imorpher.vectormorph.core.model.TimingMode.UNIFORM,
            -> progress

            com.imorpher.vectormorph.core.model.TimingMode.BY_COMMAND -> {
                val n = arc.segmentLengths.size
                val scaled = progress * n
                val seg = scaled.toInt().coerceIn(0, n - 1)
                val local = (scaled - seg).coerceIn(0f, 1f)
                val before = arc.cumulativeLength[seg]
                (before + local * arc.segmentLengths[seg]) / arc.totalLength
            }

            com.imorpher.vectormorph.core.model.TimingMode.BY_PATH -> {
                if (contourCount <= 1) return progress
                val slice = 1f / contourCount
                val local = ((progress - contourIndex * slice) / slice).coerceIn(0f, 1f)
                local
            }
        }
    }
}
