package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the deprecated [VectorAnimation.drawConfigFor] shim (plan C6 review focus 5).
 * The shim keeps the historical contract for its single consumer pattern — the stroke
 * reveal of the last matching DRAW_PROGRESS track, the same [DrawConfig]
 * [strokeRevealFor] resolves — but is asymmetric by design: fill/clip reveals are typed
 * [FillRevealConfig]/[ClipRevealConfig] now, so FILL_PROGRESS resolves null here and the
 * typed pair ([strokeRevealFor]/[fillRevealFor]) is the replacement. The clip direction
 * has no clip-named public accessor (shipped state); renderers read the direction from
 * the [PathFrameValues.clipReveal] slot the evaluator populates.
 *
 * The deprecation wording is pinned for real: Kotlin's `@Deprecated` compiles to a runtime-
 * retained `kotlin.Deprecated` marker WITHOUT a `java.lang.Deprecated` mirror, so reflection on
 * `java.lang.Deprecated` cannot observe it, and KDoc is erased at compile time — so the marker's
 * presence and the deprecation message are scanned from the compiled class' annotation constant in
 * [deprecationMessagePinsTypedReplacementsAndTheFillClipAsymmetry].
 */
@Suppress("DEPRECATION")
class DeprecatedDrawConfigForTest {

    private fun vectorOf(vararg pairsOf: Pair<String, List<PathNode>>): ImageVector {
        val builder = ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f)
        pairsOf.forEach { (name, pathData) ->
            builder.addPath(pathData = pathData, fill = SolidColor(Color.Black), name = name)
        }
        return builder.build()
    }

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    private fun plan(vector: ImageVector) = MorphPlanner.plan(
        VectorCompiler.compile(vector), VectorCompiler.compile(vector), MorphConfiguration.Default,
    )

    private fun fullAnimation() = VectorAnimation {
        defaultEasing(LinearEasing)
        path("ic") {
            strokeReveal(
                mode = DrawMode.FORWARD,
                start = PathStart.TOP,
                direction = DrawDirection.BOTTOM_TO_TOP,
            )
            fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.BOTTOM_TO_TOP)
        }
    }

    @Test
    fun shimReturnsTheSameStrokeRevealAsstrokeRevealForOnDrawProgress() {
        val animation = fullAnimation()
        val shim = animation.drawConfigFor("ic", "ic", 0, 0, PropKey.DRAW_PROGRESS)
        val typed = animation.strokeRevealFor("ic", "ic", 0, 0, PropKey.DRAW_PROGRESS)

        assertNotNull(typed)
        // The shim simply resolves the stroke reveal — same instance and same three
        // fields the stroke pass consumes.
        assertSame(typed, shim)
        assertEquals(DrawMode.FORWARD, shim!!.mode)
        assertEquals(PathStart.TOP, shim.start)
        assertEquals(DrawDirection.BOTTOM_TO_TOP, shim.direction)
    }

    @Test
    fun shimReturnsNullForFillWhileFillRevealForResolvesTheFillConfig() {
        val animation = fullAnimation()

        // The asymmetry: FILL_PROGRESS was never a stroke draw config, and after the
        // typed split no DrawConfig exists on fill tracks at all — the shim must answer
        // null exactly as documented.
        assertNull(animation.drawConfigFor("ic", "ic", 0, 0, PropKey.FILL_PROGRESS))

        val fill = animation.fillRevealFor("ic", "ic", 0, 0)
        assertNotNull(fill)
        assertEquals(FillMode.DIRECTIONAL, fill!!.fillMode)
        assertEquals(DrawDirection.BOTTOM_TO_TOP, fill.fillDirection)
    }

    @Test
    fun clipDirectionReachesRenderersThroughTheClipSlot() {
        // No clip-named accessor was added with the typed split (stroke/fill pair only);
        // the shipped contract is that CLIP_PROGRESS resolves ClipRevealConfig onto the
        // frame slot renderers consume.
        val vector = vectorOf("p" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { clipReveal(direction = DrawDirection.LEFT_TO_RIGHT) }
        }
        val morphPlan = plan(vector)
        val frame = EvaluatedFrame(morphPlan)
        TimelineEvaluator(
            animation, morphPlan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM,
        ).evaluate(0.5f, frame)
        assertEquals(DrawDirection.LEFT_TO_RIGHT, frame.pathProps[0].clipReveal?.direction)
    }

    @Test
    fun deprecationMessagePinsTypedReplacementsAndTheFillClipAsymmetry() {
        val methods = VectorAnimation::class.java.methods.filter { it.name == "drawConfigFor" }
        assertTrue("drawConfigFor missing from VectorAnimation bytecode", methods.isNotEmpty())

        // Kotlin compiles @Deprecated WITHOUT a runtime-visible java.lang.Deprecated
        // marker (verified in the bytecode: only Lkotlin/Deprecated is emitted), so
        // annotation reflection cannot observe it; the compiled class bytes carry the
        // kotlin.Deprecated marker plus the message constant as the runtime-checkable
        // proxy for the documented contract.
        val cls = VectorAnimation::class.java
        val text = cls.getResourceAsStream("${cls.name.substringAfterLast('.')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
        assertNotNull("VectorAnimation.class not on the test classpath", text)
        // Presence: the kotlin.Deprecated marker must exist in the bytecode.
        assertTrue("drawConfigFor lost its deprecation marker", text!!.contains("Lkotlin/Deprecated"))
        // Why: reveals are typed per property and only stroke (DRAW_PROGRESS) resolves.
        assertTrue(text.contains("Reveals are typed per property"))
        assertTrue(text.contains("resolves only stroke (DRAW_PROGRESS) reveals"))
        assertTrue(text.contains("returns null for fill/clip"))
        // Where: the typed replacements of the shim.
        assertTrue(text.contains("Use strokeRevealFor, fillRevealFor, or read Segment.reveal directly"))
    }
}
