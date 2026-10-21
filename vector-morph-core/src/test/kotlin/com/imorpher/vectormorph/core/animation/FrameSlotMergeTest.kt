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
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Per-frame typed reveal slots ([PathFrameValues.strokeReveal]/[fillReveal]/[clipReveal]).
 * The three slots resolve exactly like the old ordinal-indexed `drawConfigs` array did:
 * each active reveal track overwrites the slot of its own property, group-scope reveals
 * land in the group's own values, and the renderer folds them with its
 * "only when the target slot is null" group-merge rule (MorphRenderer.mergeGroupVisuals).
 */
class FrameSlotMergeTest {

    private fun vectorOf(vararg pairsOf: Pair<String, List<PathNode>>): ImageVector {
        val builder = ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f)
        pairsOf.forEach { (name, pathData) ->
            builder.addPath(pathData = pathData, fill = SolidColor(Color.Black), name = name)
        }
        return builder.build()
    }

    /** One path named [member] inside the named group "g". */
    private fun groupedVector(member: String): ImageVector {
        val builder = ImageVector.Builder("grouped", 24.dp, 24.dp, 24f, 24f)
        builder.addGroup(name = "g", pivotX = 12f, pivotY = 12f)
        builder.addPath(pathData = line(), fill = SolidColor(Color.Black), name = member)
        builder.clearGroup()
        return builder.build()
    }

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    private fun plan(vector: ImageVector) = MorphPlanner.plan(
        VectorCompiler.compile(vector), VectorCompiler.compile(vector), MorphConfiguration.Default,
    )

    private fun evaluate(
        animation: VectorAnimation,
        vector: ImageVector,
        progress: Float = 0.5f,
    ): EvaluatedFrame {
        val p = plan(vector)
        val frame = EvaluatedFrame(p)
        TimelineEvaluator(
            animation, p, ColorInterpolationSpace.SRGB,
            timingMode = TimingMode.UNIFORM,
        ).evaluate(progress, frame)
        return frame
    }

    // ------------------------------------------------------------ slot = old array semantics

    @Test
    fun allPathsThenPerPathTracksResolveTheStrokeSlotByLastActiveWriter() {
        val vector = vectorOf("p" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { drawProgress(start = PathStart.AUTO, direction = DrawDirection.AUTO, interval = 0f..1f) }
            path("p") { strokeReveal(start = PathStart.BOTTOM, direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..1f) }
        }
        val frame = evaluate(animation, vector)
        val stroke = frame.pathProps[0].strokeReveal
        // Both stroke tracks are active at 0.5, so both wrote the same slot in the old
        // ordinal-indexed array — the later (per-path) declaration won. The typed slot
        // must resolve identically.
        assertEquals(PathStart.BOTTOM, stroke?.start)
        assertEquals(DrawDirection.BOTTOM_TO_TOP, stroke?.direction)
    }

    @Test
    fun oneEvaluationPopulatesAllThreeRevealSlotsAtOnce() {
        val vector = vectorOf("p" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths {
                strokeReveal(start = PathStart.TOP, direction = DrawDirection.TOP_TO_BOTTOM, interval = 0f..1f)
                fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..1f)
                clipReveal(direction = DrawDirection.LEFT_TO_RIGHT, interval = 0f..1f)
            }
        }
        val frame = evaluate(animation, vector)
        val values = frame.pathProps[0]
        // DRAW+FILL+CLIP tracks simultaneously: each writes its own typed slot in the
        // same evaluation — no slot borrows another property's config.
        assertEquals(PathStart.TOP, values.strokeReveal?.start)
        assertEquals(DrawDirection.TOP_TO_BOTTOM, values.strokeReveal?.direction)
        assertEquals(FillMode.DIRECTIONAL, values.fillReveal?.fillMode)
        assertEquals(DrawDirection.BOTTOM_TO_TOP, values.fillReveal?.fillDirection)
        assertEquals(DrawDirection.LEFT_TO_RIGHT, values.clipReveal?.direction)
    }

    // ------------------------------------------------------------ group-merge parity inputs

    @Test
    fun groupScopeRevealsStayInGroupSlotsSoTheNullOnlyMergePreservesPathOwns() {
        val vector = groupedVector("member")
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            group("g") { strokeReveal(start = PathStart.TOP, interval = 0f..1f) }
            path("member") { fill(mode = FillMode.DIRECTIONAL, interval = 0f..1f) }
        }
        val frame = evaluate(animation, vector)

        // The group's stroke reveal lives in the GROUP's own values; the direct pair
        // values carry only the path's own fill reveal. At render time the group-merge
        // ("only when the target slot is null") therefore leaves the path's own slots
        // untouched and lifts the group's stroke reveal into the null stroke slot.
        val direct = frame.pathProps[0]
        assertNull(direct.strokeReveal)
        assertNotNull(direct.fillReveal)
        assertEquals(FillMode.DIRECTIONAL, direct.fillReveal?.fillMode)

        val group = direct.groupProps.getValue("g")
        assertEquals(PathStart.TOP, group.strokeReveal?.start)
        assertNull(group.fillReveal)
        assertNull(group.clipReveal)
    }

    @Test
    fun frameValueCopyCarriesAllThreeRevealSlots() {
        // The renderer's merged-frame optimization copies per-frame visuals from the
        // evaluated values; the three typed slots must ride along (mirrors the old
        // drawConfigs.copyInto behaviour).
        val source = PathFrameValues()
        source.strokeReveal = DrawConfig(start = PathStart.BOTTOM)
        source.fillReveal = FillRevealConfig(FillMode.RADIAL, DrawDirection.COUNTER_CLOCKWISE)
        source.clipReveal = ClipRevealConfig(DrawDirection.RIGHT_TO_LEFT)
        val target = PathFrameValues()
        target.copyVisualsFrom(source)
        assertEquals(PathStart.BOTTOM, target.strokeReveal?.start)
        assertEquals(FillMode.RADIAL, target.fillReveal?.fillMode)
        assertEquals(DrawDirection.RIGHT_TO_LEFT, target.clipReveal?.direction)
    }

    @Test
    fun clearOnEachEvaluationKeepsReveallessSegmentsFromInheritingThePreviousSlot() {
        // One (target, prop) track can mix a reveal-bearing segment (fill) with a plain
        // one (fillProgress). The slot must resolve per active segment every frame: the
        // old drawConfigs array was nulled before the tracks ran, so a plain segment
        // clears the slot instead of leaking the previous frame's fill reveal.
        val vector = vectorOf("p" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("p") {
                fill(mode = FillMode.DIRECTIONAL, interval = 0.6f..1f)
                fillProgress(0f, 1f, interval = 0.2f..0.4f)
            }
        }
        val p = plan(vector)
        val frame = EvaluatedFrame(p)
        val evaluator = TimelineEvaluator(
            animation, p, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM,
        )
        evaluator.evaluate(0.8f, frame)
        assertEquals(FillMode.DIRECTIONAL, frame.pathProps[0].fillReveal?.fillMode)
        evaluator.evaluate(0.3f, frame)
        assertNull(frame.pathProps[0].fillReveal)
    }
}
