package com.imorpher.vectormorph.core.api

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.animation.SpringEasing
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimationCodec
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.geometry.ArcLengthData
import com.imorpher.vectormorph.core.geometry.ArcToCubic
import com.imorpher.vectormorph.core.geometry.BooleanGeometry
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import com.imorpher.vectormorph.core.geometry.PathNodeNormalizer
import com.imorpher.vectormorph.core.geometry.Subdivider
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.gradients.linearGradientSpecAtAngle
import com.imorpher.vectormorph.core.gradients.radialGradientSpec
import com.imorpher.vectormorph.core.gradients.sweepGradientSpec
import com.imorpher.vectormorph.core.interpolation.ColorInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.matching.PathMatcher
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MorphDebugOptions
import com.imorpher.vectormorph.core.model.PathMapping
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.model.Transform2D
import com.imorpher.vectormorph.core.model.TransformOrigin
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.imorpher.vectormorph.core.plan.IssueSeverity
import com.imorpher.vectormorph.core.plan.MorphCompatibility
import com.imorpher.vectormorph.core.plan.MorphIssue
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.MorphPlanCache
import com.imorpher.vectormorph.core.plan.MorphPlanner
import com.imorpher.vectormorph.core.plan.MorphReport
import com.imorpher.vectormorph.core.plan.planMorph
import com.imorpher.vectormorph.core.validation.MorphValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time fixture over `vector-morph-core`'s public surface.
 *
 * Every symbol pinned here is part of the advertised API: renaming a type, changing a
 * constructor parameter, or removing a member breaks this file at compile time, which makes
 * accidental API drift a review-visible event instead of a silent consumer break.
 */
class CoreApiSurfaceTest {

    // ------------------------------------------------------------------ fixtures

