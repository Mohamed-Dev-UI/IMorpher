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
}
