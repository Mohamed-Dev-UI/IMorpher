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
