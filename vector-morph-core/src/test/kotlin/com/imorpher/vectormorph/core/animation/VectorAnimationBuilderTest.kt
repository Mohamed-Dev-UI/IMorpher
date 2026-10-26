package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.MorphPlanner
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `VectorAnimation { ... }` DSL compiler: keyframes, intervals, tracks. */
class VectorAnimationBuilderTest {

    private fun triangle() = listOf(
        PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 3f), PathNode.LineTo(12f, 21f), PathNode.Close,
    )

    private fun prepared() = VectorCompiler.compile(
        ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(pathData = triangle(), fill = SolidColor(Color.Black), name = "body")
        }.build(),
    )

    // ------------------------------------------------------------------ keyframes

    @Test
    fun keyframesInterpolateBetweenAndHoldBeyond() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(0f) { alpha = 0f }
            at(1f) { alpha = 1f }
        }
        val track = animation.tracks.single { it.prop == PropKey.ALPHA }
        assertEquals(1, track.segments.size)
        val segment = track.segments.single()
        assertEquals(0f, segment.start, 0f)
        assertEquals(1f, segment.end, 0f)
        assertEquals(0f, segment.from, 0f)
        assertEquals(1f, segment.to, 0f)

        // evaluation holds outside the key span
        val plan = MorphPlanner.plan(prepared(), prepared())
        val frame = EvaluatedFrame(plan)
        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
        evaluator.evaluate(0.5f, frame)
        assertEquals(0.5f, frame.vectorProps[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun singleKeyframeHoldsItsValueAcrossTheWholeTimeline() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(1f) { scale = 1f; rotation = 8f }
        }
        val scaleTrack = animation.tracks.single { it.prop == PropKey.SCALE_X }
        assertEquals(0f, scaleTrack.segments.single().start, 0f)
        assertEquals(1f, scaleTrack.segments.single().end, 0f)
        assertEquals(1f, scaleTrack.segments.single().from, 0f)
    }

    @Test
    fun keyframeScaleSetterDrivesBothAxes() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(0f) { scale = 0.8f }
            at(1f) { scale = 1.2f }
        }
        val scaleX = animation.tracks.single { it.prop == PropKey.SCALE_X }
        val scaleY = animation.tracks.single { it.prop == PropKey.SCALE_Y }
        assertEquals(0.8f, scaleX.segments.single().from, 0f)
        assertEquals(0.8f, scaleY.segments.single().from, 0f)
        assertEquals(1.2f, scaleX.segments.single().to, 0f)
        assertEquals(1.2f, scaleY.segments.single().to, 0f)
    }

    @Test
    fun keyframesMergePropertiesAtTheSameTime() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(0f) { alpha = 0f }
            at(0.5f) { alpha = 1f; rotation = 90f }
            at(1f) { rotation = 0f }
        }
        val alphaTrack = animation.tracks.single { it.prop == PropKey.ALPHA }
        val rotationTrack = animation.tracks.single { it.prop == PropKey.ROTATION_DEG }
        assertEquals(1, alphaTrack.segments.size) // 0 → 0.5, then holds
        assertEquals(1, rotationTrack.segments.size)
    }

    @Test
    fun keyframeTimesOutsideTheUnitIntervalAreClamped() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(-1f) { alpha = 0f }
            at(2f) { alpha = 1f }
        }
        val segment = animation.tracks.single { it.prop == PropKey.ALPHA }.segments.single()
        assertEquals(0f, segment.start, 0f)
        assertEquals(1f, segment.end, 0f)
    }

    @Test
    fun colorKeyframesFormABrushTimeline() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(0f) { fillColor = Color.Red }
            at(1f) { fillColor = Color.Blue }
        }
        val brushTrack = animation.brushTracks.single()
        assertEquals(1, brushTrack.segments.size)
        val from = brushTrack.segments.single().from as BrushSpec.Solid
        val to = brushTrack.segments.single().to as BrushSpec.Solid
        assertEquals(Color.Red, from.color)
        assertEquals(Color.Blue, to.color)
    }

    @Test
    fun singleColorKeyframeHoldsOneColor() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(0.4f) { fillColor = Color.Green }
        }
        val segment = animation.brushTracks.single().segments.single()
        assertEquals(0f, segment.start, 0f)
        assertEquals(1f, segment.end, 0f)
        assertEquals(Color.Green, (segment.from as BrushSpec.Solid).color)
        assertEquals(Color.Green, (segment.to as BrushSpec.Solid).color)
    }

    // ------------------------------------------------------------------ intervals

    @Test
    fun degenerateIntervalsAreDropped() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") {
                alpha(from = 0f, to = 1f, interval = 0.5f..0.5f)
                alpha(from = 0f, to = 1f, interval = 0.8f..0.2f)
            }
        }
        assertTrue("no tracks expected for degenerate intervals", animation.tracks.isEmpty())
    }

    @Test
    fun intervalsOutsideTheTimelineAreClamped() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { alpha(from = 0f, to = 1f, interval = -1f..2f) }
        }
        val segment = animation.tracks.single().segments.single()
        assertEquals(0f, segment.start, 0f)
        assertEquals(1f, segment.end, 0f)
    }

    @Test
    fun overlappingIntervalsForOnePropertyStackAsSegments() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") {
                alpha(0f, 1f, interval = 0f..0.4f)
                alpha(1f, 0f, interval = 0.3f..0.8f)
            }
        }
        val segments = animation.tracks.single().segments
        assertEquals(2, segments.size)
        assertEquals(0f, segments[0].start, 0f)
        assertEquals(0.3f, segments[1].start, 0f)
    }

    // ------------------------------------------------------------------ reveal helper

    @Test
    fun revealCouplesAFillSweepBehindTheStroke() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            reveal(mode = com.imorpher.vectormorph.core.model.DrawMode.FORWARD, interval = 0f..1f)
        }
        val drawTrack = animation.tracks.single { it.prop == PropKey.DRAW_PROGRESS }
        val fillTrack = animation.tracks.single { it.prop == PropKey.FILL_PROGRESS }
        assertEquals(AnimationTarget.AllPaths, drawTrack.target)
        assertEquals(AnimationTarget.AllPaths, fillTrack.target)

        // the reveal helper adds a 0→1 fill ramp whose interval starts past the stroke
        // start (0.65 of the way in): the fill trails the pen.
        val fillSegments = fillTrack.segments.sortedBy { it.start }
        assertTrue(fillSegments.isNotEmpty())
        assertEquals(1f, fillSegments.last().to, 0f)
        assertTrue(
            "fill must trail the stroke start (${fillSegments.last().start})",
            fillSegments.last().start >= 0.65f - 0.01f,
        )

        // reveal configs survive compilation: the fill mode rides the FILL_PROGRESS track
        val draw = animation.strokeRevealFor("body", "body", 0, 0)
        assertNotNull(draw)
        val fillReveal = animation.fillRevealFor("body", "body", 0, 0)
        assertNotNull(fillReveal)
        assertEquals(FillMode.DIRECTIONAL, fillReveal!!.fillMode)
    }

    @Test
    fun revealWithoutFillCouplingOnlyAddsTheStrokeTrack() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            reveal(followWithFill = false)
        }
        assertTrue(animation.tracks.singleOrNull { it.prop == PropKey.DRAW_PROGRESS } != null)
        assertNull(animation.tracks.singleOrNull { it.prop == PropKey.FILL_PROGRESS })
    }

    @Test
    fun staggerSettingsAreKeptOnTheBuiltAnimation() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            stagger(delay = 0.12f, order = DrawOrderStrategy.BOTTOM_RIGHT_TO_TOP_LEFT)
        }
        assertEquals(0.12f, animation.staggerDelay, 0f)
        assertEquals(DrawOrderStrategy.BOTTOM_RIGHT_TO_TOP_LEFT, animation.staggerOrder)
    }

    @Test
    fun defaultEasingAppliesToTracksWithoutExplicitEasing() {
        val animation = VectorAnimation {
            defaultEasing(FastOutSlowInEasing)
            path("body") { alpha(0f, 1f) }
        }
        // FastOutSlowIn at 0.5 is noticeably past linear 0.5
        val evaluatorLike = animation.tracks.single().segments.single().easing
        assertTrue(evaluatorLike.transform(0.5f) > 0.5f)
    }

    @Test
    fun keyframeEasingAppliesToTheSegmentLeavingThatKey() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            at(0f) { alpha = 0f }
            at(1f, easing = FastOutSlowInEasing) { alpha = 1f }
        }
        val eased = animation.tracks.single().segments.single().easing
        assertTrue(eased.transform(0.5f) > 0.5f)
    }

    @Test
    fun keyframeScopeDefaultsMatchTheirNeutralValues() {
        val scope = KeyframeScope()
        assertEquals(1f, scope.alpha, 0f)
        assertEquals(1f, scope.scale, 0f)
        assertEquals(-1f, scope.scaleY, 0f)
        assertEquals(0f, scope.rotation, 0f)
        assertEquals(1f, scope.reveal, 0f)
        assertEquals(1f, scope.fillProgress, 0f)
        assertEquals(0f, scope.morphProgress, 0f)
        assertNull(scope.fillColor)
    }

    @Test
    fun vectorLevelTracksWriteVectorPropsNotPathProps() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            alpha(from = 0.25f, to = 1f)
            translation(0f, 0f, 10f, -10f)
        }
        val plan = MorphPlanner.plan(prepared(), prepared())
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(0.625f, frame.vectorProps[PropKey.ALPHA.ordinal], 1e-4f)
        assertEquals(5f, frame.vectorProps[PropKey.TRANSLATION_X.ordinal], 1e-4f)
        assertEquals(-5f, frame.vectorProps[PropKey.TRANSLATION_Y.ordinal], 1e-4f)
        assertTrue(frame.pathProps[0].props[PropKey.ALPHA.ordinal].isNaN())
    }

    @Test
    fun customizerIsPreservedThroughBuild() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            customAnimation { null }
        }
        assertNotNull(animation.customizer)
    }

    @Test
    fun morphProgressConfigurationRoundTripsThroughThePlanConfig() {
        // The configuration carries the animation; planning must not mutate it.
        val animation = VectorAnimation { defaultEasing(LinearEasing) }
        val config = MorphConfiguration(animation = animation, timing = TimingMode.BY_PATH)
        val plan = MorphPlanner.plan(prepared(), prepared(), config)
        assertTrue(plan.pairs.isNotEmpty())
        assertEquals(TimingMode.BY_PATH, config.timing)
    }
}