    private fun square(name: String, color: Color): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = listOf(
                    PathNode.MoveTo(2f, 2f),
                    PathNode.LineTo(22f, 2f),
                    PathNode.LineTo(22f, 22f),
                    PathNode.LineTo(2f, 22f),
                    PathNode.Close,
                ),
                fill = SolidColor(color),
                name = "body",
            )
            .build()

    private fun triangle(name: String, color: Color): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = listOf(
                    PathNode.MoveTo(12f, 2f),
                    PathNode.LineTo(22f, 22f),
                    PathNode.LineTo(2f, 22f),
                    PathNode.Close,
                ),
                fill = SolidColor(color),
                stroke = SolidColor(Color.Black),
                name = "body",
            )
            .build()

    // ------------------------------------------------------------------ model + plan

    @Test
    fun configurationAndDebugSurfaceCompiles() {
        val config = MorphConfiguration(
            pathMatching = com.imorpher.vectormorph.core.model.PathMatchingStrategy.AUTO,
            startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.AUTO,
            pathDirection = com.imorpher.vectormorph.core.model.PathDirectionStrategy.AUTO,
            drawOrder = DrawOrderStrategy.BY_INDEX,
            customDrawOrder = emptyList(),
            missingPath = com.imorpher.vectormorph.core.model.MissingPathBehavior.FADE,
            fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.AUTO,
            timing = com.imorpher.vectormorph.core.model.TimingMode.BY_PATH_LENGTH,
            colorSpace = ColorInterpolationSpace.SRGB,
            strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.AUTO,
            animation = null,
            staggerDelay = 0f,
            staggerOrder = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
            customMappings = emptyList(),
            customDrawAnchors = emptyMap(),
            defaultFillMode = FillMode.DIRECTIONAL,
            defaultFillDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            debug = MorphDebugOptions(showPathNumbers = true, markerArcFraction = 0.5f),
            keepRenderOrder = true,
            directionTraversal = com.imorpher.vectormorph.core.model.DirectionTraversal.HONOR_DIRECTION,
            segmentBudget = 16,
            simplificationTolerance = 0.25f,
            viewportNormalization = ViewportNormalization.FIT,
        )

        assertEquals(16, config.segmentBudget)
        assertEquals(0.5f, config.debug?.markerArcFraction ?: 0f, 0f)
        assertNotNull(MorphConfiguration.Default)

        val mapping = PathMapping("source", "target")
        assertEquals("source", mapping.source)
        assertEquals("target", mapping.target)

        val issue = MorphIssue(IssueSeverity.WARNING, "contour-count", listOf("outline"), "mismatch")
        assertEquals(IssueSeverity.WARNING, issue.severity)

        val report = MorphReport(
            compatibility = MorphCompatibility(0.75f, listOf("warning")),
            issues = listOf(issue),
            unmatchedSourcePaths = emptyList(),
            unmatchedTargetPaths = emptyList(),
            fallbackUsed = false,
            fallbackStrategy = null,
            fallbackReason = null,
        )
        assertEquals(0.75f, report.compatibility.score, 0f)
        assertNotNull(MorphValidator)

        assertEquals(0.5f, TransformOrigin.Center.x, 0f)
        assertEquals(1f, TransformOrigin.TopRight.x, 0f)
        assertEquals(0.7f, TransformOrigin.Custom(0.3f, 0.7f).y, 0f)
        assertEquals(Transform2D.Identity, Transform2D().scaled(0f))
    }

    @Test
    fun compilePlanAndCacheSurfaceCompiles() {
        val from = VectorCompiler.compile(square("from", Color.Red))
        val to = VectorCompiler.compile(triangle("to", Color.Blue))

        assertNotNull(from.contentKey)
        assertNotNull(from.boundsDiagonal)
        assertTrue(from.paths.isNotEmpty())

        val prepared: PreparedVector = from
        val preparedPath: PreparedPath = from.paths.first()
        assertEquals(StrokeCap.Butt, preparedPath.strokeLineCap)
        assertEquals(StrokeJoin.Miter, preparedPath.strokeLineJoin)

        val plan: MorphPlan = MorphPlanner.plan(from, to, MorphConfiguration.Default)
        assertNotNull(plan.pairs)
        assertNotNull(plan.report)
        assertSame(from, plan.from)

        // Convenience one-shot planner over raw ImageVectors.
        val convenience = planMorph(square("a", Color.Red), square("b", Color.Blue))
        assertNotNull(convenience)

        // Structural cache keys: equal-but-distinct compiled vectors share one entry.
        val cache = MorphPlanCache(maxEntries = 8, maxBytes = 4L * 1024 * 1024)
        assertEquals(0, cache.size)
        assertEquals(0L, cache.byteSize)
        val first = cache.getOrPut(from, to, MorphConfiguration.Default) { plan }
        val second = cache.getOrPut(
            VectorCompiler.compile(square("from", Color.Red)),
            VectorCompiler.compile(triangle("to", Color.Blue)),
            MorphConfiguration.Default,
        ) { error("structural keys must hit the cache") }
        assertSame(first, second)
        assertEquals(1, cache.size)
        assertSame(plan, cache.get(from, to, MorphConfiguration.Default))
        assertTrue(cache.byteSize > 0L)
        assertTrue(MorphPlanCache.estimateBytes(plan) > 0L)

        cache.trimToBytes(0L)
        assertEquals(0, cache.size)
        cache.clear()
        assertEquals(0, cache.size)

        // Presence-only checks for pure helpers whose exact signatures are exercised elsewhere.
        assertNotNull(Subdivider)
        assertNotNull(com.imorpher.vectormorph.core.matching.ContourAligner)
        assertNotNull(BooleanGeometry)
        assertNotNull(ArcToCubic)
        assertNotNull(PathNodeNormalizer)
        assertNotNull(Cubic(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f))
        assertNotNull(ContourData(emptyList(), true))
        assertNotNull(ArcLengthData(floatArrayOf(0f), floatArrayOf(0f), 0f))

        assertEquals(1f, PathMatcher.scorePair(preparedPath, preparedPath), 0.0001f)
        val (matches, warnings) = PathMatcher.match(
            prepared, prepared, com.imorpher.vectormorph.core.model.PathMatchingStrategy.BY_INDEX, emptyList(),
        )
        assertEquals(1, matches.size)
        assertTrue(warnings.isEmpty())
    }

    // ------------------------------------------------------------------ animation

    @Test
    fun animationDslAndCodecSurfaceCompiles() {
        val animation: VectorAnimation = VectorAnimation {
            defaultEasing(FastOutSlowInEasing)
            at(0f) {
                alpha = 0f
                scale = 0.9f
                fillColor = Color.Red
            }
            at(1f) {
                alpha = 1f
                scale = 1f
                drawProgress = 1f
                fillProgress = 1f
                morphProgress = 1f
                strokeWidth = 2f
            }
            path("outline", occurrence = 0) {
                strokeReveal(
                    start = PathStart.TOP_RIGHT,
                    direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
                    interval = 0f..0.5f,
                )
                fill(mode = FillMode.DIRECTIONAL, interval = 0.4f..0.9f)
                clipReveal(direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT, interval = 0.3f..0.6f)
                gradient(
                    from = BrushSpec.Solid(Color.Red),
                    to = BrushSpec.Linear(listOf(Color.Red, Color.Blue), null, 0f, 0f, 24f, 24f),
                    interval = 0.2f..0.8f,
                )
                strokeGradient(
                    from = BrushSpec.Solid(Color.Black),
                    to = BrushSpec.Solid(Color.White),
                    interval = 0f..1f,
                )
            }
            pathIndex(1) { drawProgress(mode = DrawMode.CENTER_OUT) }
            group("layer") { rotation(from = 0f, to = 90f, pivot = TransformOrigin.Center) }
            allPaths { scale(from = 1f, to = 1.1f) }
            stagger(0.05f, DrawOrderStrategy.AUTO)
        }

        assertEquals(0.05f, animation.staggerDelay, 0f)
        assertNotNull(animation.strokeRevealFor("outline", "outline", 0, 0))
        assertNotNull(animation.fillRevealFor("outline", "outline", 0, 0))
        assertNotNull(animation.warnings)

        // Constructor-visible reveal payloads.
        val draw = VectorAnimation.DrawConfig(mode = DrawMode.REVERSE, start = PathStart.CENTER, direction = DrawDirection.AUTO)
        assertEquals(DrawMode.REVERSE, draw.mode)
        val fill = VectorAnimation.FillRevealConfig(fillMode = FillMode.RADIAL, fillDirection = null)
        assertEquals(FillMode.RADIAL, fill.fillMode)
        val clip = VectorAnimation.ClipRevealConfig(direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT)
        assertEquals(DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT, clip.direction)

        // Wire round-trip stays on the public codec entry points.
        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(animation))
        assertEquals(animation.staggerDelay, decoded.staggerDelay, 0f)
        assertNotNull(decoded.strokeRevealFor("outline", "outline", 0, 0))

        assertEquals(0.5f, SpringEasing.MediumBouncy.dampingRatio, 0.0001f)
        assertEquals(1500f / 50f, SpringEasing.MediumBouncy.stiffness, 0.0001f)
        assertNotEquals(0f, SpringEasing.of(0.5f, 400f).transform(0.5f))
    }

    // ------------------------------------------------------------------ gradients + interpolation

    @Test
    fun gradientAndInterpolationSurfaceCompiles() {
        val solid = BrushSpec.Solid(Color.Red)
        assertEquals(1f, solid.color.alpha, 0f)

        val linear = BrushSpec.Linear(
            colors = listOf(Color.Red, Color.Blue),
            stops = listOf(0f, 1f),
            startX = 0f, startY = 0f, endX = 24f, endY = 24f,
            angleDegrees = 45f,
        )
        assertEquals(45f, linear.angleDegrees ?: 0f, 0f)

        val atAngle = linearGradientSpecAtAngle(listOf(Color.Red, Color.Blue), angleDegrees = 90f, length = 2f)
        assertEquals(90f, atAngle.angleDegrees ?: 0f, 0f)

        val radial = radialGradientSpec(listOf(Color.Red, Color.Blue), radius = 0.5f)
        assertEquals(0.5f, radial.radius, 0f)
        val sweep = sweepGradientSpec(listOf(Color.Red, Color.Blue))
        assertEquals(0.5f, sweep.centerX, 0f)

        assertTrue(BrushSpec.Unknown(SolidColor(Color.Black)).isUnknown)
        assertTrue(BrushSpec.from(SolidColor(Color.Red)) is BrushSpec.Solid)
        // A built-in gradient extracts parametrically; the reflective path is covered by
        // BrushSpecFallbackTest, so only the shape of the result is pinned here.
        assertNotNull(BrushSpec.from(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.Red, Color.Blue))))
        assertNotNull(BrushSpec.asGradientOrNull(solid))

        assertEquals(90f, ColorInterpolator.lerpAngleDeg(0f, 180f, 0.5f), 0.001f)

        val gradient = GradientInterpolator.interpolate(
            solid, BrushSpec.Solid(Color.Blue), 0.5f, ColorInterpolationSpace.SRGB,
        )
        assertNotNull(gradient)
    }
}
