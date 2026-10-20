package com.imorpher.vectormorph.core.matching

import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.StartPointStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Winding normalization and start-point alignment strategies. */
class ContourAlignerStrategiesTest {

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float, clockwise: Boolean): ContourData {
        val corners = if (clockwise) {
            listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        } else {
            listOf(x0 to y0, x0 to y1, x1 to y1, x1 to y0)
        }
        val segments = corners.zipWithNext { a, b ->
            Cubic.line(a.first, a.second, b.first, b.second)
        } + Cubic.line(corners.last().first, corners.last().second, corners.first().first, corners.first().second)
        return ContourData(segments, closed = true)
    }

    // ------------------------------------------------------------------ direction

    @Test
    fun preserveNeverReverses() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true)
        val target = square(0f, 0f, 10f, 10f, clockwise = false)
        val (s, t, reversed) = ContourAligner.normalizeDirection(source, target, PathDirectionStrategy.PRESERVE)
        assertTrue(s === source)
        assertTrue(t === target)
        assertFalse(reversed)
    }

    @Test
    fun clockwiseForcesBothWindingsPositive() {
        val source = square(0f, 0f, 10f, 10f, clockwise = false)
        val target = square(0f, 0f, 10f, 10f, clockwise = false)
        val (s, t, _) = ContourAligner.normalizeDirection(source, target, PathDirectionStrategy.CLOCKWISE)
        assertTrue(com.imorpher.vectormorph.core.geometry.signedArea(s) > 0f)
        assertTrue(com.imorpher.vectormorph.core.geometry.signedArea(t) > 0f)
    }

    @Test
    fun counterClockwiseForcesBothWindingsNegative() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true)
        val target = square(0f, 0f, 10f, 10f, clockwise = false)
        val (s, t, _) = ContourAligner.normalizeDirection(source, target, PathDirectionStrategy.COUNTER_CLOCKWISE)
        assertTrue(com.imorpher.vectormorph.core.geometry.signedArea(s) < 0f)
        assertTrue(com.imorpher.vectormorph.core.geometry.signedArea(t) < 0f)
    }

    @Test
    fun autoMatchesTargetWindingToSource() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true)
        val target = square(0f, 0f, 10f, 10f, clockwise = false)
        val (_, t, reversed) = ContourAligner.normalizeDirection(source, target, PathDirectionStrategy.AUTO)
        assertTrue(reversed)
        assertTrue(com.imorpher.vectormorph.core.geometry.signedArea(t) > 0f)

        // same winding: untouched
        val (_, same, notReversed) = ContourAligner.normalizeDirection(source, source, PathDirectionStrategy.AUTO)
        assertTrue(same === source)
        assertFalse(notReversed)
    }

    // ------------------------------------------------------------------ start points

    @Test
    fun matchSourceRotatesTargetToTheSourceStart() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true) // starts at (0,0)
        val target = square(0f, 0f, 10f, 10f, clockwise = true).rotateStart(2, 0f) // starts at (0,10)

        val (_, aligned, offset) = ContourAligner.alignStartPoints(source, target, StartPointStrategy.MATCH_SOURCE)
        assertEquals(source.startX, aligned.startX, 1e-4f)
        assertEquals(source.startY, aligned.startY, 1e-4f)
        assertTrue("rotation must be reported: $offset", offset > 0.3f)
    }

    @Test
    fun matchTargetRotatesSourceToTheTargetStart() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true)
        val target = square(0f, 0f, 10f, 10f, clockwise = true).rotateStart(1, 0f)

        val (alignedSource, _, _) = ContourAligner.alignStartPoints(source, target, StartPointStrategy.MATCH_TARGET)
        assertEquals(target.startX, alignedSource.startX, 1e-4f)
        assertEquals(target.startY, alignedSource.startY, 1e-4f)
    }

    @Test
    fun bestGeometricMatchAlignsBothToTheirTopmostPoint() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true).rotateStart(2, 0f)
        val target = square(0f, 0f, 10f, 10f, clockwise = true).rotateStart(1, 0f)

        val (alignedSource, alignedTarget, _) = ContourAligner.alignStartPoints(
            source, target, StartPointStrategy.BEST_GEOMETRIC_MATCH,
        )
        // both must start at (or extremely near) the top-left corner (0,0)
        assertEquals(0f, alignedSource.startY, 1e-4f)
        assertEquals(0f, alignedTarget.startY, 1e-4f)
    }

    @Test
    fun customOffsetsRotateEachSideToItsArcFraction() {
        val contour = square(0f, 0f, 10f, 10f, clockwise = true)
        val (alignedSource, alignedTarget, _) = ContourAligner.alignStartPoints(
            contour, contour, StartPointStrategy.CUSTOM,
            customSourceOffset = 0f,
            customTargetOffset = 0.25f,
        )
        assertEquals(contour.startX, alignedSource.startX, 1e-4f)
        assertNotEquals(contour.startX, alignedTarget.startX, 1e-3f)
        // a quarter of the perimeter of a 10x10 square = 10 units → the target starts at (10, 0)
        assertEquals(10f, alignedTarget.startX, 0.2f)
        assertEquals(0f, alignedTarget.startY, 0.2f)
    }

    @Test
    fun autoAlignmentMinimizesCorrespondenceError() {
        val source = square(0f, 0f, 10f, 10f, clockwise = true)
        val target = square(0f, 0f, 10f, 10f, clockwise = true).rotateStart(3, 0f)

        val (_, aligned, _) = ContourAligner.alignStartPoints(source, target, StartPointStrategy.AUTO)
        val error = ContourAligner.correspondenceError(source, aligned)
        assertTrue("aligned error $error must be near zero", error < 0.2f)

        // a misaligned rotation must be measurably worse
        val misaligned = ContourAligner.correspondenceError(source, target)
        assertTrue(misaligned > error)
    }

    @Test
    fun autoAlignmentHandlesShapesWithDifferentSegmentCounts() {
        // source: 4-segment square; target: 3-segment triangle, start rotated.
        val source = square(0f, 0f, 10f, 10f, clockwise = true)
        val triangle = ContourData.fromPolygon(
            listOf(floatArrayOf(5f, 0f), floatArrayOf(10f, 10f), floatArrayOf(0f, 10f)),
            closed = true,
        ).rotateStart(1, 0f)

        val (_, aligned, _) = ContourAligner.alignStartPoints(source, triangle, StartPointStrategy.AUTO)
        assertTrue(aligned.segments.isNotEmpty())
        // aligned contour still wraps and preserves geometry
        assertEquals(triangle.endX, triangle.startX, 1e-5f)
        assertEquals(aligned.startX, aligned.endX, 1e-5f)
    }

    @Test
    fun openContoursAreNeverRotated() {
        val open = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(4f, 0f), floatArrayOf(4f, 4f)),
            closed = false,
        )
        val (s, t, offset) = ContourAligner.alignStartPoints(open, open, StartPointStrategy.AUTO)
        assertTrue(s === open)
        assertTrue(t === open)
        assertEquals(0f, offset, 0f)
    }

    @Test
    fun rotateToArcFractionLandsOnTheRequestedFraction() {
        val contour = square(0f, 0f, 10f, 10f, clockwise = true)
        val rotated = ContourAligner.rotateToArcFraction(contour, 0.5f)
        val error = ContourAligner.correspondenceError(contour, rotated)
        // half-perimeter shift is the largest possible misalignment: error must be substantial
        assertTrue("half-rotation should move the start far: $error", error > 5f)
        // halfway around the 40-unit perimeter from (0,0) lands on the corner (10,10)
        assertEquals(10f, rotated.startX, 0.2f)
        assertEquals(10f, rotated.startY, 0.2f)
    }

    @Test
    fun rotateToExtremePicksTopmostThenLeftmost() {
        val contour = square(2f, 5f, 12f, 15f, clockwise = true).rotateStart(2, 0f)
        val rotated = ContourAligner.rotateToExtreme(contour)
        assertEquals(2f, rotated.startX, 1e-4f)
        assertEquals(5f, rotated.startY, 1e-4f)
    }

    // ------------------------------------------------------------------ sample helper

    @Test
    fun sampleByArcFractionCoversTheWholeContour() {
        val contour = square(0f, 0f, 10f, 10f, clockwise = true)
        val arc = com.imorpher.vectormorph.core.geometry.measureContour(contour)
        val samples = ContourAligner.sampleByArcFraction(contour, arc, 5)
        assertEquals(5, samples.size)
        // first sample is the start, and consecutive samples walk the perimeter
        assertEquals(0f, samples.first()[0], 1e-4f)
        assertEquals(0f, samples.first()[1], 1e-4f)
        assertEquals(10f, samples[2][0], 0.2f) // half way = corner (10,10)… actually (10, 5+5) midpoint walk
    }

    @Test
    fun correspondenceErrorOfIdenticalContoursIsZero() {
        val contour = square(0f, 0f, 10f, 10f, clockwise = true)
        assertEquals(0f, ContourAligner.correspondenceError(contour, contour), 1e-5f)
        // reversed traversal is a large error for an asymmetric start
        val reversed = contour.reversed()
        assertTrue(ContourAligner.correspondenceError(contour, reversed) > 0f)
    }
}
