package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Parameter application through the full render pipeline: every configuration knob and
 * timeline property below must measurably change the rendered frame. A parameter that
 * renders identically to its opposite is silently ignored — the regression class these
 * tests pin down. Differences are asserted on whole-bitmap deltas, so mirrored but
 * distinct frames still count as different.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MorphParameterApplicationTest {

    private lateinit var activity: ComponentActivity
    private val framePx = 96

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // ------------------------------------------------------------------ builders

    private fun filled(name: String, vararg contours: List<PathNode>, alpha: Float = 1f): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                fill = SolidColor(Color(0xFF12A4D9).copy(alpha = alpha)),
                name = "path-$index",
            )
        }
        return builder.build()
    }

    private fun stroked(name: String, data: List<PathNode>, width: Float = 2f): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = data,
            stroke = SolidColor(Color(0xFF12A4D9)),
            strokeLineWidth = width,
            name = "stroke",
        )
        return builder.build()
    }

    private fun squareAt(x: Float, y: Float, size: Float = 8f) = listOf(
        PathNode.MoveTo(x, y), PathNode.LineTo(x + size, y),
        PathNode.LineTo(x + size, y + size), PathNode.LineTo(x, y + size), PathNode.Close,
    )

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    /** Two disjoint squares as ONE multi-contour path: the double-fill regression shape. */
    private fun twoSquares() = squareAt(3f, 9f, 6f) + squareAt(15f, 9f, 6f)

    /** Diagonal pair that swaps corners between the morph endpoints. */
    private val swapFrom = listOf(squareAt(3f, 3f), squareAt(13f, 13f))
    private val swapTo = listOf(squareAt(13f, 3f), squareAt(3f, 13f))

    // ------------------------------------------------------------------ rendering

    private fun renderMorph(
        from: ImageVector,
        to: ImageVector,
        progress: Float,
        configuration: MorphConfiguration = MorphConfiguration.Default,
        animation: VectorAnimationSpec? = null,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(framePx.dp), contentAlignment = Alignment.Center) {
                MorphVector(
                    from = from,
                    to = to,
                    progress = progress,
                    configuration = if (animation != null) configuration.copy(animation = animation) else configuration,
                    contentDescription = null,
                    width = framePx.dp,
                    height = framePx.dp,
                )
            }
        }
        activity.setContentView(view)
        pumpMainLoop()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, framePx, framePx)
        pumpMainLoop()
        val bitmap = Bitmap.createBitmap(framePx, framePx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        pumpMainLoop()
        return bitmap
    }

    private fun pumpMainLoop() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun meanAlpha(bitmap: Bitmap): Float = BitmapAsserts.meanAlpha(bitmap)
    private fun paintedCount(bitmap: Bitmap): Int = BitmapAsserts.paintedCount(bitmap)
    private fun difference(a: Bitmap, b: Bitmap): Float = BitmapAsserts.meanAbsDifference(a, b)

    // ------------------------------------------------------------------ reveal parameters

    @Test
    fun forwardAndReverseDrawModesRenderDifferentMidFrames() {
        // The draw mode shapes the STROKE pass, so these paths must be stroked.
        val from = stroked("from", line(), width = 2f)
        val to = stroked("to", listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 6f)), width = 2f)
        val forward = renderMorph(from, to, 0.4f, animation = reveal(mode = DrawMode.FORWARD))
        val reverse = renderMorph(from, to, 0.4f, animation = reveal(mode = DrawMode.REVERSE))
        val delta = difference(forward, reverse)
        assertTrue(
            "DrawMode must change the mid-morph frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    @Test
    fun topAndBottomPathStartsRenderDifferentMidFrames() {
        // PathStart anchors the pen: a vertical line's TOP and BOTTOM anchors are its
        // opposite ends, so the reveal direction visibly differs.
        val from = stroked("from", listOf(PathNode.MoveTo(12f, 4f), PathNode.LineTo(12f, 20f)), width = 2f)
        val to = stroked("to", listOf(PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 20f)), width = 2f)
        val top = renderMorph(
            from, to, 0.4f,
            animation = reveal(mode = DrawMode.FORWARD, start = PathStart.TOP),
        )
        val bottom = renderMorph(
            from, to, 0.4f,
            animation = reveal(mode = DrawMode.FORWARD, start = PathStart.BOTTOM),
        )
        val delta = difference(top, bottom)
        assertTrue(
            "PathStart must change the mid-morph frame (delta=$delta)",
            delta > 0.005f,
        )
    }

    @Test
    fun fillDirectionsRenderDifferentMidFrames() {
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", squareAt(13f, 13f))
        val leftToRight = renderMorph(
            from, to, 0.5f,
            animation = fillReveal(mode = FillMode.DIRECTIONAL, direction = DrawDirection.LEFT_TO_RIGHT),
        )
        val rightToLeft = renderMorph(
            from, to, 0.5f,
            animation = fillReveal(mode = FillMode.DIRECTIONAL, direction = DrawDirection.RIGHT_TO_LEFT),
        )
        val delta = difference(leftToRight, rightToLeft)
        assertTrue(
            "fill direction must change the mid-reveal frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    @Test
    fun fillModesRenderDifferentMidFrames() {
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", squareAt(13f, 13f))
        val radial = renderMorph(
            from, to, 0.4f,
            animation = fillReveal(mode = FillMode.RADIAL, direction = DrawDirection.AUTO),
        )
        val linear = renderMorph(
            from, to, 0.4f,
            animation = fillReveal(mode = FillMode.DIRECTIONAL, direction = DrawDirection.AUTO),
        )
        val delta = difference(radial, linear)
        assertTrue(
            "FillMode must change the mid-reveal frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    @Test
    fun fadeFillModeActuallyFadesUnderAReveal() {
        // Regression: inside a morph pair's fill legs, FillMode.FADE ignored the reveal
        // progress and popped the fill in at full alpha.
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", squareAt(13f, 13f))
        val atQuarter = meanAlpha(
            renderMorph(from, to, 0.25f, animation = fillReveal(mode = FillMode.FADE, direction = DrawDirection.AUTO)),
        )
        val atFull = meanAlpha(
            renderMorph(from, to, 1f, animation = fillReveal(mode = FillMode.FADE, direction = DrawDirection.AUTO)),
        )
        assertTrue(
            "FADE fill must grow with progress: p=0.25 alpha $atQuarter vs p=1 alpha $atFull",
            atQuarter < atFull * 0.75f,
        )
    }

    // ------------------------------------------------------------------ timing / stagger

    @Test
    fun byPathTimingSequencesWholePathsMidMorph() {
        // TimingMode schedules the reveal tracks: with BY_PATH the second path's alpha
        // ramp only starts after the first finishes; UNIFORM runs both at once.
        val from = filled("from", swapFrom[0], swapFrom[1])
        val to = filled("to", swapTo[0], swapTo[1])
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(0.1f, 1f, interval = 0f..1f) }
        }
        val byPath = renderMorph(from, to, 0.5f, configuration = MorphConfiguration(timing = TimingMode.BY_PATH, animation = animation))
        val uniform = renderMorph(from, to, 0.5f, configuration = MorphConfiguration(timing = TimingMode.UNIFORM, animation = animation))
        val delta = difference(byPath, uniform)
        assertTrue(
            "TimingMode must change the mid-morph frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    @Test
    fun staggerDelaysLaterPathsMidMorph() {
        // stagger() offsets the per-path reveal clocks: with a visible alpha ramp the
        // delayed path must lag the un-staggered render.
        val from = filled("from", swapFrom[0], swapFrom[1])
        val to = filled("to", swapTo[0], swapTo[1])
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(0.1f, 1f, interval = 0f..1f) }
        }
        val staggered = renderMorph(from, to, 0.35f, configuration = MorphConfiguration(staggerDelay = 0.4f, animation = animation))
        val plain = renderMorph(from, to, 0.35f, configuration = MorphConfiguration(animation = animation))
        val delta = difference(staggered, plain)
        assertTrue(
            "staggerDelay must change the mid-morph frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    // ------------------------------------------------------------------ missing paths

    @Test
    fun missingPathBehaviorsRenderDifferentMidFrames() {
        val from = filled("from", squareAt(3f, 3f), squareAt(13f, 13f))
        val to = filled("to", squareAt(3f, 3f))
        val fade = renderMorph(from, to, 0.5f, configuration = MorphConfiguration(missingPath = MissingPathBehavior.FADE))
        val keep = renderMorph(from, to, 0.5f, configuration = MorphConfiguration(missingPath = MissingPathBehavior.KEEP))
        val scale = renderMorph(from, to, 0.5f, configuration = MorphConfiguration(missingPath = MissingPathBehavior.SCALE))
        // KEEP paints the orphan at full alpha; FADE at half alpha (same coverage).
        assertTrue(
            "FADE vs KEEP must differ in alpha mid-frame (${meanAlpha(fade)} vs ${meanAlpha(keep)})",
            meanAlpha(fade) < meanAlpha(keep) * 0.85f,
        )
        // SCALE shrinks the orphan away: visibly less coverage.
        assertTrue(
            "FADE vs SCALE must differ in coverage mid-frame (${paintedCount(fade)} vs ${paintedCount(scale)})",
            paintedCount(scale) < paintedCount(fade),
        )
    }

    @Test
    fun drawMissingPathBehaviorPaintsTheFillExactlyOnce() {
        // Regression: the fill of a multi-contour path was painted once PER CONTOUR,
        // so a half-transparent fill darkened with every extra contour.
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", twoSquares(), alpha = 0.5f)

        val frame = renderMorph(
            from, to, 1f,
            configuration = MorphConfiguration(missingPath = MissingPathBehavior.DRAW),
        )
        // Sample the center of one target square: the fill must be the authored 50% alpha,
        // not the double-painted ~75%.
        val scale = framePx / 24f
        val cx = (18f * scale).toInt()
        val cy = (12f * scale).toInt()
        var alphaSum = 0L
        var samples = 0
        for (dy in -4..4) {
            for (dx in -4..4) {
                val a = (frame.getPixel(cx + dx, cy + dy) ushr 24) and 0xFF
                alphaSum += a
                samples++
            }
        }
        val mean = alphaSum / samples.toFloat() / 255f
        assertTrue(
            "fill must be painted once: mean alpha $mean ≈ 0.5 expected (double paint ≈ 0.75)",
            mean in 0.35f..0.62f,
        )
    }

    // ------------------------------------------------------------------ timeline style overrides

    @Test
    fun strokeWidthTrackAppliesToStaticMissingPaths() {
        // Regression: the STROKE_WIDTH timeline override was only honored for MORPH
        // pairs; static sides kept their authored width.
        val from = stroked("from", line(), width = 2f)
        val to = stroked("to", listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 6f)), width = 2f)
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            path("stroke") { strokeWidth(2f, 6f) }
        }
        val thick = paintedCount(renderMorph(from, to, 0.5f, animation = animation))
        val plain = paintedCount(renderMorph(from, to, 0.5f))
        assertTrue(
            "strokeWidth track must widen the stroke mid-morph: $thick vs $plain",
            thick > plain,
        )
    }

    @Test
    fun fillAlphaTrackMultipliesThePathFill() {
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", squareAt(13f, 13f))
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(1f, 0.2f, interval = 0f..1f) }
        }
        val faded = meanAlpha(renderMorph(from, to, 0.5f, animation = animation))
        val plain = meanAlpha(renderMorph(from, to, 0.5f))
        assertTrue(
            "fillAlpha track must dim the fill mid-morph: $faded vs $plain",
            faded < plain * 0.8f,
        )
    }

    // ------------------------------------------------------------------ crossfade fallback

    @Test
    fun scaleCrossfadeAddsAPulseComparedToPlainCrossfade() {
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", squareAt(13f, 13f))
        val plain = renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.CROSSFADE),
        )
        val pulse = renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.SCALE_CROSSFADE),
        )
        val delta = difference(pulse, plain)
        assertTrue(
            "SCALE_CROSSFADE must pulse differently from CROSSFADE at mid-point (delta=$delta)",
            delta > 0.0005f,
        )
    }

    @Test
    fun drawRevealFallbackRevealsTheTargetInsteadOfMorphing() {
        val from = filled("from", squareAt(3f, 3f))
        val to = filled("to", squareAt(13f, 13f))
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
        }
        val drawReveal = renderMorph(
            from, to, 0.3f,
            configuration = MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.DRAW_REVEAL),
            animation = animation,
        )
        val crossfade = renderMorph(
            from, to, 0.3f,
            configuration = MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.CROSSFADE),
        )
        val delta = difference(drawReveal, crossfade)
        assertTrue(
            "DRAW_REVEAL must render differently from CROSSFADE mid-frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    // ------------------------------------------------------------------ reveal helpers

    private fun reveal(
        mode: DrawMode,
        start: PathStart = PathStart.START,
    ): VectorAnimationSpec = VectorAnimationSpec {
        defaultEasing(androidx.compose.animation.core.LinearEasing)
        allPaths {
            drawProgress(from = 0f, to = 1f, interval = 0f..1f, mode = mode, start = start)
        }
    }

    private fun fillReveal(mode: FillMode, direction: DrawDirection): VectorAnimationSpec = VectorAnimationSpec {
        defaultEasing(androidx.compose.animation.core.LinearEasing)
        allPaths { fill(mode = mode, direction = direction, interval = 0f..1f) }
    }
}
