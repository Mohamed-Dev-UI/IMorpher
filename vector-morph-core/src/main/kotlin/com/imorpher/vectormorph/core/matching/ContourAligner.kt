package com.imorpher.vectormorph.core.matching

import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.measureContour
import com.imorpher.vectormorph.core.geometry.signedArea
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.StartPointStrategy
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Contour-level alignment: winding normalization and start-point selection for closed contours.
 *
 * This is where morph quality is won or lost. Two identical squares whose paths begin at
 * different corners must not "rotate" through each other; two contours wound in opposite
 * directions must not twist inside out. Both artifacts are eliminated here, before any
 * subdivision happens.
 */
object ContourAligner {

    /**
     * Normalizes winding of [target] relative to [source] per [strategy].
     * Returns (source, target, targetWasReversed).
     */
    fun normalizeDirection(
        source: ContourData,
        target: ContourData,
        strategy: PathDirectionStrategy,
    ): Triple<ContourData, ContourData, Boolean> {
        val srcArea = signedArea(source)
        val tgtArea = signedArea(target)
        // Sign convention: shoelace area is positive for clockwise traversal in a Y-down
        // (screen) coordinate system, negative for counter-clockwise.
        return when (strategy) {
            PathDirectionStrategy.PRESERVE -> Triple(source, target, false)
            PathDirectionStrategy.CLOCKWISE -> {
                val s = if (srcArea < 0f) source.reversed() else source
                val t = if (tgtArea < 0f) target.reversed() else target
                Triple(s, t, tgtArea < 0f)
            }
            PathDirectionStrategy.COUNTER_CLOCKWISE -> {
                val s = if (srcArea > 0f) source.reversed() else source
                val t = if (tgtArea > 0f) target.reversed() else target
                Triple(s, t, tgtArea > 0f)
            }
            PathDirectionStrategy.AUTO -> {
                // Match the target's winding to the source so correspondence doesn't twist.
                if (srcArea * tgtArea < 0f) {
                    Triple(source, target.reversed(), true)
                } else {
                    Triple(source, target, false)
                }
            }
        }
    }

    /**
     * Aligns the start point of a closed contour pair.
     *
     * [AUTO] evaluates every candidate start vertex on the target and keeps the rotation that
     * minimizes the summed distance between arc-length-corresponding sample points — the direct
     * cure for rotational deformation. [BEST_GEOMETRIC_MATCH] aligns by dominant feature
     * (topmost point) which is more robust when the shapes themselves rotate.
     *
     * Returns (source, target, appliedOffsetFraction).
     */
    fun alignStartPoints(
        source: ContourData,
        target: ContourData,
        strategy: StartPointStrategy,
        customSourceOffset: Float? = null,
        customTargetOffset: Float? = null,
    ): Triple<ContourData, ContourData, Float> {
        if (!source.closed || !target.closed || source.segments.isEmpty() || target.segments.isEmpty()) {
            return Triple(source, target, 0f)
        }
        return when (strategy) {
            StartPointStrategy.MATCH_SOURCE -> {
                val t = rotateToNearest(target, source.startX, source.startY)
                Triple(source, t, rotationOffsetFraction(target, t))
            }
            StartPointStrategy.MATCH_TARGET -> {
                val s = rotateToNearest(source, target.startX, target.startY)
                Triple(s, target, rotationOffsetFraction(source, s))
            }
            StartPointStrategy.BEST_GEOMETRIC_MATCH -> {
                // align both to their topmost (then leftmost) boundary point
                val s = rotateToExtreme(source)
                val t = rotateToExtreme(target)
                Triple(s, t, rotationOffsetFraction(source, t))
            }
            StartPointStrategy.CUSTOM -> {
                val s = customSourceOffset?.let { rotateToArcFraction(source, it) } ?: source
                val t = customTargetOffset?.let { rotateToArcFraction(target, it) } ?: target
                Triple(s, t, customTargetOffset ?: 0f)
            }
            StartPointStrategy.AUTO -> autoAlign(source, target)
        }
    }

    // ------------------------------------------------------------------ AUTO

    internal fun autoAlign(source: ContourData, target: ContourData): Triple<ContourData, ContourData, Float> {
        // Try every boundary vertex of the target as its start point; keep the rotation that
        // minimizes correspondence error against the source (sampled at equal arc fractions).
        val srcArc = measureContour(source)
        val sampleCount = min(24, maxOf(source.segments.size, target.segments.size) * 2).coerceAtLeast(8)
        val srcSamples = sampleByArcFraction(source, srcArc, sampleCount)

        var bestTarget = target
        var bestError = Float.MAX_VALUE
        var bestOffset = 0f

        for ((segIdx, _) in target.segments.withIndex()) {
            val rotated = target.rotateStart(segIdx, 0f)
            val rotArc = measureContour(rotated)
            val tgtSamples = sampleByArcFraction(rotated, rotArc, sampleCount)
            var error = 0f
            for (k in 0 until sampleCount) {
                val dx = srcSamples[k][0] - tgtSamples[k][0]
                val dy = srcSamples[k][1] - tgtSamples[k][1]
                error += dx * dx + dy * dy
            }
            if (error < bestError) {
                bestError = error
                bestTarget = rotated
                bestOffset = segIdx.toFloat() / target.segments.size
            }
        }

        // refine within the winning first segment: try midpoint splits too
        if (target.segments.isNotEmpty()) {
            for (t in listOf(0.25f, 0.5f, 0.75f)) {
                val rotated = target.rotateStart(0, t)
                val rotArc = measureContour(rotated)
                val tgtSamples = sampleByArcFraction(rotated, rotArc, sampleCount)
                var error = 0f
                for (k in 0 until sampleCount) {
                    val dx = srcSamples[k][0] - tgtSamples[k][0]
                    val dy = srcSamples[k][1] - tgtSamples[k][1]
                    error += dx * dx + dy * dy
                }
                if (error < bestError) {
                    bestError = error
                    bestTarget = rotated
                    bestOffset = t / target.segments.size
                }
            }
        }

        return Triple(source, bestTarget, bestOffset)
    }

