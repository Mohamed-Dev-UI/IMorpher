package com.imorpher.vectormorph.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.PropKey
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.interpolation.ColorInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.model.PathMapping
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.MorphPlanner
import com.imorpher.vectormorph.core.plan.PairKind
import com.imorpher.vectormorph.core.validation.MorphValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphPlanningTest {
    @Test
    fun compilerPreservesPathNamesAndViewport() {
        val vector = vector("sample", "outline" to triangle())
        val prepared = VectorCompiler.compile(vector)

        assertEquals(24f, prepared.viewportWidth, 0f)
        assertEquals(24f, prepared.viewportHeight, 0f)
        assertEquals("outline", prepared.paths.single().name)
        assertEquals(1, prepared.paths.single().contours.size)
    }

    @Test
    fun plannerHandlesUnmatchedPathsAndExplicitMappings() {
        val from = vector("from", "outline" to triangle(), "detail" to line())
        val to = vector("to", "body" to triangle(2f), "detail" to line(), "badge" to triangle(6f))
        val plan = MorphPlanner.plan(
            VectorCompiler.compile(from),
            VectorCompiler.compile(to),
            MorphConfiguration(
                pathMatching = PathMatchingStrategy.CUSTOM,
                customMappings = listOf(PathMapping("outline", "body"), PathMapping("detail", "detail")),
                missingPath = MissingPathBehavior.DRAW,
            ),
        )

        assertEquals(3, plan.pairs.size)
        assertTrue(plan.pairs.any { it.toPath?.name == "badge" && it.fromPath == null })
        assertEquals(listOf("badge"), plan.report.unmatchedTargetPaths)
        assertTrue(MorphValidator.validate(from, to).isNotEmpty())
    }

    @Test
    fun plannerNormalizesTargetGeometryIntoSourceViewport() {
        val from = VectorCompiler.compile(vector("source", "shape" to triangle()))
        val to = VectorCompiler.compile(vector("target", "shape" to triangle(), viewportWidth = 48f, viewportHeight = 48f))

        val plan = MorphPlanner.plan(from, to)

        assertEquals(24f, plan.to.viewportWidth, 0f)
        assertEquals(24f, plan.to.viewportHeight, 0f)
        assertEquals(1.5f, plan.to.paths.single().bounds[0], 1e-4f)
        assertTrue(plan.report.issues.any { it.category == "viewport" })
    }

    @Test
    fun missingPathCanMorphFromNearestUnusedSourcePath() {
        val from = vector("source", "known" to triangle(), "source-near" to triangle(6f))
        val to = vector("target", "known" to triangle(), "added" to triangle(6f))

        val plan = MorphPlanner.plan(
            VectorCompiler.compile(from),
            VectorCompiler.compile(to),
            MorphConfiguration(
                pathMatching = PathMatchingStrategy.CUSTOM,
                customMappings = listOf(PathMapping("known", "known")),
                missingPath = MissingPathBehavior.MORPH_FROM_NEAREST,
            ),
        )

        val added = plan.pairs.single { it.toPath?.name == "added" }
        assertEquals(PairKind.MORPH, added.kind)
        assertEquals("source-near", added.fromPath?.name)
        assertTrue(plan.report.unmatchedSourcePaths.isEmpty())
        assertTrue(plan.report.unmatchedTargetPaths.isEmpty())
    }

    @Test
    fun pathTimelineEvaluatesScalarAndRevealConfiguration() {
        val prepared = VectorCompiler.compile(vector("timeline", "outline" to triangle()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("outline") {
                easing = LinearEasing
                strokeReveal(
                    start = PathStart.TOP_RIGHT,
                    direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
                    interval = 0f..1f,
                    mode = DrawMode.FORWARD,
                )
            }
            at(0f) { scale = 0.8f; rotation = 350f }
            at(1f) { scale = 1f; rotation = 10f }
        }
        val frame = EvaluatedFrame(plan.pairs.size)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB).evaluate(0.5f, frame)

        assertEquals(0.5f, frame.pathProps[0].props[PropKey.DRAW_PROGRESS.ordinal], 1e-4f)
        assertEquals(0.9f, frame.vectorProps[PropKey.SCALE_X.ordinal], 1e-4f)
        assertEquals(360f, frame.vectorProps[PropKey.ROTATION_DEG.ordinal], 1e-3f)
        val draw = animation.drawConfigFor("outline", "outline", 0, 0)
        assertNotNull(draw)
        assertEquals(PathStart.TOP_RIGHT, draw!!.start)
        assertEquals(DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT, draw.direction)
    }

    @Test
    fun scalarTracksHoldTheirValuesBeforeAfterAndBetweenIntervals() {
        val prepared = VectorCompiler.compile(vector("intervals", "outline" to triangle()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("outline") {
                alpha(from = 0f, to = 1f, interval = 0.2f..0.4f)
                alpha(from = 0.25f, to = 0.75f, interval = 0.6f..0.8f)
            }
        }
        val evaluator = TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM,
        )
        val frame = EvaluatedFrame(plan)

        fun alphaAt(progress: Float): Float {
            evaluator.evaluate(progress, frame)
            return frame.pathProps.single().props[PropKey.ALPHA.ordinal]
        }

        assertEquals(0f, alphaAt(0.1f), 1e-4f)
        assertEquals(0.5f, alphaAt(0.3f), 1e-4f)
        assertEquals(1f, alphaAt(0.5f), 1e-4f)
        assertEquals(0.25f, alphaAt(0.6f), 1e-4f)
        assertEquals(0.5f, alphaAt(0.7f), 1e-4f)
        assertEquals(0.75f, alphaAt(0.9f), 1e-4f)
    }

    @Test
    fun brushTrackIntervalsAreClampedToTheNormalizedTimeline() {
        val prepared = VectorCompiler.compile(vector("brush-interval", "outline" to triangle()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("outline") { color(Color.Red, Color.Blue, interval = -1f..2f) }
        }
        val segment = animation.brushTracks.single().segments.single()

        assertEquals(0f, segment.start, 0f)
        assertEquals(1f, segment.end, 0f)

        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
        val frame = EvaluatedFrame(plan)
        evaluator.evaluate(0f, frame)
        assertEquals(Color.Red, (frame.pathProps.single().primaryBrush as SolidColor).value)
        evaluator.evaluate(1f, frame)
        assertEquals(Color.Blue, (frame.pathProps.single().primaryBrush as SolidColor).value)
    }

    @Test
    fun pathIndexTracksOnlyAPathWithThatDeclarationIndex() {
        val prepared = VectorCompiler.compile(vector("indexed", "first" to triangle(), "second" to line()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            pathIndex(1) { alpha(from = 0f, to = 1f) }
        }
        val frame = EvaluatedFrame(plan)

        TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM,
        ).evaluate(0.5f, frame)

        assertTrue(frame.pathProps[0].props[PropKey.ALPHA.ordinal].isNaN())
        assertEquals(0.5f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun pathLengthTimingLetsShortPathsFinishBeforeLongPaths() {
        val prepared = VectorCompiler.compile(vector("length", "long" to triangle(), "short" to line()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
        }
        val frame = EvaluatedFrame(plan.pairs.size)
        TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.BY_PATH_LENGTH,
        ).evaluate(0.5f, frame)

        assertEquals(0.5f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-4f)
        assertEquals(1f, frame.pathProps[1].props[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun repeatedSeekingReusesFrameAndPropertyBuffers() {
        val prepared = VectorCompiler.compile(vector("reuse", "outline" to triangle()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation { allPaths { alpha(0f, 1f) } }
        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB)
        val frame = EvaluatedFrame(plan)
        val pathBuffers = frame.pathProps
        val scalarBuffer = frame.pathProps.single().props

        repeat(2_000) { frameIndex -> evaluator.evaluate((frameIndex % 101) / 100f, frame) }

        assertSame(pathBuffers, frame.pathProps)
        assertSame(scalarBuffer, frame.pathProps.single().props)
    }

    @Test
    fun nestedGroupTracksKeepIndependentFrameValuesAndSharedBounds() {
        val compiled = VectorCompiler.compile(vector("groups", "leaf" to triangle()))
        val source = compiled.paths.single()
        val groupedPath = PreparedPath(
            name = source.name,
            index = source.index,
            groupNames = listOf("body", "detail"),
            contours = source.contours,
            fill = source.fill,
            fillAlpha = source.fillAlpha,
            stroke = source.stroke,
            strokeAlpha = source.strokeAlpha,
            strokeWidth = source.strokeWidth,
            strokeLineCap = source.strokeLineCap,
            strokeLineJoin = source.strokeLineJoin,
            strokeLineMiter = source.strokeLineMiter,
            pathFillType = source.pathFillType,
            blendMode = source.blendMode,
            clipContours = source.clipContours,
            sourceTransform = source.sourceTransform,
            bounds = source.bounds,
            centroidX = source.centroidX,
            centroidY = source.centroidY,
            area = source.area,
            totalLength = source.totalLength,
            contourLengths = source.contourLengths,
            windingSigns = source.windingSigns,
            arcLengths = source.arcLengths,
            startFractions = source.startFractions,
        )
        val groupedVector = PreparedVector(
            compiled.viewportWidth, compiled.viewportHeight, compiled.defaultWidth, compiled.defaultHeight,
            compiled.name, listOf(groupedPath),
        )
        val plan = MorphPlanner.plan(groupedVector, groupedVector)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            group("body") {
                rotation(0f, 90f)
                clipReveal(direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT)
                alpha(0.5f, 1f)
            }
            group("detail") { rotation(0f, 180f) }
        }
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
            .evaluate(0.5f, frame)

        val groups = frame.pathProps.single().groupProps
        assertEquals(45f, groups.getValue("body").props[PropKey.ROTATION_DEG.ordinal], 1e-4f)
        assertEquals(90f, groups.getValue("detail").props[PropKey.ROTATION_DEG.ordinal], 1e-4f)
        assertEquals(0.5f, groups.getValue("body").props[PropKey.CLIP_PROGRESS.ordinal], 1e-4f)
        assertEquals(0.75f, groups.getValue("body").props[PropKey.ALPHA.ordinal], 1e-4f)
        assertTrue(groups.getValue("body").groupBounds[0].isFinite())
        assertTrue(groups.getValue("detail").groupBounds[2].isFinite())
    }

    @Test
    fun sequentialTracksForOnePropertyRemainIndependent() {
        val prepared = VectorCompiler.compile(vector("timeline", "outline" to triangle()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            path("outline") {
                alpha(from = 0f, to = 1f, interval = 0f..0.25f)
                alpha(from = 1f, to = 0f, interval = 0.75f..1f)
            }
        }
        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB)
        val frame = EvaluatedFrame(plan.pairs.size)

        evaluator.evaluate(0.125f, frame)
        assertEquals(0.5f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-4f)
        evaluator.evaluate(0.875f, frame)
        assertEquals(0.5f, frame.pathProps[0].props[PropKey.ALPHA.ordinal], 1e-4f)
    }

    @Test
    fun automaticStaggerOrderUsesGeometry() {
        val bottom = listOf(
            PathNode.MoveTo(3f, 15f), PathNode.LineTo(21f, 15f),
            PathNode.LineTo(12f, 21f), PathNode.Close,
        )
        val prepared = VectorCompiler.compile(
            vector("order", "bottom" to bottom, "top" to triangle()),
        )
        val plan = MorphPlanner.plan(prepared, prepared)

        val order = com.imorpher.vectormorph.core.reveal.DrawOrder.orderIndices(
            plan, DrawOrderStrategy.AUTO, emptyList(),
        )

        assertEquals(1, order[0])
        assertEquals(0, order[1])
    }

    @Test
    fun staggerShiftsThePathClockBeforeTimingModeMapping() {
        val prepared = VectorCompiler.compile(vector("stagger", "long" to triangle(), "short" to line()))
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { strokeReveal(start = PathStart.START, interval = 0f..0.82f, mode = DrawMode.FORWARD) }
            stagger(delay = 0.15f, order = DrawOrderStrategy.BY_INDEX)
        }
        val frame = EvaluatedFrame(plan)

        // BY_PATH_LENGTH maps progress onto travel distance; the arc-length remap of the
        // short line must not silently cancel (or pre-saturate) its stagger offset.
        TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.BY_PATH_LENGTH,
        ).evaluate(0.1f, frame)
        assertTrue(frame.pathProps[0].props[PropKey.DRAW_PROGRESS.ordinal] > 0f)
        assertEquals(0f, frame.pathProps[1].props[PropKey.DRAW_PROGRESS.ordinal], 0f)

        TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.BY_PATH_LENGTH,
        ).evaluate(0.3f, frame)
        val shortDraw = frame.pathProps[1].props[PropKey.DRAW_PROGRESS.ordinal]
        assertTrue("short path must be travelling after its stagger starts: $shortDraw", shortDraw > 0f)
        assertTrue("short path must not have saturated the timeline: $shortDraw", shortDraw < 1f)
    }

    @Test
    fun groupTracksShareOneClockSoTheGroupStaysRigid() {
        val builder = ImageVector.Builder("grouped", 24.dp, 24.dp, 24f, 24f)
        builder.addGroup(name = "rays", pivotX = 12f, pivotY = 12f)
        builder.addPath(pathData = line(), fill = SolidColor(Color.Black), name = "member")
        builder.clearGroup()
        val prepared = VectorCompiler.compile(builder.build())
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            group("rays") { strokeReveal(start = PathStart.START, interval = 0f..0.7f, mode = DrawMode.FORWARD) }
            stagger(delay = 0.08f, order = DrawOrderStrategy.TOP_RIGHT_TO_BOTTOM_LEFT)
        }
        val evaluator = TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM,
        )
        val frame = EvaluatedFrame(plan)

        val draws = (0..10).map { step ->
            evaluator.evaluate(step / 10f, frame)
            frame.pathProps.single().groupProps.getValue("rays").props[PropKey.DRAW_PROGRESS.ordinal]
        }

        // The group's own clock ramps 0→1; stagger never staggers a group's descendants.
        assertEquals(0f, draws[0], 0f)
        assertEquals(0.2f / 0.7f, draws[2], 1e-3f)
        assertTrue(draws.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, draws[10], 1e-4f)
    }

    @Test
    fun evaluatedFramesDefaultToNoOverrideUntilTracksApply() {
        val prepared = VectorCompiler.compile(sparkVector())
        val plan = MorphPlanner.plan(prepared, prepared)
        val frame = EvaluatedFrame(plan)

        // Without an animation the renderer must fall back to the path defaults (alpha 1,
        // native stroke width, full geometry): a zero-filled buffer once rendered the
        // whole icon invisible.
        assertTrue(frame.pathProps.all { it.props[PropKey.ALPHA.ordinal].isNaN() })
        assertTrue(frame.pathProps.all { it.props[PropKey.STROKE_WIDTH.ordinal].isNaN() })
        assertTrue(frame.pathProps.all { it.props[PropKey.DRAW_PROGRESS.ordinal].isNaN() })
        assertTrue(frame.vectorProps[PropKey.ALPHA.ordinal].isNaN())

        // And every evaluation must reset overrides, otherwise one frame's timeline values
        // leak into the next seek.
        val animation = VectorAnimation { allPaths { alpha(0f, 1f) } }
        val evaluator = TimelineEvaluator(
            animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM,
        )
        evaluator.evaluate(1f, frame)
        assertTrue(frame.pathProps.all { it.props[PropKey.ALPHA.ordinal] == 1f })
        evaluator.evaluate(0f, frame)
        assertTrue("overrides must reset between frames", frame.pathProps.all { it.props[PropKey.ALPHA.ordinal] == 0f })
    }

    @Test
    fun penChoreographyCascadesRaysAcrossTheTimeline() {
        val (evaluator, frame) = sparkStaggeredRaysTiming()
        val drawIndex = PropKey.DRAW_PROGRESS.ordinal
        val alphaIndex = PropKey.ALPHA.ordinal

        fun drawAt(progress: Float, pairIndex: Int): Float {
            evaluator.evaluate(progress, frame)
            return frame.pathProps[pairIndex].props[drawIndex]
        }

        // The stagger fans the ray clocks around the hub so the pen visibly cascades
        // instead of drawing everything at once. Pair indices follow declaration order:
        // 4 is the first-ranked ray, 0 the second, 3 the third, 8 the core.
        val first = drawAt(0.2f, 4)
        val second = drawAt(0.2f, 0)
        assertTrue("first ray must be travelling at p=0.2: $first", first > 0f)
        assertTrue("second ray must lag the first: $second vs $first", second > 0f && second < first)
        assertEquals("third-ranked ray must still be queued", 0f, drawAt(0.2f, 3), 0f)

        // Every ray finishes before the timeline ends (the tail belongs to the core fill)…
        (0..7).forEach { index ->
            assertTrue("ray $index must finish drawing", drawAt(1f, index) == 1f)
        }
        assertTrue(
            "the last-ranked ray (W) must still be drawing at p=0.9: ${drawAt(0.9f, 2)}",
            drawAt(0.9f, 2) < 1f,
        )
        // …while the core stays faint until the wave reaches it and then fills in.
        assertEquals(0f, drawAt(0.1f, 8), 0f)
        assertEquals(0.15f, frame.pathProps[8].props[alphaIndex], 1e-3f)
        assertTrue("core must still be drawing at p=0.9: ${drawAt(0.9f, 8)}", drawAt(0.9f, 8) < 1f)
        assertTrue(drawAt(1f, 8) == 1f)
        assertEquals(1f, frame.pathProps[8].props[alphaIndex], 1e-3f)
    }

    @Test
    fun sRgbAndLinearSrgbUseDifferentTransferFunctions() {
        val srgbMid = ColorInterpolator.lerp(Color.Black, Color.White, 0.5f, ColorInterpolationSpace.SRGB)
        val linearMid = ColorInterpolator.lerp(Color.Black, Color.White, 0.5f, ColorInterpolationSpace.LINEAR_SRGB)

        assertEquals(0.5f, srgbMid.red, 0.01f)
        assertEquals(0.735f, linearMid.red, 0.02f)
        assertTrue(linearMid.red > srgbMid.red)
    }

    @Test
    fun solidToLinearGradientProducesAnInterpolatedBrush() {
        val from = BrushSpec.Solid(Color.Red)
        val to = BrushSpec.Linear(
            colors = listOf(Color.Blue, Color.Green), stops = listOf(0f, 1f),
            startX = 0f, startY = 0f, endX = 1f, endY = 0f,
        )

        val start = GradientInterpolator.interpolate(from, to, 0f, ColorInterpolationSpace.OKLAB)
        val middle = GradientInterpolator.interpolate(from, to, 0.5f, ColorInterpolationSpace.OKLAB)
        val end = GradientInterpolator.interpolate(from, to, 1f, ColorInterpolationSpace.OKLAB)

        assertTrue(start is GradientInterpolator.Result.Interpolated)
        assertTrue(middle is GradientInterpolator.Result.Interpolated)
        assertTrue(end is GradientInterpolator.Result.Interpolated)
    }

    @Test
    fun planCacheReturnsSameImmutablePlanForSameKeys() {
        val vector = VectorCompiler.compile(vector("cache", "path" to triangle()))
        val cache = com.imorpher.vectormorph.core.plan.MorphPlanCache()
        val first = cache.getOrPut(vector, vector, MorphConfiguration.Default) { MorphPlanner.plan(vector, vector) }
        val second = cache.getOrPut(vector, vector, MorphConfiguration.Default) { error("cached value expected") }

        assertTrue(first === second)
        assertEquals(1, cache.size)
    }

    /** Eight hub→tip rays plus a fill-only core, mirroring the showcase spark icon. */
    private fun sparkVector(): ImageVector {
        val builder = ImageVector.Builder("spark", 24.dp, 24.dp, 24f, 24f)
        val rays = listOf(
            listOf(PathNode.MoveTo(12f, 1.8f), PathNode.LineTo(12f, 6f)),
            listOf(PathNode.MoveTo(12f, 22.2f), PathNode.LineTo(12f, 18f)),
            listOf(PathNode.MoveTo(1.8f, 12f), PathNode.LineTo(6f, 12f)),
            listOf(PathNode.MoveTo(18f, 12f), PathNode.LineTo(22.2f, 12f)),
            listOf(PathNode.MoveTo(4.5f, 4.5f), PathNode.LineTo(7.5f, 7.5f)),
            listOf(PathNode.MoveTo(19.5f, 19.5f), PathNode.LineTo(16.5f, 16.5f)),
            listOf(PathNode.MoveTo(19.5f, 4.5f), PathNode.LineTo(16.5f, 7.5f)),
            listOf(PathNode.MoveTo(7.5f, 16.5f), PathNode.LineTo(4.5f, 19.5f)),
        )
        rays.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                stroke = SolidColor(Color(0xFF536DFE)),
                strokeLineWidth = 1.6f,
                name = "ray-$index",
            )
        }
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(12f, 7.5f),
                PathNode.CurveTo(14.485f, 7.5f, 16.5f, 9.515f, 16.5f, 12f),
                PathNode.CurveTo(16.5f, 14.485f, 14.485f, 16.5f, 12f, 16.5f),
                PathNode.CurveTo(9.515f, 16.5f, 7.5f, 14.485f, 7.5f, 12f),
                PathNode.CurveTo(7.5f, 9.515f, 9.515f, 7.5f, 12f, 7.5f),
                PathNode.Close,
            ),
            fill = SolidColor(Color(0xFF536DFE)),
            name = "core",
        )
        return builder.build()
    }

    /** The showcase-03 pen setup: hub→tip forward reveal, UNIFORM timing, clockwise stagger. */
    private fun sparkStaggeredRaysTiming(): Pair<TimelineEvaluator, EvaluatedFrame> {
        val prepared = VectorCompiler.compile(sparkVector())
        val plan = MorphPlanner.plan(prepared, prepared)
        val animation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths {
                strokeReveal(
                    start = PathStart.START,
                    direction = DrawDirection.AUTO,
                    interval = 0f..1f,
                    mode = DrawMode.FORWARD,
                )
            }
            path("core") {
                alpha(from = 0.15f, to = 1f, interval = 0.55f..0.85f)
                fill(mode = FillMode.RADIAL, interval = 0.6f..0.95f)
            }
            stagger(delay = 0.08f, order = DrawOrderStrategy.CLOCKWISE)
        }
        val evaluator = TimelineEvaluator(animation, plan, ColorInterpolationSpace.SRGB, timingMode = TimingMode.UNIFORM)
        return evaluator to EvaluatedFrame(plan)
    }

    private fun triangle(offset: Float = 0f) = listOf(
        PathNode.MoveTo(3f + offset, 3f),
        PathNode.LineTo(21f - offset, 3f),
        PathNode.LineTo(12f, 21f - offset),
        PathNode.Close,
    )

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    private fun vector(
        name: String,
        vararg paths: Pair<String, List<PathNode>>,
        viewportWidth: Float = 24f,
        viewportHeight: Float = 24f,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth, viewportHeight)
        paths.forEach { (pathName, pathData) ->
            builder.addPath(
                pathData = pathData,
                fill = SolidColor(Color.Black),
                name = pathName,
            )
        }
        return builder.build()
    }
}
