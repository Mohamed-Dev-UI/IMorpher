package com.imorpher.vectormorph.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Geometry primitives: cubic evaluation/splitting, contour operations, and the
 * measurement functions the morph engine builds on.
 */
class CubicAndContourTest {

    // ------------------------------------------------------------------ Cubic

    @Test
    fun cubicEvaluatesEndpointsAndMidpoints() {
        val c = Cubic(0f, 0f, 3f, 9f, 7f, -4f, 12f, 5f)
        val start = c.pointAt(0f)
        val end = c.pointAt(1f)
        assertEquals(0f, start[0], 1e-5f)
        assertEquals(0f, start[1], 1e-5f)
        assertEquals(12f, end[0], 1e-5f)
        assertEquals(5f, end[1], 1e-5f)

        // At t=0.5 the de Casteljau midpoint averages the control polygon.
        val mid = c.pointAt(0.5f)
        val expectedX = (0f + 3 * 3f + 3 * 7f + 12f) / 8f
        val expectedY = (0f + 3 * 9f + 3 * -4f + 5f) / 8f
        assertEquals(expectedX, mid[0], 1e-4f)
        assertEquals(expectedY, mid[1], 1e-4f)
    }

    @Test
    fun splitHalvesTraceTheIdenticalCurve() {
        val c = Cubic(0f, 0f, 3f, 9f, 7f, -4f, 12f, 5f)
        val (left, right) = c.split(0.4f)

        assertEquals(c.pointAt(0f)[0], left.pointAt(0f)[0], 1e-6f)
        assertEquals(c.pointAt(1f)[0], right.pointAt(1f)[0], 1e-6f)
        // the split point is shared exactly
        assertEquals(left.pointAt(1f)[0], right.pointAt(0f)[0], 1e-5f)
        assertEquals(left.pointAt(1f)[1], right.pointAt(0f)[1], 1e-5f)

        // both halves sample the original curve
        for (t in listOf(0.1f, 0.25f, 0.4f)) {
            val expected = c.pointAt(t)
            val actual = left.pointAt(t / 0.4f)
            assertEquals(expected[0], actual[0], 1e-4f)
            assertEquals(expected[1], actual[1], 1e-4f)
        }
        for (t in listOf(0.4f, 0.7f, 0.95f)) {
            val expected = c.pointAt(t)
            val actual = right.pointAt((t - 0.4f) / 0.6f)
            assertEquals(expected[0], actual[0], 1e-4f)
            assertEquals(expected[1], actual[1], 1e-4f)
        }
    }

    @Test
    fun reversedTraversesTheSamePoints() {
        val c = Cubic(1f, 2f, 3f, 9f, 7f, -4f, 12f, 5f)
        val r = c.reversed()
        for (t in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            val forward = c.pointAt(t)
            val backward = r.pointAt(1f - t)
            assertEquals(forward[0], backward[0], 1e-5f)
            assertEquals(forward[1], backward[1], 1e-5f)
        }
    }

