package com.imorpher.vectormorph.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc-length-balanced subdivision — what keeps morph correspondence smooth. */
class SubdividerTest {

    @Test
    fun allocateCountsSumToTargetAndStayPositive() {
        val lengths = floatArrayOf(1f, 9f, 2f, 8f)
        val counts = Subdivider.allocateCounts(lengths, totalLength = 20f, target = 12)
        assertEquals(4, counts.size)
        assertEquals(12, counts.sum())
        assertTrue(counts.all { it >= 1 })
        // the long segments dominate the allocation
        assertTrue("long segment must receive more pieces: ${counts.toList()}", counts[1] > counts[0])
        assertTrue(counts[3] > counts[2])
    }

    @Test
    fun allocateCountsWithoutSpareBudgetKeepsOnePieceEach() {
        val counts = Subdivider.allocateCounts(floatArrayOf(1f, 2f), totalLength = 3f, target = 2)
        assertEquals(listOf(1, 1), counts.toList())
        // degenerate lengths still yield a valid distribution
        val zeroed = Subdivider.allocateCounts(FloatArray(3), totalLength = 0f, target = 7)
        assertEquals(listOf(1, 1, 1), zeroed.toList())
    }

    @Test
    fun equalizePreservesTheOriginalCurve() {
        val original = ContourData(
            listOf(
                Cubic(0f, 0f, 2f, 14f, 10f, -8f, 16f, 6f),
                Cubic.line(16f, 6f, 30f, 6f),
            ),
            closed = false,
        )
        val equalized = Subdivider.equalize(original, 9)

        assertEquals(9, equalized.segments.size)
        assertFalse(equalized.closed)
        assertEquals(original.startX, equalized.startX, 1e-5f)
        assertEquals(original.startY, equalized.startY, 1e-5f)
        assertEquals(original.endX, equalized.endX, 1e-5f)
        assertEquals(original.endY, equalized.endY, 1e-5f)

        // every sample of the subdivision lies on the original curve: compare against
        // a densely flattened version of the original (nearestPoint is too coarse here)
        val dense = ArrayList<FloatArray>(600)
        val buf = FloatArray(2 * 129)
        for (seg in original.segments) {
            val m = flattenCubic(seg, buf, 0, 128, true)
            for (k in 0 until m) dense.add(floatArrayOf(buf[2 * k], buf[2 * k + 1]))
        }
        val point = FloatArray(2)
        for ((i, seg) in equalized.segments.withIndex()) {
            seg.pointAt(0.5f, point)
            var best = Float.MAX_VALUE
            for (sample in dense) {
                val dx = point[0] - sample[0]
                val dy = point[1] - sample[1]
                best = minOf(best, dx * dx + dy * dy)
            }
            assertTrue("segment $i midpoint off the original curve ($best)", best < 0.02f)
        }
        // length is preserved
        val before = measureContour(original, samplesPerSegment = 64).totalLength
        val after = measureContour(equalized, samplesPerSegment = 64).totalLength
        assertEquals(before, after, before * 0.002f)
    }

    @Test
    fun equalizeReturnsTheSameInstanceWhenCompatible() {
        val contour = ContourData(listOf(Cubic.line(0f, 0f, 5f, 5f)), closed = false)
        assertTrue(Subdivider.equalize(contour, 1) === contour)
        assertTrue(Subdivider.equalize(contour, 0) === contour)
        val empty = ContourData(emptyList(), closed = true)
        assertTrue(Subdivider.equalize(empty, 4) === empty)
    }

    @Test
    fun equalizeKeepsClosureAndRotatedStartsAligned() {
        val closed = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f), floatArrayOf(0f, 10f)),
            closed = true,
        )
        val equalized = Subdivider.equalize(closed, 8)
        assertTrue(equalized.closed)
        assertEquals(8, equalized.segments.size)
        assertEquals(closed.startX, equalized.startX, 1e-5f)
        // wraps back onto its own start
        assertEquals(equalized.startX, equalized.endX, 1e-5f)
    }

    @Test
    fun splitByArcLengthProducesEqualArcPieces() {
        val curve = Cubic(0f, 0f, 0f, 30f, 30f, -15f, 24f, 0f)
        val pieces = Subdivider.splitByArcLength(curve, 4)
        assertEquals(4, pieces.size)
        val lengths = pieces.map { measureContour(ContourData(listOf(it), false), samplesPerSegment = 48).totalLength }
        val average = lengths.sum() / 4
        lengths.forEach { length ->
            assertTrue("piece length $length vs average $average", kotlin.math.abs(length - average) < average * 0.05f)
        }
        // continuity
        pieces.zipWithNext().forEach { (a, b) ->
            assertEquals(a.x3, b.x0, 1e-5f)
            assertEquals(a.y3, b.y0, 1e-5f)
        }
    }

    @Test
    fun equalizePairBringsBothSidesToTheSameSegmentCount() {
        val a = ContourData(listOf(Cubic.line(0f, 0f, 10f, 0f)), closed = false)
        val b = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f), floatArrayOf(0f, 10f)),
            closed = true,
        )
        val (equalizedA, equalizedB) = Subdivider.equalizePair(a, b)
        assertEquals(equalizedA.segments.size, equalizedB.segments.size)
        assertEquals(4, equalizedB.segments.size)
        assertEquals(4, equalizedA.segments.size)
    }
}
