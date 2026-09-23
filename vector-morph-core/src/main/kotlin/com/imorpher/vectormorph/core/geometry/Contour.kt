package com.imorpher.vectormorph.core.geometry

import kotlin.math.sqrt

/**
 * One contour (sub-path) of a vector path: a contiguous run of cubic segments starting at
 * `segments[0].x0/y0`. May be closed (the last segment ends where the first begins) or open.
 *
 * All engine geometry is stored in this normalized form. Immutable after construction.
 */
class ContourData(
    val segments: List<Cubic>,
    val closed: Boolean,
) {
    val startX: Float get() = segments.firstOrNull()?.x0 ?: 0f
    val startY: Float get() = segments.firstOrNull()?.y0 ?: 0f
    val endX: Float get() = segments.lastOrNull()?.x3 ?: 0f
    val endY: Float get() = segments.lastOrNull()?.y3 ?: 0f

    val isEmpty: Boolean get() = segments.isEmpty()

    /** Back-to-front points of the contour (start of each segment + final end point). */
    fun boundaryPoints(): List<FloatArray> {
        val pts = ArrayList<FloatArray>(segments.size + 1)
        for (s in segments) pts.add(floatArrayOf(s.x0, s.y0))
        if (segments.isNotEmpty()) pts.add(floatArrayOf(endX, endY))
        return pts
    }

    /** The same curve traversed backwards. Exact — control points are simply swapped. */
    fun reversed(): ContourData {
        if (segments.isEmpty()) return this
        return ContourData(segments.asReversed().map { it.reversed() }, closed)
    }

    /**
     * Rotates the start point of a *closed* contour to (segment index [segIndex], parameter [t]).
     * The geometry is unchanged: the contour is cut open at the chosen point and re-stitched.
     * No-op for open contours (their start point is semantically fixed).
     */
    fun rotateStart(segIndex: Int, t: Float): ContourData {
        if (!closed || segments.isEmpty()) return this
        val i = ((segIndex % segments.size) + segments.size) % segments.size
        val tc = t.coerceIn(0f, 1f)
        if (i == 0 && tc <= 1e-6f) return this
        val (head, tail) = segments[i].split(tc)
        val result = ArrayList<Cubic>(segments.size + 1)
        result.add(tail)
        for (j in (i + 1) until segments.size) result.add(segments[j])
        for (j in 0 until i) result.add(segments[j])
        result.add(head)
        return ContourData(result, closed = true)
    }

    companion object {
        /** Builds a closed contour from points, or an open one. Useful in tests/tools. */
        fun fromPolygon(points: List<FloatArray>, closed: Boolean): ContourData {
            val segs = ArrayList<Cubic>(points.size)
            for (i in 0 until points.size - 1) {
                segs.add(
                    Cubic.line(
                        points[i][0], points[i][1],
                        points[i + 1][0], points[i + 1][1],
                    )
                )
            }
            if (closed && points.size > 1) {
                segs.add(Cubic.line(points.last()[0], points.last()[1], points.first()[0], points.first()[1]))
            }
            return ContourData(segs, closed)
        }
    }
}

/** Signed (shoelace) area of a contour; positive = counter-clockwise in a Y-down coordinate system. */
fun signedArea(contour: ContourData, samplesPerSegment: Int = 8): Float {
    if (contour.segments.isEmpty()) return 0f
    var sum = 0f
    val buf = FloatArray(2 * (samplesPerSegment + 1))
    var prevX = 0f; var prevY = 0f
    var first = true
    for (seg in contour.segments) {
        val n = flattenCubic(seg, buf, 0, samplesPerSegment, includeStart = true)
        var i = 0
        while (i < n) {
            val x = buf[i]; val y = buf[i + 1]
            if (!first) sum += prevX * y - x * prevY
            prevX = x; prevY = y
            first = false
            i += 2
        }
    }
    // close back to start
    if (!contour.closed) {
        sum += prevX * contour.segments[0].y0 - contour.segments[0].x0 * prevY
    }
    return sum * 0.5f
}

