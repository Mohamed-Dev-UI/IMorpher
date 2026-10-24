package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Brush interpolation memoization: identical input reuses the Result, different input does not. */
class BrushMemoTest {

    private fun plan() = VectorCompiler.compile(triangleVector()).let { MorphPlanner.plan(it, it) }

    private fun animation() = VectorAnimation {
        defaultEasing(LinearEasing)
        gradient(BrushSpec.Solid(Color.Red), BrushSpec.Solid(Color.Blue))
    }

    @Test
    fun identicalBrushProgressReusesResultInstance() {
        val plan = plan()
        val evaluator = TimelineEvaluator(animation(), plan, ColorInterpolationSpace.SRGB)
        val frame = EvaluatedFrame(plan)

        evaluator.evaluate(0.5f, frame)
        val first = frame.vectorPrimaryBrush
        assertNotNull(first)
        evaluator.evaluate(0.5f, frame)
        val second = frame.vectorPrimaryBrush

        assertSame("same progress must hit the per-track memo", first, second)
    }

    @Test
    fun differentBrushProgressComputesFreshResult() {
        val plan = plan()
        val evaluator = TimelineEvaluator(animation(), plan, ColorInterpolationSpace.SRGB)
        val frame = EvaluatedFrame(plan)

        evaluator.evaluate(0.5f, frame)
        val first = frame.vectorPrimaryBrush
        evaluator.evaluate(0.75f, frame)
        val second = frame.vectorPrimaryBrush

        assertNotSame(first, second)
    }

    @Test
    fun memoizedResultStillInterpolatesColors() {
        val plan = plan()
        val evaluator = TimelineEvaluator(animation(), plan, ColorInterpolationSpace.SRGB)
        val frame = EvaluatedFrame(plan)

        evaluator.evaluate(0.5f, frame)
        val brush = frame.vectorPrimaryBrush as SolidColor

        // Midpoint between red and blue in sRGB: 127/128-ish per channel, opaque.
        assertEqualsWithTolerance(0.5f, brush.value.red)
        assertEqualsWithTolerance(0.5f, brush.value.blue)
        assertEqualsWithTolerance(1f, brush.value.alpha)
    }

    private fun assertEqualsWithTolerance(expected: Float, actual: Float) {
        org.junit.Assert.assertEquals(expected, actual, 0.02f)
    }

    private fun triangleVector(): ImageVector =
        ImageVector.Builder("memo", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(3f, 3f),
                    PathNode.LineTo(21f, 3f),
                    PathNode.LineTo(12f, 21f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                name = "shape",
            )
        }.build()
}
