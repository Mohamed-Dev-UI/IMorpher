package com.imorpher.vectormorph.core.animation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-time DSL diagnostics (task 21). */
class DslWarningsTest {

    @Test
    fun degenerateIntervalEmitsWarning() {
        val anim = VectorAnimation {
            alpha(from = 1f, to = 0f, interval = 0.5f..0.5f)
        }

        assertTrue(
            "expected a degenerate-interval warning, got ${anim.warnings}",
            anim.warnings.any { it.contains("degenerate") },
        )
    }

    @Test
    fun duplicateKeyframeTimeWarns() {
        val anim = VectorAnimation {
            at(0f) { alpha = 1f }
            at(0f) { alpha = 0f }
        }

        assertTrue(
            "expected a duplicate/overlap warning, got ${anim.warnings}",
            anim.warnings.any { it.contains("duplicate") || it.contains("overlap") },
        )
    }

    @Test
    fun overlappingExplicitSegmentsWarn() {
        val anim = VectorAnimation {
            alpha(from = 0f, to = 1f, interval = 0f..0.6f)
            alpha(from = 1f, to = 0.5f, interval = 0.4f..1f)
        }

        assertTrue(
            "expected an overlapping-intervals warning, got ${anim.warnings}",
            anim.warnings.any { it.contains("overlapping") },
        )
    }

    @Test
    fun cleanAnimationHasNoWarnings() {
        val anim = VectorAnimation {
            at(0f) { alpha = 0f }
            at(1f) { alpha = 1f }
        }

        assertEquals(emptyList<String>(), anim.warnings)
    }

    @Test
    fun warningsAreNotSerialized() {
        val anim = VectorAnimation {
            alpha(from = 1f, to = 0f, interval = 0.5f..0.5f)
        }
        assertFalse(anim.warnings.isEmpty())

        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(anim))

        assertEquals(emptyList<String>(), decoded.warnings)
    }

    @Test
    fun warningsDoNotAffectEvaluation() {
        val vector = triangleVector()
        val prepared = VectorCompiler.compile(vector)
        val plan = MorphPlanner.plan(prepared, prepared)
        val anim = VectorAnimation {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            alpha(from = 0f, to = 1f, interval = 0f..0.5f)
            gradient(BrushSpec.Solid(Color.Red), BrushSpec.Solid(Color.Blue), interval = 0.5f..0.5f)
        }
        val frame = EvaluatedFrame(plan)

        TimelineEvaluator(anim, plan, ColorInterpolationSpace.SRGB).evaluate(0.25f, frame)

        assertEquals(0.5f, frame.vectorProps[PropKey.ALPHA.ordinal], 1e-4f)
    }

    private fun triangleVector(): ImageVector =
        ImageVector.Builder("warnings", 24.dp, 24.dp, 24f, 24f).apply {
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
