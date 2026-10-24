package com.imorpher.vectormorph.core.geometry

/**
 * Intelligent curve subdivision.
 *
 * Morphing two contours requires the same number of cubic segments on both sides. Rather than
 * blindly duplicating commands, segments are subdivided with exact de Casteljau splits — the
 * resulting curve is mathematically identical to the original — and the *number of pieces each
 * segment is split into is proportional to its arc length*, so corresponding points sit at
 * matching distances along both shapes. This is what makes morphs look smooth instead of
 * rubber-banding.
 */
object Subdivider {

    /**
     * Brings [contour] to exactly [target] segments, distributing splits by arc length.
     * Returns the original instance when already compatible.
     */
    fun equalize(contour: ContourData, target: Int): ContourData {
        val current = contour.segments.size
        if (target <= 0 || current == target || current == 0) return contour
        require(target >= current) { "Cannot reduce segments ($current → $target) without geometry loss" }

        val arc = measureContour(contour, samplesPerSegment = 12)
        val counts = allocateCounts(arc.segmentLengths, arc.totalLength, target)

        val out = ArrayList<Cubic>(target)
        for ((i, seg) in contour.segments.withIndex()) {
            val k = counts[i]
            if (k <= 1) {
                out.add(seg)
            } else {
                out.addAll(splitByArcLength(seg, k))
            }
        }
        return ContourData(out, contour.closed)
    }

    /**
     * Splits [seg] into [k] pieces at equal arc-length fractions. Piece boundaries are found on
     * a sampled arc-length table, then exact de Casteljau splits cut the curve at those
     * parameters. Geometry is preserved exactly.
     */
    fun splitByArcLength(seg: Cubic, k: Int): List<Cubic> {
        if (k <= 1) return listOf(seg)
        // 1) build an arc-length table for this single segment
        val samples = 24
        val xs = FloatArray(samples + 1)
        val ys = FloatArray(samples + 1)
        val cum = FloatArray(samples + 1)
        val p = FloatArray(2)
        var total = 0f
        for (i in 0..samples) {
            seg.pointAt(i.toFloat() / samples, p)
            xs[i] = p[0]; ys[i] = p[1]
            if (i > 0) {
                val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]
                total += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            cum[i] = total
        }
        // 2) find parameters at equal arc fractions
        val params = FloatArray(k + 1)
        params[0] = 0f; params[k] = 1f
        var sampleIdx = 0
        for (j in 1 until k) {
            val targetLen = total * j / k
            while (sampleIdx < samples && cum[sampleIdx + 1] < targetLen) sampleIdx++
            val seg0 = cum[sampleIdx]
            val seg1 = cum[sampleIdx + 1]
            val localT = if (seg1 - seg0 <= 1e-9f) 0f else (targetLen - seg0) / (seg1 - seg0)
            val globalIdx = sampleIdx.toFloat() + localT
            params[j] = (globalIdx / samples).coerceIn(0f, 1f)
        }
        // ensure strictly increasing
        for (j in 1 until params.size) {
            if (params[j] <= params[j - 1]) params[j] = (params[j - 1] + 1e-4f).coerceAtMost(1f)
        }
        // 3) exact sequential splits
        val pieces = ArrayList<Cubic>(k)
        var remainder = seg
        var consumed = 0f
        for (j in 1 until k) {
            val localT = if (params[j] >= 1f) 1f else (params[j] - consumed) / (1f - consumed)
            val (left, right) = remainder.split(localT.coerceIn(0f, 1f))
            pieces.add(left)
            remainder = right
            consumed = params[j]
        }
        pieces.add(remainder)
        return pieces
    }

    /**
     * Allocates how many pieces each segment becomes: proportional to arc length with a
     * largest-remainder fix-up so the total is exactly [target] and every segment keeps ≥ 1 piece.
     */
    internal fun allocateCounts(segmentLengths: FloatArray, totalLength: Float, target: Int): IntArray {
        val n = segmentLengths.size
        val counts = IntArray(n) { 1 }
        if (target <= n || totalLength <= 0f) return counts
        var remaining = target - n
        if (remaining <= 0) return counts

        // proportional allocation
        val raw = FloatArray(n) { segmentLengths[it] / totalLength * remaining }
        val floors = IntArray(n) { raw[it].toInt().coerceAtLeast(0) }
        var allocated = 0
        for (i in 0 until n) {
            counts[i] += floors[i]
            allocated += floors[i]
        }
        remaining -= allocated
        // distribute leftovers to the segments with the largest fractional remainders
        if (remaining > 0) {
            val order = (0 until n).sortedByDescending { raw[it] - raw[it].toInt() }
            var idx = 0
            while (remaining > 0 && order.isNotEmpty()) {
                counts[order[idx % order.size]]++
                remaining--
                idx++
            }
        }
        return counts
    }

    /**
     * Equalizes both contours to a common segment count = max(a, b).
     * Returns (equalizedA, equalizedB).
     */
    fun equalizePair(a: ContourData, b: ContourData): Pair<ContourData, ContourData> {
        val n = maxOf(a.segments.size, b.segments.size)
        return Subdivider.equalize(a, n) to Subdivider.equalize(b, n)
    }

