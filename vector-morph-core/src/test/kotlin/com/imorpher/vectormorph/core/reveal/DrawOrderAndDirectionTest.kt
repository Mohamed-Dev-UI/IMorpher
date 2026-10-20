package com.imorpher.vectormorph.core.reveal

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reveal ordering, stagger offsets, and direction math. */
class DrawOrderAndDirectionTest {

    private fun squareAt(left: Float, top: Float): List<PathNode> = listOf(
        PathNode.MoveTo(left, top), PathNode.LineTo(left + 4f, top),
        PathNode.LineTo(left + 4f, top + 4f), PathNode.LineTo(left, top + 4f), PathNode.Close,
    )

    private fun vector(vararg paths: Pair<String, List<PathNode>>): ImageVector {
        val builder = ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f)
        paths.forEach { (name, data) ->
            builder.addPath(pathData = data, fill = SolidColor(Color.Black), name = name)
        }
        return builder.build()
    }

    private fun twoSquarePlan(): com.imorpher.vectormorph.core.plan.MorphPlan {
        val v = vector(
            "left" to squareAt(2f, 10f),
            "right" to squareAt(18f, 10f),
            "top" to squareAt(10f, 2f),
            "bottom" to squareAt(10f, 18f),
        )
        val compiled = VectorCompiler.compile(v)
        return MorphPlanner.plan(compiled, compiled)
    }

    @Test
    fun byIndexKeepsDeclarationOrder() {
        val plan = twoSquarePlan()
        val order = DrawOrder.orderIndices(plan, DrawOrderStrategy.BY_INDEX, emptyList())
        assertEquals(listOf(0, 1, 2, 3), order.toList())
    }

    @Test
    fun topLeftToBottomRightOrdersByCornerDistance() {
        val plan = twoSquarePlan()
        val order = DrawOrder.orderIndices(plan, DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT, emptyList())
        // left (2,10) and top (10,2) share x+y=12 → tie broken by sort stability/index
        assertEquals(3, order.last()) // bottom (10,18 → x+y=28) is last
        assertTrue(order.first() == 0 || order.first() == 2)
    }

    @Test
    fun topRightToBottomLeftReversesTheCornerPriority() {
        val plan = twoSquarePlan()
        val order = DrawOrder.orderIndices(plan, DrawOrderStrategy.TOP_RIGHT_TO_BOTTOM_LEFT, emptyList())
        // right/top squares (largest max-x) come first, left square last
        assertTrue("right before left", order.indexOf(1) < order.indexOf(0))
        assertTrue("top before bottom", order.indexOf(2) < order.indexOf(3))
    }

    @Test
    fun clockwiseOrdersAroundTheViewportCenter() {
        val plan = twoSquarePlan()
        val order = DrawOrder.orderIndices(plan, DrawOrderStrategy.CLOCKWISE, emptyList())
        // Ascending atan2 in Y-down space walks top → right → bottom → left.
        val rightRank = order.indexOf(1)
        val bottomRank = order.indexOf(3)
        val leftRank = order.indexOf(0)
        val topRank = order.indexOf(2)
        assertTrue("top before right", topRank < rightRank)
        assertTrue("right before bottom", rightRank < bottomRank)
        assertTrue("bottom before left", bottomRank < leftRank)
    }

    @Test
    fun counterClockwiseIsTheMirrorOfClockwise() {
        val plan = twoSquarePlan()
        val cw = DrawOrder.orderIndices(plan, DrawOrderStrategy.CLOCKWISE, emptyList())
        val ccw = DrawOrder.orderIndices(plan, DrawOrderStrategy.COUNTER_CLOCKWISE, emptyList())
        assertTrue("mirrored orders", cw.withIndex().all { (rank, index) -> ccw[cw.size - 1 - rank] == index })
    }

    @Test
    fun byNameFollowsTheCustomOrderList() {
        val plan = twoSquarePlan()
        val order = DrawOrder.orderIndices(
            plan, DrawOrderStrategy.CUSTOM,
            customOrder = listOf("bottom", "top", "right", "left"),
        )
        assertEquals(listOf(3, 2, 1, 0), order.toList())
    }

    @Test
    fun byNameWithoutCustomOrderSortsAlphabetically() {
        val plan = twoSquarePlan()
        val order = DrawOrder.orderIndices(plan, DrawOrderStrategy.BY_NAME, emptyList())
        // bottom, left, right, top → pair indices 3, 0, 1, 2
        assertEquals(listOf(3, 0, 1, 2), order.toList())
    }

    @Test
    fun staggerOffsetsFollowRevealRanks() {
        val plan = twoSquarePlan()
        val order = intArrayOf(2, 0, 3, 1)
        val offsets = DrawOrder.staggerOffsets(plan, order, staggerDelay = 0.1f)
        assertEquals(0f, offsets[2], 0f)
        assertEquals(0.1f, offsets[0], 0f)
        assertEquals(0.2f, offsets[3], 0f)
        assertEquals(0.3f, offsets[1], 0f)
    }

    @Test
    fun staggerOffsetsAreZeroWithoutDelay() {
        val plan = twoSquarePlan()
        val offsets = DrawOrder.staggerOffsets(plan, intArrayOf(0, 1, 2, 3), staggerDelay = 0f)
        assertTrue(offsets.all { it == 0f })
    }

    // ------------------------------------------------------------------ DirectionMath

    @Test
    fun axisForMapsEveryDirection() {
        assertTrue(DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(0f, 0f, 1f, 1f)))
        assertTrue(DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(1f, 0f, 0f, 1f)))
        assertTrue(DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(0f, 1f, 1f, 0f)))
        assertTrue(DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(1f, 1f, 0f, 0f)))
        assertTrue(DrawDirection.LEFT_TO_RIGHT.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f)))
        assertTrue(DrawDirection.RIGHT_TO_LEFT.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(1f, 0.5f, 0f, 0.5f)))
        assertTrue(DrawDirection.TOP_TO_BOTTOM.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(0.5f, 0f, 0.5f, 1f)))
        assertTrue(DrawDirection.BOTTOM_TO_TOP.let { DirectionMath.axisFor(it) }.contentEquals(floatArrayOf(0.5f, 1f, 0.5f, 0f)))
    }

    @Test
    fun unsupportedDirectionsFallBackToTheDiagonal() {
        val fallback = DirectionMath.axisFor(DrawDirection.CENTER_OUT)
        assertTrue(fallback.contentEquals(DirectionMath.axisFor(DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT)))
        val clockwise = DirectionMath.axisFor(DrawDirection.CLOCKWISE)
        assertTrue(clockwise.contentEquals(fallback))
    }

    @Test
    fun anchorForMatchesTheAxisStart() {
        for (direction in listOf(
            DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT, DrawDirection.LEFT_TO_RIGHT,
            DrawDirection.TOP_TO_BOTTOM, DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT,
        )) {
            val axis = DirectionMath.axisFor(direction)
            val anchor = DirectionMath.anchorFor(direction)
            assertEquals(axis[0], anchor[0], 0f)
            assertEquals(axis[1], anchor[1], 0f)
        }
    }

    @Test
    fun isDiagonalFlagsOnlyTheCornerAxes() {
        assertTrue(DirectionMath.isDiagonal(DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT))
        assertTrue(DirectionMath.isDiagonal(DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT))
        assertFalse(DirectionMath.isDiagonal(DrawDirection.LEFT_TO_RIGHT))
        assertFalse(DirectionMath.isDiagonal(DrawDirection.CENTER_OUT))
        assertFalse(DirectionMath.isDiagonal(DrawDirection.CLOCKWISE))
    }
}
