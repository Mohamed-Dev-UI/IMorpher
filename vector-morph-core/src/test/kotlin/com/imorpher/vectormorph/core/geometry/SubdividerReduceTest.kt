package com.imorpher.vectormorph.core.geometry

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Error-driven segment reduction and the planner's segment budget. */
class SubdividerReduceTest {

    @Test
    fun reducePreservesEndpoints() {
        val contour = densePolygon(cx = 12f, cy = 12f, radius = 9f, segments = 24)

        val reduced = Subdivider.reduce(contour, 4, tolerancePx = Float.MAX_VALUE)

        assertEquals(4, reduced.segments.size)
        assertEquals(contour.segments.first().x0, reduced.segments.first().x0, 0f)
        assertEquals(contour.segments.first().y0, reduced.segments.first().y0, 0f)
        assertEquals(contour.segments.last().x3, reduced.segments.last().x3, 0f)
        assertEquals(contour.segments.last().y3, reduced.segments.last().y3, 0f)
        assertEquals(contour.closed, reduced.closed)
    }

    @Test
    fun reduceReachesTargetForCollinearDetail() {
        val contour = ContourData(
            List(24) { i ->
                val x0 = i.toFloat()
                Cubic.line(x0, 0f, x0 + 1f, 0f)
            },
            closed = false,
        )

        val reduced = Subdivider.reduce(contour, 8, tolerancePx = 0.25f)

        assertEquals(8, reduced.segments.size)
        assertTrue("merging collinear pieces is lossless", maxDeviation(contour, reduced) < 0.25f)
    }

    @Test
    fun reduceStopsWhenToleranceWouldBeExceeded() {
        val contour = densePolygon(cx = 12f, cy = 12f, radius = 9f, segments = 24)

        val reduced = Subdivider.reduce(contour, 2, tolerancePx = 1e-5f)

        assertTrue("no merge is that cheap on a circle", reduced.segments.size > 2)
        assertTrue(reduced.segments.size <= contour.segments.size)
    }

    @Test
    fun reduceIsNoOpAtOrAboveCurrentCount() {
        val contour = densePolygon(cx = 12f, cy = 12f, radius = 9f, segments = 12)

        assertEquals(12, Subdivider.reduce(contour, 12, 0.25f).segments.size)
        assertEquals(12, Subdivider.reduce(contour, 40, 0.25f).segments.size)
    }

    @Test
    fun plannerSegmentBudgetCapsBothSidesAtEqualCounts() {
        val from = VectorCompiler.compile(polygonVector("from", radius = 9f))
        val to = VectorCompiler.compile(polygonVector("to", radius = 6f))

        val plan = MorphPlanner.plan(
            from,
            to,
            MorphConfiguration(segmentBudget = 8, simplificationTolerance = Float.MAX_VALUE),
        )

        val contourPairs = plan.pairs.flatMap { it.contourPairs }
        assertTrue("the fixture must exercise at least one contour pair", contourPairs.isNotEmpty())
        for (pair in contourPairs) {
            assertEquals(
                "interpolation requires equal segment counts on both sides",
                pair.from.segmentCount,
                pair.to.segmentCount,
            )
            assertTrue("budget caps the contour at 8 segments", pair.from.segmentCount <= 8)
        }
    }

    @Test
    fun defaultConfigurationLeavesSegmentCountsUntouched() {
        val from = VectorCompiler.compile(polygonVector("from", radius = 9f))
        val to = VectorCompiler.compile(polygonVector("to", radius = 6f))

        val plan = MorphPlanner.plan(from, to)

        val contourPairs = plan.pairs.flatMap { it.contourPairs }
        assertTrue(contourPairs.all { it.from.segmentCount == it.to.segmentCount })
    }

    // ------------------------------------------------------------------ fixtures

    private fun polygonVector(name: String, radius: Float, segments: Int = 32): ImageVector {
        val nodes = ArrayList<PathNode>(segments + 2)
        for (i in 0 until segments) {
            val angle = 2.0 * Math.PI * i / segments
            val x = 12f + (radius * cos(angle)).toFloat()
            val y = 12f + (radius * sin(angle)).toFloat()
            if (i == 0) nodes.add(PathNode.MoveTo(x, y)) else nodes.add(PathNode.LineTo(x, y))
        }
        nodes.add(PathNode.Close)
        return ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(pathData = nodes, fill = SolidColor(Color.Black), name = "shape")
        }.build()
    }

    /** Closed polygon with straight segments, used as a dense reduction source. */
    private fun densePolygon(cx: Float, cy: Float, radius: Float, segments: Int): ContourData {
        val segs = ArrayList<Cubic>(segments)
        for (i in 0 until segments) {
            val a0 = 2.0 * Math.PI * i / segments
            val a1 = 2.0 * Math.PI * (i + 1) / segments
            segs.add(
                Cubic.line(
                    cx + (radius * cos(a0)).toFloat(), cy + (radius * sin(a0)).toFloat(),
                    cx + (radius * cos(a1)).toFloat(), cy + (radius * sin(a1)).toFloat(),
                ),
            )
        }
        return ContourData(segs, closed = true)
    }

    /** Max point distance between two contours sampled at matching arc fractions. */
    private fun maxDeviation(original: ContourData, reduced: ContourData): Float {
        val arcA = measureContour(original)
        val arcB = measureContour(reduced)
        var worst = 0f
        for (step in 0..200) {
            val fraction = step / 200f
            val pa = positionAtArcFraction(original, arcA, fraction)
            val pb = positionAtArcFraction(reduced, arcB, fraction)
            val pointA = original.segments[pa[0].toInt()].pointAt(pa[1])
            val pointB = reduced.segments[pb[0].toInt()].pointAt(pb[1])
            worst = maxOf(worst, hypot(pointA[0] - pointB[0], pointA[1] - pointB[1]))
        }
        assertTrue(abs(worst) < Float.MAX_VALUE)
        return worst
    }
}