    @Test
    fun lineCubicIsAGeometricallyExactLine() {
        val line = Cubic.line(0f, 0f, 30f, 40f)
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val p = line.pointAt(t)
            assertEquals(30f * t, p[0], 1e-4f)
            assertEquals(40f * t, p[1], 1e-4f)
        }
        assertEquals(50f, line.controlLength(), 1e-4f)
        assertEquals(50f, line.chordLength(), 1e-4f)
    }

    @Test
    fun quadraticElevationMatchesTheOriginalQuad() {
        // Quadratic through (0,0), control (10,20), end (20,0).
        val quadPoint = { t: Float ->
            val mt = 1f - t
            floatArrayOf(
                mt * mt * 0f + 2 * mt * t * 10f + t * t * 20f,
                mt * mt * 0f + 2 * mt * t * 20f + t * t * 0f,
            )
        }
        val cubic = Cubic.fromQuad(10f, 20f, 0f, 0f, 20f, 0f)
        for (t in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            val expected = quadPoint(t)
            val actual = cubic.pointAt(t)
            assertEquals(expected[0], actual[0], 1e-4f)
            assertEquals(expected[1], actual[1], 1e-4f)
        }
    }

    // ------------------------------------------------------------------ ContourData

    @Test
    fun boundaryPointsIncludeStartOfEachSegmentAndTheFinalEnd() {
        val contour = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(4f, 0f), floatArrayOf(4f, 4f)),
            closed = false,
        )
        val pts = contour.boundaryPoints()
        // 3 polygon points → 2 open segments → 2 segment starts + 1 final end
        assertEquals(3, pts.size)
        assertEquals(0f, pts[0][0], 0f)
        assertEquals(4f, pts[1][0], 0f)
        assertEquals(4f, pts[2][0], 0f)
        assertEquals(4f, pts[2][1], 0f)
    }

    @Test
    fun reversedContourPreservesGeometryAndClosure() {
        val contour = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(6f, 0f), floatArrayOf(6f, 6f), floatArrayOf(0f, 6f)),
            closed = true,
        )
        val reversed = contour.reversed()
        assertTrue(reversed.closed)
        assertEquals(
            measureContour(contour).totalLength,
            measureContour(reversed).totalLength,
            1e-4f,
        )
        // every sample of the reversed contour lies on the original
        val arc = measureContour(contour, samplesPerSegment = 32)
        for (fraction in listOf(0.1f, 0.35f, 0.7f, 0.95f)) {
            val forward = positionAtArcFraction(contour, arc, fraction)
            val point = floatArrayOf(0f, 0f)
            contour.segments[forward[0].toInt()].pointAt(forward[1], point)
            val near = nearestPoint(reversed, point[0], point[1])
            val distance = hypot(point[0] - near[0], point[1] - near[1])
            // nearestPoint is a sampled approximation; a few hundredths are expected
            assertTrue("sample $fraction must exist on the reversed contour ($distance)", distance < 0.05f)
        }
    }

    @Test
    fun rotateStartRelocatesTheStartWithoutChangingGeometry() {
        val contour = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(8f, 0f), floatArrayOf(8f, 8f), floatArrayOf(0f, 8f)),
            closed = true,
        )
        val rotated = contour.rotateStart(2, 0f)
        assertNotEquals(contour.startX, rotated.startX, 0f)
        assertEquals(8f, rotated.startX, 1e-5f)
        assertEquals(8f, rotated.startY, 1e-5f)
        assertTrue(rotated.closed)
        assertEquals(measureContour(contour).totalLength, measureContour(rotated).totalLength, 1e-4f)
        // contour continuity holds after rotation
        rotated.segments.zipWithNext().forEach { (left, right) ->
            assertEquals(left.x3, right.x0, 1e-5f)
            assertEquals(left.y3, right.y0, 1e-5f)
        }
        // and it wraps back to the (new) start
        assertEquals(rotated.startX, rotated.endX, 1e-5f)
        assertEquals(rotated.startY, rotated.endY, 1e-5f)
    }

    @Test
    fun rotateStartIsNoOpForOpenContoursAndIdentityRotation() {
        val open = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(4f, 0f), floatArrayOf(4f, 4f)),
            closed = false,
        )
        assertTrue(open.rotateStart(1, 0.5f) === open)

        val closed = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(4f, 0f), floatArrayOf(4f, 4f), floatArrayOf(0f, 4f)),
            closed = true,
        )
        assertTrue(closed.rotateStart(0, 0f) === closed)
        // negative and oversized indices wrap
        val wrapped = closed.rotateStart(-1, 0f)
        assertEquals(closed.segments[3].x3, wrapped.startX, 1e-5f)
    }

    // ------------------------------------------------------------------ area / bounds / centroid

    @Test
    fun signedAreaSignFollowsScreenWinding() {
        // (0,0) → (10,0) → (10,10) → (0,10) is clockwise on a Y-down screen: positive area.
        val clockwise = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f), floatArrayOf(0f, 10f)),
            closed = true,
        )
        val counterClockwise = clockwise.reversed()
        val cwArea = signedArea(clockwise)
        val ccwArea = signedArea(counterClockwise)
        assertEquals(100f, cwArea, 0.5f)
        assertEquals(-100f, ccwArea, 0.5f)
    }

    @Test
    fun openContourAreaClosesBackToItsStart() {
        val open = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f)),
            closed = false,
        )
        assertEquals(50f, signedArea(open), 0.5f)
    }

    @Test
    fun boundsTrackControlPointsOutsideTheChord() {
        val bulge = ContourData(
            listOf(Cubic(0f, 0f, 5f, -10f, 15f, -10f, 20f, 0f)),
            closed = false,
        )
        // bounds() is conservative: control points participate, so minY is the
        // control-point extreme (-10), not the curve apex (-7.5).
        val b = bounds(bulge)
        assertEquals(0f, b[0], 1e-4f)
        assertEquals(-10f, b[1], 1e-4f)
        assertEquals(20f, b[2], 1e-4f)
        assertEquals(0f, b[3], 1e-4f)
    }

    @Test
    fun centroidOfASquareIsItsCenter() {
        val square = ContourData.fromPolygon(
            listOf(floatArrayOf(2f, 3f), floatArrayOf(12f, 3f), floatArrayOf(12f, 13f), floatArrayOf(2f, 13f)),
            closed = true,
        )
        val c = centroid(square)
        assertEquals(7f, c[0], 0.2f)
        assertEquals(8f, c[1], 0.2f)
    }

    // ------------------------------------------------------------------ measurement

    @Test
    fun measureContourReportsExactLineLengths() {
        val contour = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(30f, 0f), floatArrayOf(30f, 40f)),
            closed = false,
        )
        val arc = measureContour(contour)
        assertEquals(2, arc.segmentLengths.size)
        assertEquals(30f, arc.segmentLengths[0], 0.01f)
        assertEquals(40f, arc.segmentLengths[1], 0.01f)
        assertEquals(70f, arc.totalLength, 0.02f)
        assertEquals(0f, arc.cumulativeLength[0], 0f)
        assertEquals(30f, arc.cumulativeLength[1], 0.01f)
        assertEquals(70f, arc.cumulativeLength[2], 0.02f)
    }

    @Test
    fun positionAtArcFractionWalksConstantSpeed() {
        // L shape: two equal legs of 10 → corner at fraction 0.5.
        val lShape = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f)),
            closed = false,
        )
        val arc = measureContour(lShape)

        val start = positionAtArcFraction(lShape, arc, 0f)
        assertEquals(0f, start[0], 0f)
        assertEquals(0f, start[1], 0f)

        val corner = positionAtArcFraction(lShape, arc, 0.5f)
        val cornerPoint = floatArrayOf(0f, 0f)
        lShape.segments[corner[0].toInt()].pointAt(corner[1], cornerPoint)
        assertEquals(10f, cornerPoint[0], 0.05f)
        assertEquals(0f, cornerPoint[1], 0.05f)

        val end = positionAtArcFraction(lShape, arc, 1f)
        assertEquals(1f, end[1], 1e-4f)
    }

    @Test
    fun sliceArcFractionProducesExactSubCurves() {
        val contour = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f), floatArrayOf(0f, 10f)),
            closed = true,
        )
        val total = measureContour(contour).totalLength

        val slice = contour.sliceArcFraction(0.25f, 0.75f)
        assertFalse(slice.closed)
        assertEquals(total * 0.5f, measureContour(slice).totalLength, total * 0.02f)

        // wrapping slice (end < start) covers the complementary half
        val wrap = contour.sliceArcFraction(0.75f, 0.25f)
        assertEquals(total * 0.5f, measureContour(wrap).totalLength, total * 0.02f)

        // degenerate slices are empty
        assertTrue(contour.sliceArcFraction(0.4f, 0.4f).segments.isEmpty())
    }

    @Test
    fun sliceArcFractionEndpointsSitOnTheOriginalCurve() {
        val curve = ContourData(listOf(Cubic(0f, 0f, 3f, 9f, 7f, -4f, 12f, 5f)), closed = false)
        val arc = measureContour(curve)
        val slice = curve.sliceArcFraction(0.3f, 0.6f, arc)
        val expectedStart = curve.segments[0].split(positionAtArcFraction(curve, arc, 0.3f)[1]).second
        assertEquals(expectedStart.x0, slice.segments.first().x0, 1e-4f)
        assertEquals(expectedStart.y0, slice.segments.first().y0, 1e-4f)
    }

    @Test
    fun arcFractionNearInvertsNearestPoint() {
        val lShape = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f)),
            closed = false,
        )
        val arc = measureContour(lShape)
        // a point on the middle of the first leg
        val fraction = arcFractionNear(lShape, arc, 5f, 0f)
        assertEquals(0.25f, fraction, 0.02f)
    }

    @Test
    fun nearestPointSnapsToTheCurve() {
        val line = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(20f, 0f)),
            closed = false,
        )
        val near = nearestPoint(line, 7f, 3f)
        assertEquals(7f, near[0], 0.05f)
        assertEquals(0f, near[1], 0.05f)
    }

    @Test
    fun emptyContoursDegradeGracefully() {
        val empty = ContourData(emptyList(), closed = false)
        assertEquals(0f, signedArea(empty), 0f)
        assertEquals(4, bounds(empty).size)
        assertEquals(0f, centroid(empty)[0], 0f)
        assertEquals(0f, measureContour(empty).totalLength, 0f)
        assertTrue(empty.boundaryPoints().isEmpty())
        assertTrue(empty.reversed() === empty)
    }

    private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)
}