/** Axis-aligned bounding box: floatArrayOf(minX, minY, maxX, maxY). */
fun bounds(contour: ContourData): FloatArray {
    if (contour.segments.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    fun track(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }
    for (seg in contour.segments) {
        track(seg.x0, seg.y0)
        track(seg.x3, seg.y3)
        track(seg.x1, seg.y1)
        track(seg.x2, seg.y2)
    }
    return floatArrayOf(minX, minY, maxX, maxY)
}

/** Polygon centroid approximation from flattened samples. floatArrayOf(cx, cy). */
fun centroid(contour: ContourData, samplesPerSegment: Int = 8): FloatArray {
    if (contour.segments.isEmpty()) return floatArrayOf(0f, 0f)
    var sumX = 0f; var sumY = 0f; var n = 0
    val buf = FloatArray(2 * (samplesPerSegment + 1))
    for (seg in contour.segments) {
        val m = flattenCubic(seg, buf, 0, samplesPerSegment, includeStart = true)
        for (i in 0 until m) {
            sumX += buf[2 * i]; sumY += buf[2 * i + 1]; n++
        }
    }
    if (n == 0) return floatArrayOf(0f, 0f)
    return floatArrayOf(sumX / n, sumY / n)
}

/** Point on the contour nearest to ([x], [y]) — searches segment endpoints plus a mid split. */
fun nearestPoint(contour: ContourData, x: Float, y: Float): FloatArray {
    var best = Float.MAX_VALUE
    var bx = 0f; var by = 0f
    var bestSeg = 0; var bestT = 0f
    val p = FloatArray(2)
    val steps = 4
    for ((si, seg) in contour.segments.withIndex()) {
        for (k in 0..steps) {
            seg.pointAt(k.toFloat() / steps, p)
            val dx = p[0] - x; val dy = p[1] - y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; bx = p[0]; by = p[1]; bestSeg = si; bestT = k.toFloat() / steps }
        }
    }
    // refine with one bisection refinement around the best sample
    if (contour.segments.isNotEmpty()) {
        val seg = contour.segments[bestSeg]
        var lo = (bestT - 1f / steps).coerceAtLeast(0f)
        var hi = (bestT + 1f / steps).coerceAtMost(1f)
        repeat(8) {
            val mid = (lo + hi) * 0.5f
            seg.pointAt(mid, p)
            val dx = p[0] - x; val dy = p[1] - y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; bx = p[0]; by = p[1]; bestT = mid }
            // walk towards whichever side is better
            seg.pointAt(lo, p); val dLo = (p[0] - x).let { it * it } + (p[1] - y).let { it * it }
            seg.pointAt(hi, p); val dHi = (p[0] - x).let { it * it } + (p[1] - y).let { it * it }
            if (dLo < dHi) hi = mid else lo = mid
        }
    }
    return floatArrayOf(bx, by, bestSeg.toFloat(), bestT)
}

/**
 * Measures the contour and produces arc-length data.
 *
 * [totalLength] is the true (approximated) polyline length; [cumulativeLength] holds the
 * cumulative length at each segment boundary; [segmentLengths] per segment. Together they drive
 * constant-speed drawing (timing mode BY_PATH_LENGTH) and dash-offset start alignment.
 */
class ArcLengthData(
    val segmentLengths: FloatArray,
    val cumulativeLength: FloatArray,
    val totalLength: Float,
    /** Cumulative sampled distances within each segment, used to invert cubic arc length. */
    val segmentSampleCumulative: List<FloatArray> = emptyList(),
)

fun measureContour(contour: ContourData, samplesPerSegment: Int = 16): ArcLengthData {
    require(samplesPerSegment >= 1) { "samplesPerSegment must be at least 1" }
    val n = contour.segments.size
    val segLens = FloatArray(n)
    val cumulative = FloatArray(n + 1)
    val sampledCumulative = ArrayList<FloatArray>(n)
    var total = 0f
    val buf = FloatArray(2 * (samplesPerSegment + 1))
    for ((i, seg) in contour.segments.withIndex()) {
        val m = flattenCubic(seg, buf, 0, samplesPerSegment, includeStart = true)
        var len = 0f
        val localCumulative = FloatArray(m)
        var px = buf[0]; var py = buf[1]
        for (j in 1 until m) {
            val x = buf[2 * j]; val y = buf[2 * j + 1]
            val dx = x - px; val dy = y - py
            len += sqrt(dx * dx + dy * dy)
            localCumulative[j] = len
            px = x; py = y
        }
        sampledCumulative.add(localCumulative)
        segLens[i] = len
        total += len
        cumulative[i + 1] = total
    }
    return ArcLengthData(segLens, cumulative, total, sampledCumulative)
}

