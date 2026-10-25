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
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parameter application through the timeline evaluator. */
class TimelineEvaluatorParamsTest {

    private fun paths(vararg data: Pair<String, List<PathNode>>): ImageVector {
        val builder = ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f)
        data.forEach { (name, pathData) ->
            builder.addPath(pathData = pathData, fill = SolidColor(Color.Black), name = name)
        }
        return builder.build()
    }

    private fun triangle() = listOf(
        PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 3f), PathNode.LineTo(12f, 21f), PathNode.Close,
    )

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    /** A path whose contour is ~291x shorter than [triangle]'s (~58 units). */
    private fun tinyLine() = listOf(PathNode.MoveTo(11.9f, 12f), PathNode.LineTo(12.1f, 12f))

    private fun plan(vector: ImageVector) = MorphPlanner.plan(
        VectorCompiler.compile(vector), VectorCompiler.compile(vector), MorphConfiguration.Default,
    )

    @Test
    fun morphProgressOverrideReplacesTheGlobalFraction() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { morph(from = 0.8f, to = 1f, interval = 0f..1f) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        val override = frame.pathProps[0].props[PropKey.MORPH_PROGRESS.ordinal]
        assertEquals(0.9f, override, 1e-3f)
        assertEquals(0.9f, TimelineEvaluator.morphFraction(frame.pathProps[0].props, 0.5f), 1e-3f)
    }

    @Test
    fun withoutOverrideMorphFractionFollowsGlobalProgress() {
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        assertEquals(0.42f, TimelineEvaluator.morphFraction(frame.pathProps[0].props, 0.42f), 0f)
    }

    @Test
    fun rotationTrackTakesTheShortAngularRoute() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { rotation(from = 350f, to = 10f, interval = 0f..1f) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        // 350° → 10° crosses 360°, so the midpoint is 0°/360° — not 180°
        assertEquals(360f, frame.pathProps[0].props[PropKey.ROTATION_DEG.ordinal], 1e-3f)
    }

    @Test
    fun scaleYDefaultsToFollowingScaleX() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { scale(from = 1f, to = 2f) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(1f, frame)
        assertEquals(2f, frame.pathProps[0].props[PropKey.SCALE_X.ordinal], 1e-4f)
        assertEquals(2f, frame.pathProps[0].props[PropKey.SCALE_Y.ordinal], 1e-4f)
    }

    @Test
    fun pivotTracksCarryTheConfiguredOrigin() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { scale(1f, 2f, origin = com.imorpher.vectormorph.core.model.TransformOrigin.TopLeft) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(1f, frame)
        assertEquals(0f, frame.pathProps[0].props[PropKey.PIVOT_X.ordinal], 1e-4f)
        assertEquals(0f, frame.pathProps[0].props[PropKey.PIVOT_Y.ordinal], 1e-4f)
    }

    @Test
    fun strokeWidthTrackOverridesThePathWidth() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { strokeWidth(1f, 3f) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(2f, frame.pathProps[0].props[PropKey.STROKE_WIDTH.ordinal], 1e-4f)
    }

    @Test
    fun fillAndStrokeAlphaTracksAreSeparateMultipliers() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") {
                fillAlpha(0f, 1f, interval = 0f..1f)
                strokeAlpha(1f, 0f, interval = 0f..1f)
            }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(0.5f, frame.pathProps[0].props[PropKey.FILL_ALPHA.ordinal], 1e-4f)
        assertEquals(0.5f, frame.pathProps[0].props[PropKey.STROKE_ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun brushTracksProduceInterpolatedResultsForKnownKinds() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") {
                color(Color.Red, Color.Blue, interval = 0f..1f)
            }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(PathFrameValues.BRUSH_INTERPOLATED, frame.pathProps[0].brushMode)
        assertNotNull(frame.pathProps[0].primaryBrush)
        assertNull(frame.pathProps[0].secondaryBrush)
    }

    @Test
    fun unknownBrushesFallIntoCrossfadeMode() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { gradient(BrushSpec.Unknown(androidx.compose.ui.graphics.SolidColor(Color.Magenta)), BrushSpec.Solid(Color.Cyan)) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.25f, frame)
        assertEquals(PathFrameValues.BRUSH_CROSSFADE, frame.pathProps[0].brushMode)
        assertEquals(0.25f, frame.pathProps[0].brushProgress, 1e-4f)
        assertNotNull(frame.pathProps[0].primaryBrush)
        assertNotNull(frame.pathProps[0].secondaryBrush)
    }

    @Test
    fun strokeColorTracksUseTheStrokeChannel() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { strokeColor(Color.Red, Color.Blue) }
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(PathFrameValues.BRUSH_NONE, frame.pathProps[0].brushMode)
        assertEquals(PathFrameValues.BRUSH_INTERPOLATED, frame.pathProps[0].strokeBrushMode)
    }

    @Test
    fun drawConfigForDisambiguatesRepeatedNamesByOccurrence() {
        val builder = ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f)
        builder.addPath(pathData = line(), fill = SolidColor(Color.Black), name = "dup")
        builder.addPath(pathData = line(), fill = SolidColor(Color.Black), name = "dup")
        val compiled = VectorCompiler.compile(builder.build())
        MorphPlanner.plan(compiled, compiled)

        // occurrence 0 matches the plain name; occurrence 1 compiles to "dup[1]"
        val occurrence1 = VectorAnimation {
            defaultEasing(LinearEasing)
            path("dup", occurrence = 1) { drawProgress(interval = 0f..1f) }
        }
        assertNotNull(occurrence1.drawConfigFor("dup[1]", "dup[1]", 1, 1, PropKey.DRAW_PROGRESS))
        assertNull(occurrence1.drawConfigFor("dup", "dup", 0, 0, PropKey.DRAW_PROGRESS))
    }

    @Test
    fun byPathTimingSequencesPathsInDrawOrder() {
        val vector = paths("first" to line(), "second" to triangle())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
        }
        val plan = plan(vector)
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.BY_PATH)
            .evaluate(0.75f, frame)
        // the triangle (corner distance 6) outranks the line (15): at p=0.75 the triangle
        // is done and the line is halfway through its own window.
        assertEquals(1f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 1e-4f)
        assertEquals(0.5f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-3f)
    }

    @Test
    fun byCommandTimingGivesEverySegmentEqualTime() {
        val vector = paths("body" to triangle())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
        }
        val plan = plan(vector)
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.BY_COMMAND)
            .evaluate(1f, frame)
        assertEquals(1f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun uniformTimingIgnoresPathLengths() {
        val vector = paths("long" to triangle(), "short" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
        }
        val plan = plan(vector)
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(0.5f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-4f)
        assertEquals(0.5f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun staggerKeepsShortPathsVisibleUnderPathLengthTiming() {
        // Mirror of uniformTimingIgnoresPathLengths under stagger: the tiny contour (291x
        // shorter than the triangle) starts 0.2 into the timeline and unbounded path-length
        // speed-up saturates it at ~0.203 — long before any viewer can see it. Contract:
        // under active stagger every path keeps a draw window of at least 0.05 of the timeline.
        val vector = paths("long" to triangle(), "short" to tinyLine())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f, interval = 0f..1f) }
            stagger(delay = 0.2f, order = DrawOrderStrategy.BY_INDEX)
        }
        val plan = plan(vector)
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB)
            .evaluate(0.23f, frame) // just inside the old dead zone (shortOffset + 0.03)
        val shortAlpha = frame.pathProps[1].props[PropKey.ALPHA.ordinal]
        val longAlpha = frame.pathProps[0].props[PropKey.ALPHA.ordinal]
        assertTrue("long path must be visibly underway (alpha=$longAlpha)", longAlpha > 0.1f)
        assertTrue(
            "short path must stay mid-draw inside its stagger window (alpha=$shortAlpha)",
            shortAlpha > 0f && shortAlpha < 1f,
        )
    }

    @Test
    fun staggerDelaysLaterRanksInConfiguredOrder() {
        val vector = paths("top" to line(), "bottom" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f, interval = 0f..1f) }
            stagger(delay = 0.3f, order = DrawOrderStrategy.BY_INDEX)
        }
        val plan = plan(vector)
        val frame = EvaluatedFrame(plan)
        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
        evaluator.evaluate(0.15f, frame)
        // pair 0 starts immediately, pair 1 (rank 1, delay 0.3) has not started
        assertTrue(frame.pathProps[0].props[PropKey.ALPHA.ordinal] > 0f)
        assertEquals(0f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 0f)
        // by the end every path completes
        evaluator.evaluate(1f, frame)
        assertEquals(1f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-4f)
        assertEquals(1f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun configurationStaggerOverridesTheAnimationStagger() {
        val vector = paths("a" to line(), "b" to line())
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
            stagger(delay = 0f, order = DrawOrderStrategy.BY_INDEX)
        }
        val plan = MorphPlanner.plan(
            VectorCompiler.compile(vector), VectorCompiler.compile(vector),
            MorphConfiguration(staggerDelay = 0.4f),
        )
        val frame = EvaluatedFrame(plan)
        // evaluator built with the configuration's stagger delay
        TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB,
            staggerDelay = 0.4f, timingMode = TimingMode.UNIFORM,
        ).evaluate(0.2f, frame)
        assertTrue(frame.pathProps[0].props[PropKey.ALPHA.ordinal] > 0f)
        assertEquals(0f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 0f)
    }

    @Test
    fun customStartOffsetReachesDrawConfigCustomPathStart() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(from = 0f, to = 1f, interval = 0f..1f, start = PathStart.CUSTOM)
            }
        }
        val config = animation.drawConfigFor("body", "body", 0, 0)!!
        assertEquals(PathStart.CUSTOM, config.start)
    }

    @Test
    fun evaluationResetsBrushAndCustomStateBetweenFrames() {
        // The brush track targets a DIFFERENT path: the body pair must never inherit it,
        // and state from a previous evaluation must not leak into unrelated pairs.
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("other") { color(Color.Red, Color.Blue) }
        }
        val plan = plan(paths("body" to triangle(), "other" to line()))
        val frame = EvaluatedFrame(plan)
        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
        evaluator.evaluate(0.5f, frame)
        assertEquals(PathFrameValues.BRUSH_INTERPOLATED, frame.pathProps[1].brushMode)
        assertEquals(PathFrameValues.BRUSH_NONE, frame.pathProps[0].brushMode)
        assertNull(frame.pathProps[0].primaryBrush)
        assertNull(frame.pathProps[0].customOverrides)
        // re-evaluating keeps unrelated pairs clean
        evaluator.evaluate(1f, frame)
        assertEquals(PathFrameValues.BRUSH_NONE, frame.pathProps[0].brushMode)
        assertNull(frame.pathProps[0].primaryBrush)
    }

    @Test
    fun vectorLevelBrushTracksDriveTheVectorFields() {
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            color(Color.Red, Color.Blue)
        }
        val plan = plan(paths("body" to triangle()))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)
        assertEquals(PathFrameValues.BRUSH_INTERPOLATED, frame.vectorBrushMode)
        assertNotNull(frame.vectorPrimaryBrush)
    }
}
