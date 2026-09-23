package com.imorpher.vectormorph.core.geometry

import androidx.compose.ui.graphics.vector.PathNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathNormalizationTest {
    @Test
    fun normalizesCommandsIntoContinuousCubicContours() {
        val contours = PathNodeNormalizer.normalize(
            listOf(
                PathNode.MoveTo(1f, 1f),
                PathNode.HorizontalTo(5f),
                PathNode.VerticalTo(3f),
                PathNode.RelativeLineTo(1f, 2f),
                PathNode.QuadTo(8f, 8f, 9f, 6f),
                PathNode.ReflectiveQuadTo(12f, 4f),
                PathNode.CurveTo(13f, 3f, 14f, 4f, 15f, 5f),
                PathNode.ReflectiveCurveTo(16f, 6f, 17f, 7f),
                PathNode.ArcTo(2f, 2f, 0f, false, true, 18f, 8f),
                PathNode.Close,
            ),
        )

        assertEquals(1, contours.size)
        val contour = contours.single()
        assertTrue(contour.closed)
        assertTrue("commands should become cubic segments", contour.segments.size >= 9)
        assertEquals(1f, contour.startX, 1e-4f)
        assertEquals(1f, contour.endX, 1e-4f)
        assertEquals(1f, contour.endY, 1e-4f)
        contour.segments.zipWithNext().forEach { (left, right) ->
            assertEquals(left.x3, right.x0, 1e-4f)
            assertEquals(left.y3, right.y0, 1e-4f)
        }
    }

    @Test
    fun moveToStartsIndependentOpenContours() {
        val contours = PathNodeNormalizer.normalize(
            listOf(
                PathNode.MoveTo(0f, 0f), PathNode.LineTo(4f, 0f),
                PathNode.MoveTo(10f, 10f), PathNode.RelativeLineTo(0f, 3f),
            ),
        )

        assertEquals(2, contours.size)
        assertFalse(contours[0].closed)
        assertFalse(contours[1].closed)
        assertEquals(0f, contours[0].startX, 0f)
        assertEquals(10f, contours[1].startX, 0f)
        assertEquals(13f, contours[1].endY, 0f)
    }

    @Test
    fun subdivisionPreservesCurveEndpointsAndLength() {
        val original = ContourData(
            listOf(
                Cubic(0f, 0f, 1f, 12f, 8f, -5f, 10f, 4f),
                Cubic.line(10f, 4f, 15f, 4f),
            ),
            closed = false,
        )

        val subdivided = Subdivider.equalize(original, 11)

        assertEquals(11, subdivided.segments.size)
        assertEquals(original.startX, subdivided.startX, 1e-5f)
        assertEquals(original.startY, subdivided.startY, 1e-5f)
        assertEquals(original.endX, subdivided.endX, 1e-5f)
        assertEquals(original.endY, subdivided.endY, 1e-5f)
        subdivided.segments.zipWithNext().forEach { (left, right) ->
            assertEquals(left.x3, right.x0, 1e-4f)
            assertEquals(left.y3, right.y0, 1e-4f)
        }
        val originalLength = measureContour(original, samplesPerSegment = 40).totalLength
        val subdividedLength = measureContour(subdivided, samplesPerSegment = 40).totalLength
        assertEquals(originalLength, subdividedLength, 0.03f)
    }

    @Test
    fun arcFractionSliceUsesExactCubicSubdivision() {
        val curve = Cubic(0f, 0f, 3f, 9f, 7f, -4f, 12f, 5f)
        val contour = ContourData(listOf(curve), closed = false)
        val splitT = positionAtArcFraction(contour, measureContour(contour), 0.5f)[1]
        val expected = curve.split(splitT).first

        val prefix = contour.sliceArcFraction(0f, 0.5f)
        val actual = prefix.segments.single()

        assertEquals(expected.x0, actual.x0, 1e-5f)
        assertEquals(expected.y0, actual.y0, 1e-5f)
        assertEquals(expected.x1, actual.x1, 1e-5f)
        assertEquals(expected.y1, actual.y1, 1e-5f)
        assertEquals(expected.x2, actual.x2, 1e-5f)
        assertEquals(expected.y2, actual.y2, 1e-5f)
        assertEquals(expected.x3, actual.x3, 1e-5f)
        assertEquals(expected.y3, actual.y3, 1e-5f)
    }

    @Test
    fun arcLengthLookupInvertsCubicParameterization() {
        val contour = ContourData(
            listOf(Cubic(0f, 0f, 0f, 30f, 30f, -15f, 24f, 0f)),
            closed = false,
        )
        val arc = measureContour(contour, samplesPerSegment = 128)

        for (fraction in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            val (segment, t) = positionAtArcFraction(contour, arc, fraction).let { it[0].toInt() to it[1] }
            val prefix = contour.segments[segment].split(t).first
            val actualFraction = measureContour(ContourData(listOf(prefix), false), 128).totalLength / arc.totalLength
            assertEquals(fraction, actualFraction, 0.02f)
        }
    }

    @Test
    fun autoAlignmentRemovesWindingAndStartPointMismatch() {
        val source = ContourData.fromPolygon(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f), floatArrayOf(0f, 10f)),
            closed = true,
        )
        val target = ContourData.fromPolygon(
            listOf(floatArrayOf(10f, 10f), floatArrayOf(10f, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, 10f)),
            closed = true,
        )

        val (sameWindingSource, sameWindingTarget, reversed) =
            com.imorpher.vectormorph.core.matching.ContourAligner.normalizeDirection(
                source, target, com.imorpher.vectormorph.core.model.PathDirectionStrategy.AUTO,
            )
        val (_, alignedTarget, _) = com.imorpher.vectormorph.core.matching.ContourAligner.alignStartPoints(
            sameWindingSource, sameWindingTarget, com.imorpher.vectormorph.core.model.StartPointStrategy.AUTO,
        )

        assertTrue(reversed)
        assertEquals(0f, com.imorpher.vectormorph.core.matching.ContourAligner.correspondenceError(source, alignedTarget), 0.1f)
    }
}
