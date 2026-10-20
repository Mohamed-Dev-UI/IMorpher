package com.imorpher.vectormorph.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Elliptical arc → cubic conversion (W3C F.6.5). */
class ArcToCubicTest {

    private fun radialError(segments: List<Cubic>, cx: Float, cy: Float, radius: Float): Float {
        var maxError = 0f
        val point = FloatArray(2)
        for (seg in segments) {
            for (i in 0..8) {
                seg.pointAt(i / 8f, point)
                val dx = point[0] - cx
                val dy = point[1] - cy
                maxError = maxOf(maxError, kotlin.math.abs(sqrt(dx * dx + dy * dy) - radius))
            }
        }
        return maxError
    }

    private fun sqrt(v: Float): Float = kotlin.math.sqrt(v)

    /** Half-way point of a multi-segment arc, by arc length. */
    private fun arcMidpoint(segments: List<Cubic>): FloatArray {
        val contour = ContourData(segments, closed = false)
        val arc = measureContour(contour)
        val at = positionAtArcFraction(contour, arc, 0.5f)
        val point = FloatArray(2)
        contour.segments[at[0].toInt()].pointAt(at[1], point)
        return point
    }

    @Test
    fun quarterCircleStaysOnTheCircleAndHitsItsEndpoint() {
        // (10, 0) → (0, 10) on a circle of radius 10 centered at the origin, sweep positive.
        val segments = ArcToCubic.convert(10f, 10f, 0f, largeArc = false, sweep = true, 10f, 0f, 0f, 10f)
        // a 90° arc may split into 1-2 pieces depending on floating point ceil()
        assertTrue(segments.size in 1..2)
        val first = segments.first().pointAt(0f)
        val last = segments.last().pointAt(1f)
        assertEquals(10f, first[0], 1e-4f)
        assertEquals(0f, first[1], 1e-4f)
        assertEquals(0f, last[0], 1e-4f)
        assertEquals(10f, last[1], 1e-4f)
        assertTrue("radial error ${radialError(segments, 0f, 0f, 10f)}", radialError(segments, 0f, 0f, 10f) < 0.01f)
    }

    @Test
    fun fullHalfCircleSplitsIntoTwoSegments() {
        // (−10, 0) → (10, 0) through the top: 180° arc.
        val segments = ArcToCubic.convert(10f, 10f, 0f, largeArc = true, sweep = false, -10f, 0f, 10f, 0f)
        // a 180° arc yields 2-3 pieces depending on floating point ceil()
        assertTrue(segments.size in 2..3)
        segments.zipWithNext().forEach { (a, b) ->
            assertEquals("halves must continue each other", a.x3, b.x0, 1e-5f)
            assertEquals(a.y3, b.y0, 1e-5f)
        }
        assertTrue(radialError(segments, 0f, 0f, 10f) < 0.01f)
    }

    @Test
    fun degenerateRadiiFallBackToALine() {
        val zeroRadius = ArcToCubic.convert(0f, 0f, 0f, false, true, 0f, 0f, 10f, 4f)
        assertEquals(1, zeroRadius.size)
        assertEquals(10f, zeroRadius[0].x3, 1e-5f)
        assertEquals(4f, zeroRadius[0].y3, 1e-5f)
        // control points on the line
        assertEquals(10f / 3f, zeroRadius[0].x1, 1e-4f)

        val coincident = ArcToCubic.convert(5f, 5f, 0f, false, true, 3f, 3f, 3f, 3f)
        assertTrue(coincident.isEmpty())
    }

    @Test
    fun tooSmallRadiiAreScaledUpAndStillReachTheEndpoint() {
        // Distance between endpoints is 20; radii 1 can't span it and must scale.
        val segments = ArcToCubic.convert(1f, 1f, 0f, false, true, 0f, 0f, 20f, 0f)
        assertTrue(segments.isNotEmpty())
        val last = segments.last().pointAt(1f)
        assertEquals(20f, last[0], 1e-3f)
        assertEquals(0f, last[1], 1e-3f)
    }

    @Test
    fun rotatedEllipseRespectsTheXAxisRotation() {
        // 90°-rotated circle: same geometry, but the parameterization rotates.
        val segments = ArcToCubic.convert(10f, 10f, 90f, false, true, 0f, 10f, -10f, 0f)
        val start = segments.first().pointAt(0f)
        val end = segments.last().pointAt(1f)
        assertEquals(0f, start[0], 1e-3f)
        assertEquals(10f, start[1], 1e-3f)
        assertEquals(-10f, end[0], 1e-3f)
        assertEquals(0f, end[1], 1e-3f)
        assertTrue(radialError(segments, 0f, 0f, 10f) < 0.01f)
    }

    @Test
    fun sweepFlagChoosesTheTravelDirection() {
        // Same endpoints; the two sweep flags must bulge to opposite sides of the chord
        // x + y = 10 (positive sweep passes below/right of it, negative above/left).
        // The half-way point is taken by arc length so it is independent of how the
        // converter split the arc into pieces.
        val positive = ArcToCubic.convert(10f, 10f, 0f, false, true, 10f, 0f, 0f, 10f)
        val negative = ArcToCubic.convert(10f, 10f, 0f, false, false, 10f, 0f, 0f, 10f)
        val midPositive = arcMidpoint(positive)
        val midNegative = arcMidpoint(negative)
        val expected = 10f * sin(Math.PI.toFloat() / 4f)
        assertTrue(midPositive[0] + midPositive[1] > 10f)
        assertTrue(midNegative[0] + midNegative[1] < 10f)
        assertEquals(expected, midPositive[0], 0.05f)
        assertEquals(expected, midPositive[1], 0.05f)
        assertEquals(10f - expected, midNegative[0], 0.05f)
        assertEquals(10f - expected, midNegative[1], 0.05f)
    }

    @Test
    fun largeArcFlagTravelsTheLongWay() {
        val small = ArcToCubic.convert(10f, 10f, 0f, largeArc = false, sweep = true, 10f, 0f, 0f, 10f)
        val large = ArcToCubic.convert(10f, 10f, 0f, largeArc = true, sweep = true, 10f, 0f, 0f, 10f)
        assertTrue("90° small arc needs fewer pieces than the 270° large arc", small.size < large.size)
        val cos45 = 10f * cos(Math.PI.toFloat() / 4f)
        // small arc bulges through (+7.07, +7.07); large arc through the opposite side
        assertEquals(cos45, arcMidpoint(small)[0], 0.05f)
        // the 270° arc swings around center (10,10): its midpoint sits at (17.07, 17.07)
        assertTrue(arcMidpoint(large)[0] > 10f)
    }
}