/**
 * Maps a normalized arc-length position (0..1 of total length) to a (segmentIndex, t) pair,
 * enabling constant-speed traversal over the curve.
 */
fun positionAtArcFraction(contour: ContourData, arc: ArcLengthData, fraction: Float): FloatArray {
    if (contour.segments.isEmpty() || arc.totalLength <= 0f) return floatArrayOf(0f, 0f)
    val target = fraction.coerceIn(0f, 1f) * arc.totalLength
    // binary search over cumulative lengths
    var lo = 0; var hi = arc.segmentLengths.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (arc.cumulativeLength[mid + 1] < target) lo = mid + 1 else hi = mid
    }
    val segIndex = lo.coerceAtMost(arc.segmentLengths.size - 1)
    val segStart = arc.cumulativeLength[segIndex]
    val segLen = arc.segmentLengths[segIndex]
    val localDistance = if (segLen <= 1e-6f) 0f else ((target - segStart) / segLen).coerceIn(0f, 1f)
    val samples = arc.segmentSampleCumulative.getOrNull(segIndex)
    val t = if (samples == null || samples.size <= 1 || samples.last() <= 1e-6f) {
        localDistance
    } else {
        val sampleTarget = localDistance * samples.last()
        var sampleLo = 0; var sampleHi = samples.lastIndex
        while (sampleLo + 1 < sampleHi) {
            val mid = (sampleLo + sampleHi) ushr 1
            if (samples[mid] < sampleTarget) sampleLo = mid else sampleHi = mid
        }
        val before = samples[sampleLo]; val after = samples[sampleHi]
        val within = if (after <= before) 0f else (sampleTarget - before) / (after - before)
        (sampleLo + within) / (samples.size - 1)
    }
    return floatArrayOf(segIndex.toFloat(), t)
}

/**
 * Returns the exact cubic geometry between two normalized arc-length fractions. Segment cuts use
 * de Casteljau subdivision, so the result follows the original curve rather than a flattened
 * approximation. A closed contour wraps when [endFraction] is less than [startFraction].
 */
fun ContourData.sliceArcFraction(
    startFraction: Float,
    endFraction: Float,
    arc: ArcLengthData = measureContour(this),
): ContourData {
    if (segments.isEmpty() || arc.totalLength <= 1e-6f) return ContourData(emptyList(), false)
    val start = startFraction.coerceIn(0f, 1f)
    val end = endFraction.coerceIn(0f, 1f)
    if (kotlin.math.abs(end - start) <= 1e-6f) return ContourData(emptyList(), false)

    fun linearSlice(from: Float, to: Float): List<Cubic> {
        if (to <= from) return emptyList()
        val a = positionAtArcFraction(this, arc, from)
        val b = positionAtArcFraction(this, arc, to)
        val firstSegment = a[0].toInt(); val lastSegment = b[0].toInt()
        val out = ArrayList<Cubic>(lastSegment - firstSegment + 1)
        for (index in firstSegment..lastSegment) {
            val t0 = if (index == firstSegment) a[1] else 0f
            val t1 = if (index == lastSegment) b[1] else 1f
            if (t1 <= t0) continue
            var piece = segments[index]
            if (t0 > 0f) piece = piece.split(t0).second
            if (t1 < 1f) {
                val relativeEnd = ((t1 - t0) / (1f - t0)).coerceIn(0f, 1f)
                piece = piece.split(relativeEnd).first
            }
            out.add(piece)
        }
        return out
    }

    val pieces = if (end > start) linearSlice(start, end) else if (closed) {
        linearSlice(start, 1f) + linearSlice(0f, end)
    } else emptyList()
    return ContourData(pieces, closed = false)
}

/** Finds the arc-length fraction (0..1) of the point on the contour nearest to ([x],[y]). */
fun arcFractionNear(contour: ContourData, arc: ArcLengthData, x: Float, y: Float): Float {
    val np = nearestPoint(contour, x, y)
    val segIndex = np[2].toInt()
    val t = np[3]
    if (contour.segments.isEmpty()) return 0f
    val before = arc.cumulativeLength[segIndex.coerceIn(0, arc.segmentLengths.size - 1)]
    val segLen = arc.segmentLengths[segIndex.coerceIn(0, arc.segmentLengths.size - 1)]
    return ((before + segLen * t) / arc.totalLength).coerceIn(0f, 1f)
}