    /**
     * Reduces [contour] toward [targetSegments] by greedily merging the adjacent cubic pair that
     * costs the least geometric error, stopping early when even the cheapest merge would deviate
     * by more than [tolerancePx].
     *
     * This complements [equalize]: subdivision only ever adds exact split points, while a segment
     * budget has to *remove* detail — here the removal is error-driven, so a budget degrades a
     * shape gracefully instead of discarding arbitrary segments. Endpoints are preserved exactly.
     * The result never exceeds the input's segment count and never goes below
     * [targetSegments].
     */
    fun reduce(contour: ContourData, targetSegments: Int, tolerancePx: Float = 0.25f): ContourData {
        val original = contour.segments
        val target = targetSegments.coerceAtLeast(2)
        if (original.size <= target) return contour

        require(targetSegments <= original.size) {
            "Cannot increase segments with reduce (${original.size} → $targetSegments)"
        }
        val tolerance = tolerancePx.coerceAtLeast(0f)
        val segments = original.toMutableList()

        while (segments.size > target) {
            var bestIndex = -1
            var bestFit: MergedCubic? = null
            for (i in 0 until segments.size - 1) {
                val fit = fitMergedCubic(segments[i], segments[i + 1])
                if (bestFit == null || fit.error < bestFit.error) {
                    bestFit = fit
                    bestIndex = i
                }
            }
            val merge = bestFit ?: break
            // Stop when no pair can be merged without visible damage: the caller (the planner)
            // then re-equalizes both sides so interpolation still sees equal segment counts.
            if (merge.error > tolerance) break
            segments[bestIndex] = merge.cubic
            segments.removeAt(bestIndex + 1)
        }
        return ContourData(segments, contour.closed)
    }

    /** A candidate merge: the fitted cubic plus its maximum sampled deviation in viewport units. */
    private class MergedCubic(val cubic: Cubic, val error: Float)

    /** Samples taken from each side of a merge when fitting/measuring the combined curve. */
    private const val MERGE_SAMPLES_PER_SIDE = 10

    /**
     * Fits one cubic to the union of [a] and [b] (a covering the first half of the parameter
     * range, b the second) and reports its worst sampled deviation.
     *
     * The fit fixes the endpoints and solves a 2×2 least-squares system for the two interior
     * control points, so tangent direction and handle length adapt to both source curves.
     */
    private fun fitMergedCubic(a: Cubic, b: Cubic): MergedCubic {
        val p0x = a.x0; val p0y = a.y0
        val p3x = b.x3; val p3y = b.y3

        // 2×2 normal equations for the interior handles: m11*c1 + m12*c2 = r1, same for y.
        var m11 = 0f; var m12 = 0f; var m22 = 0f
        var rx = 0f; var ry = 0f
        var sx = 0f; var sy = 0f
        val pointsX = FloatArray(MERGE_SAMPLES_PER_SIDE * 2 + 2)
        val pointsY = FloatArray(MERGE_SAMPLES_PER_SIDE * 2 + 2)
        val params = FloatArray(MERGE_SAMPLES_PER_SIDE * 2 + 2)
        val p = FloatArray(2)
        var count = 0
        for (i in 0..MERGE_SAMPLES_PER_SIDE) {
            val local = i.toFloat() / MERGE_SAMPLES_PER_SIDE
            a.pointAt(local, p)
            params[count] = local * 0.5f; pointsX[count] = p[0]; pointsY[count] = p[1]; count++
        }
        for (i in 1..MERGE_SAMPLES_PER_SIDE) {
            val local = i.toFloat() / MERGE_SAMPLES_PER_SIDE
            b.pointAt(local, p)
            params[count] = 0.5f + local * 0.5f; pointsX[count] = p[0]; pointsY[count] = p[1]; count++
        }
        for (i in 0 until count) {
            val t = params[i]
            val mt = 1f - t
            val b0 = mt * mt * mt
            val b1 = 3f * mt * mt * t
            val b2 = 3f * mt * t * t
            val b3 = t * t * t
            m11 += b1 * b1; m12 += b1 * b2; m22 += b2 * b2
            val baseX = pointsX[i] - b0 * p0x - b3 * p3x
            val baseY = pointsY[i] - b0 * p0y - b3 * p3y
            rx += b1 * baseX; ry += b1 * baseY
            sx += b2 * baseX; sy += b2 * baseY
        }

        val det = m11 * m22 - m12 * m12
        val c1x: Float; val c1y: Float; val c2x: Float; val c2y: Float
        if (kotlin.math.abs(det) < 1e-9f) {
            // Degenerate parameter distribution: fall back to preserving both outer tangents.
            c1x = p0x + (a.x1 - a.x0)
            c1y = p0y + (a.y1 - a.y0)
            c2x = p3x + (b.x2 - b.x3)
            c2y = p3y + (b.y2 - b.y3)
        } else {
            c1x = (rx * m22 - sx * m12) / det
            c1y = (ry * m22 - sy * m12) / det
            c2x = (sx * m11 - rx * m12) / det
            c2y = (sy * m11 - ry * m12) / det
        }

        val fitted = Cubic(p0x, p0y, c1x, c1y, c2x, c2y, p3x, p3y)
        var error = 0f
        for (i in 0 until count) {
            fitted.pointAt(params[i], p)
            val dx = p[0] - pointsX[i]
            val dy = p[1] - pointsY[i]
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance > error) error = distance
        }
        return MergedCubic(fitted, error)
    }
}
