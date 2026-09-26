package com.imorpher.vectormorph.core.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A single cubic Bézier segment in absolute coordinates.
 *
 * Every geometry the engine works with is normalized to a stream of cubic segments
 * (lines, quads, reflective curves and arcs are converted losslessly at compile time),
 * so interpolation only ever has to lerp eight floats per segment.
 */
data class Cubic(
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val x3: Float, val y3: Float,
) {
    fun toArray(out: FloatArray = FloatArray(8)): FloatArray {
        out[0] = x0; out[1] = y0
        out[2] = x1; out[3] = y1
        out[4] = x2; out[5] = y2
        out[6] = x3; out[7] = y3
        return out
    }

    /** Point on the curve at parameter [t] (de Casteljau evaluation). */
    fun pointAt(t: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val mt = 1f - t
        val a = mt * mt * mt
        val b = 3f * mt * mt * t
        val c = 3f * mt * t * t
        val d = t * t * t
        out[0] = a * x0 + b * x1 + c * x2 + d * x3
        out[1] = a * y0 + b * y1 + c * y2 + d * y3
        return out
    }

    /**
     * Exact de Casteljau split at parameter [t]. Returns (left, right).
     * The union of the two halves traces the identical curve — geometry is preserved bit-for-bit
     * up to floating point rounding.
     */
    fun split(t: Float): Pair<Cubic, Cubic> {
        val mt = 1f - t
        val x01 = mt * x0 + t * x1; val y01 = mt * y0 + t * y1
        val x12 = mt * x1 + t * x2; val y12 = mt * y1 + t * y2
        val x23 = mt * x2 + t * x3; val y23 = mt * y2 + t * y3
        val x012 = mt * x01 + t * x12; val y012 = mt * y01 + t * y12
        val x123 = mt * x12 + t * x23; val y123 = mt * y12 + t * y23
        val xm = mt * x012 + t * x123; val ym = mt * y012 + t * y123
        return Cubic(x0, y0, x01, y01, x012, y012, xm, ym) to
            Cubic(xm, ym, x123, y123, x23, y23, x3, y3)
    }

    /** The same curve traversed in the opposite direction. */
    fun reversed(): Cubic = Cubic(x3, y3, x2, y2, x1, y1, x0, y0)

    /** Flat chord length — cheap lower bound of the arc length. */
    fun chordLength(): Float {
        val dx = x3 - x0
        val dy = y3 - y0
        return sqrt(dx * dx + dy * dy)
    }

    /** Rough "control polygon" length — upper bound. Good enough for relative comparisons. */
    fun controlLength(): Float {
        val d1x = x1 - x0; val d1y = y1 - y0
        val d2x = x2 - x1; val d2y = y2 - y1
        val d3x = x3 - x2; val d3y = y3 - y2
        return sqrt(d1x * d1x + d1y * d1y) + sqrt(d2x * d2x + d2y * d2y) + sqrt(d3x * d3x + d3y * d3y)
    }

    companion object {
        /** A straight line expressed as a cubic (control points at thirds). Exact. */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float): Cubic = Cubic(
            x0, y0,
            x0 + (x1 - x0) / 3f, y0 + (y1 - y0) / 3f,
            x0 + 2f * (x1 - x0) / 3f, y0 + 2f * (y1 - y0) / 3f,
            x1, y1,
        )

        /** Quadratic Bézier → cubic Bézier conversion. Exact (standard degree elevation). */
        fun fromQuad(qx: Float, qy: Float, x0: Float, y0: Float, x1: Float, y1: Float): Cubic = Cubic(
            x0, y0,
            x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
            x1 + 2f / 3f * (qx - x1), y1 + 2f / 3f * (qy - y1),
            x1, y1,
        )
    }
}

/** Builds a cubic polyline (flattening) into [out]; returns the number of points written. */
internal fun flattenCubic(
    c: Cubic,
    out: FloatArray,
    offset: Int,
    steps: Int,
    includeStart: Boolean,
): Int {
    val point = FloatArray(2)
    var o = offset
    var from = 1
    if (includeStart) {
        out[o++] = c.x0; out[o++] = c.y0
        from = 1
    } else {
        from = 1
    }
    for (i in from..steps) {
        val t = i.toFloat() / steps
        c.pointAt(t, point)
        out[o++] = point[0]; out[o++] = point[1]
    }
    return (o - offset) / 2
}

/** Absolute value helper kept local so geometry has zero dependencies. */
internal fun fastAbs(v: Float): Float = if (v < 0f) -v else v
internal fun isClose(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps
