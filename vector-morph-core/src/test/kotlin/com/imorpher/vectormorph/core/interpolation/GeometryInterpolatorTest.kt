package com.imorpher.vectormorph.core.interpolation

import com.imorpher.vectormorph.core.geometry.ArcLengthData
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import com.imorpher.vectormorph.core.geometry.measureContour
import com.imorpher.vectormorph.core.model.TimingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packed-coordinate lerp and draw-progress timing maps. */
class GeometryInterpolatorTest {

    private fun sampleContour(): ContourData = ContourData.fromPolygon(
        listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f)),
        closed = false,
    )

    @Test
    fun packRoundTripsSegmentCoordinates() {
        val segments = listOf(Cubic(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f), Cubic.line(0f, 0f, 10f, 10f))
        val packed = GeometryInterpolator.pack(segments)
        assertEquals(16, packed.size)
        assertEquals(0f, packed[0], 0f)
        assertEquals(7f, packed[7], 0f)
        assertEquals(10f, packed[14], 0f)
    }

    @Test
    fun lerpCoordsInterpolatesEndpointFractions() {
        val from = floatArrayOf(0f, 0f, 10f, 10f)
        val to = floatArrayOf(20f, 30f, -10f, 50f)
        val out = FloatArray(4)

        GeometryInterpolator.lerpCoords(from, to, 0f, out)
        assertTrue(out.contentEquals(from))

        GeometryInterpolator.lerpCoords(from, to, 1f, out)
        assertTrue(out.contentEquals(to))

        GeometryInterpolator.lerpCoords(from, to, 0.5f, out)
        assertEquals(10f, out[0], 1e-5f)
        assertEquals(15f, out[1], 1e-5f)
        assertEquals(0f, out[2], 1e-5f)
        assertEquals(30f, out[3], 1e-5f)
    }

    @Test
    fun byCommandTimingGivesEachSegmentAnEqualSlice() {
        val contour = sampleContour() // 2 segments, 10 units each
        val arc = measureContour(contour)

        // At 0.25 the draw is half-way through the FIRST segment → arc fraction 0.25.
        val first = GeometryInterpolator.mapDrawProgress(0.25f, TimingMode.BY_COMMAND, arc, 0, 2)
        assertEquals(0.25f, first, 0.01f)

        // At 0.75 half-way through the SECOND segment → arc fraction 0.75.
        val second = GeometryInterpolator.mapDrawProgress(0.75f, TimingMode.BY_COMMAND, arc, 0, 2)
        assertEquals(0.75f, second, 0.01f)
    }

    @Test
    fun byPathTimingSequencesContoursByIndex() {
        val arc = measureContour(sampleContour())
        // contour 0 travels during [0, 0.5), contour 1 during [0.5, 1]
        val first = GeometryInterpolator.mapDrawProgress(0.25f, TimingMode.BY_PATH, arc, 0, 2)
        assertEquals(0.5f, first, 1e-4f)
        val second = GeometryInterpolator.mapDrawProgress(0.75f, TimingMode.BY_PATH, arc, 1, 2)
        assertEquals(0.5f, second, 1e-4f)
        // contour 1 has not started at p=0.25
        val notYet = GeometryInterpolator.mapDrawProgress(0.25f, TimingMode.BY_PATH, arc, 1, 2)
        assertEquals(0f, notYet, 0f)
    }

    @Test
    fun uniformAndByPathLengthPassProgressThroughUnmapped() {
        val arc = measureContour(sampleContour())
        assertEquals(0.4f, GeometryInterpolator.mapDrawProgress(0.4f, TimingMode.UNIFORM, arc, 0, 2), 0f)
        assertEquals(0.6f, GeometryInterpolator.mapDrawProgress(0.6f, TimingMode.BY_PATH_LENGTH, arc, 0, 2), 0f)
    }

    @Test
    fun degenerateArcsPassProgressThrough() {
        val emptyArc = ArcLengthData(FloatArray(0), FloatArray(1), 0f)
        assertEquals(0.7f, GeometryInterpolator.mapDrawProgress(0.7f, TimingMode.BY_COMMAND, emptyArc, 0, 1), 0f)
    }

    @Test
    fun mapDrawProgressClampsSegmentBounds() {
        val arc = measureContour(sampleContour())
        // progress beyond 1 and below 0 must not index out of bounds
        assertEquals(arc.totalLength / arc.totalLength, GeometryInterpolator.mapDrawProgress(1.5f, TimingMode.BY_COMMAND, arc, 0, 2), 0.01f)
        assertEquals(0f, GeometryInterpolator.mapDrawProgress(-0.5f, TimingMode.BY_COMMAND, arc, 0, 2), 0.01f)
    }
}
