package com.imorpher.vectormorph.core.matching

import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourAlignerTest {

    /** Closed rectangular contour; [clockwise] uses the screen-coordinate convention. */
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

    @Test
    fun oppositeWindingInsideEncloserIsAHoleRegardlessOfAbsoluteDirection() {
        // Outer CW + hole CCW (AddCircleSolid-style authoring)...
        val outer = square(0f, 0f, 20f, 20f, clockwise = true)
        val hole = square(6f, 6f, 14f, 14f, clockwise = false)
        val universe = listOf(outer, hole)
        assertTrue(ContourAligner.isFillRuleHole(hole, universe))
        assertFalse("the enclosing contour itself is not a hole", ContourAligner.isFillRuleHole(outer, universe))

        // ...and the mirrored convention (outer CCW + holes CW, ArrangeCircle2Solid-style).
        val mirroredOuter = square(0f, 0f, 20f, 20f, clockwise = false)
        val mirroredHole = square(6f, 6f, 14f, 14f, clockwise = true)
        val mirroredUniverse = listOf(mirroredOuter, mirroredHole)
        assertTrue(ContourAligner.isFillRuleHole(mirroredHole, mirroredUniverse))
        assertFalse(ContourAligner.isFillRuleHole(mirroredOuter, mirroredUniverse))
    }

    @Test
    fun multipleHolesInsideOneEncloserAreAllDetected() {
        val outer = square(0f, 0f, 20f, 20f, clockwise = false)
        val holeA = square(2f, 2f, 6f, 6f, clockwise = true)
        val holeB = square(10f, 10f, 14f, 14f, clockwise = true)
        val universe = listOf(outer, holeA, holeB)
        assertTrue(ContourAligner.isFillRuleHole(holeA, universe))
        assertTrue(ContourAligner.isFillRuleHole(holeB, universe))
        assertFalse(ContourAligner.isFillRuleHole(outer, universe))
    }

    @Test
    fun contourWithoutEncloserIsNeverAHole() {
        val disc = square(0f, 0f, 20f, 20f, clockwise = true)
        val separate = square(21f, 0f, 30f, 9f, clockwise = false)
        val universe = listOf(disc, separate)
        assertFalse(ContourAligner.isFillRuleHole(separate, universe))
        assertFalse(ContourAligner.isFillRuleHole(disc, universe))
    }

    @Test
    fun sameWindingInsideEncloserIsAnIslandNotAHole() {
        val outer = square(0f, 0f, 20f, 20f, clockwise = true)
        val island = square(6f, 6f, 14f, 14f, clockwise = true)
        val universe = listOf(outer, island)
        assertFalse(ContourAligner.isFillRuleHole(island, universe))
    }
}