    internal fun sampleByArcFraction(
        contour: ContourData,
        arc: com.imorpher.vectormorph.core.geometry.ArcLengthData,
        count: Int,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>(count)
        if (arc.totalLength <= 0f || contour.segments.isEmpty()) {
            repeat(count) { out.add(floatArrayOf(0f, 0f)) }
            return out
        }
        val point = FloatArray(2)
        for (i in 0 until count) {
            val fraction = i.toFloat() / count
            val targetLen = fraction * arc.totalLength
            // find segment
            var lo = 0
            var hi = arc.segmentLengths.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (arc.cumulativeLength[mid + 1] < targetLen) lo = mid + 1 else hi = mid
            }
            val segIndex = lo.coerceAtMost(arc.segmentLengths.size - 1)
            val segStart = arc.cumulativeLength[segIndex]
            val segLen = arc.segmentLengths[segIndex]
            val t = if (segLen <= 1e-6f) 0f else ((targetLen - segStart) / segLen).coerceIn(0f, 1f)
            contour.segments[segIndex].pointAt(t, point)
            out.add(floatArrayOf(point[0], point[1]))
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    internal fun rotateToNearest(contour: ContourData, x: Float, y: Float): ContourData {
        var bestSeg = 0
        var bestT = 0f
        var bestDist = Float.MAX_VALUE
        val p = FloatArray(2)
        for ((si, seg) in contour.segments.withIndex()) {
            for (k in 0..4) {
                seg.pointAt(k.toFloat() / 4f, p)
                val dx = p[0] - x; val dy = p[1] - y
                val d = dx * dx + dy * dy
                if (d < bestDist) {
                    bestDist = d; bestSeg = si; bestT = k.toFloat() / 4f
                }
            }
        }
        return contour.rotateStart(bestSeg, bestT)
    }

    /** Rotates the contour to start at its topmost (then leftmost) boundary point. */
    internal fun rotateToExtreme(contour: ContourData): ContourData {
        var bestSeg = 0
        var bestT = 0f
        var bestY = Float.MAX_VALUE
        var bestX = Float.MAX_VALUE
        val p = FloatArray(2)
        for ((si, seg) in contour.segments.withIndex()) {
            seg.pointAt(0f, p)
            if (p[1] < bestY - 1e-4f || (abs(p[1] - bestY) <= 1e-4f && p[0] < bestX - 1e-4f)) {
                bestY = p[1]; bestX = p[0]; bestSeg = si; bestT = 0f
            }
        }
        return contour.rotateStart(bestSeg, bestT)
    }

    internal fun rotateToArcFraction(contour: ContourData, fraction: Float): ContourData {
        val arc = measureContour(contour)
        if (arc.totalLength <= 0f) return contour
        val target = fraction.coerceIn(0f, 1f) * arc.totalLength
        var acc = 0f
        for ((i, len) in arc.segmentLengths.withIndex()) {
            if (acc + len >= target) {
                val t = if (len <= 1e-6f) 0f else (target - acc) / len
                return contour.rotateStart(i, t.coerceIn(0f, 1f))
            }
            acc += len
        }
        return contour
    }

    private fun rotationOffsetFraction(original: ContourData, rotated: ContourData): Float {
        // fraction of total arc length represented by the new start point
        val originalArc = measureContour(original)
        val start = rotated.startX to rotated.startY
        var acc = 0f
        for ((i, seg) in original.segments.withIndex()) {
            if (abs(seg.x0 - start.first) < 1e-4f && abs(seg.y0 - start.second) < 1e-4f) {
                return if (originalArc.totalLength <= 0f) 0f else acc / originalArc.totalLength
            }
            acc += originalArc.segmentLengths[i]
        }
        return 0f
    }

    /** Distance between arc-corresponding samples; used by tests to assert alignment quality. */
    fun correspondenceError(a: ContourData, b: ContourData, count: Int = 24): Float {
        val arcA = measureContour(a)
        val arcB = measureContour(b)
        val sa = sampleByArcFraction(a, arcA, count)
        val sb = sampleByArcFraction(b, arcB, count)
        var e = 0f
        for (i in 0 until count) {
            val dx = sa[i][0] - sb[i][0]
            val dy = sa[i][1] - sb[i][1]
            e += sqrt(dx * dx + dy * dy)
        }
        return e / count
    }
}
