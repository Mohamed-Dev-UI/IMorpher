package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.imorpher.vectormorph.compose.renderer.MorphRenderer
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MorphDebugOptions
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.plan.MorphPlanner
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task 7 probes (start/direction coordination plan rule 7): the debug overlay must
 * visualize the **resolved** draw anchor — the exact point the reveal's
 * `DirectionMath.resolve` → `resolveStartFraction` selection produced — plus a short
 * arrow along the configured direction's travel axis, and a frame whose draw resolution
 * produced warnings must show an indicator. Frames rendered with `debug = null` must stay
 * byte-identical to the pre-overlay pixels, even when warnings were collected but no
 * debug toggle is on.
 *
 * Shared fixture: the harness's open horizontal line (`RenderProbeHarness.line()`, already
 * anchoring the suites of `MorphParameterApplicationTest`), morphing 1 viewport unit down —
 * from (3,12)→(21,12) to (3,13)→(21,13), stroked probe-blue with the harness's 2-unit width.
 *
 * Probe calibration (the pipeline maps the 24-unit viewport onto the 96px frame at exactly
 * 4 px/unit — `scale = framePx / 24f` exactly as in `MorphLineRenderingTest`):
 *  1. At progress 0.25 the interpolated line sits at y = 12.25 and a FORWARD reveal paints
 *     arc [0, 0.25] ⇒ screen pixels x ∈ [12, 30], y ∈ [45, 53] (plus caps). A reveal
 *     anchored **at arc 1** paints only that wrap-leg window, so the arc-1 anchor point
 *     itself stays UNPAINTED by the stroke and any overlay draw there lands on
 *     transparency — that is what makes the anchor rect falsifiable at all (an anchor on
 *     the painted arc would only repaint opaque stroke pixels).
 *  2. `DrawDirection.RIGHT_TO_LEFT` with `PathStart.AUTO` resolves its anchor (plan rule 2)
 *     to the arc-1 endpoint (21, 12.25) ⇒ screen (84, 49). The delta with the debug render
 *     is probed inside (78..90, 42..56) and must contain probe-YELLOW pixels — the resolved
 *     anchor dot's `Color.Yellow`. The only other yellow in these frames comes from the
 *     pre-existing authored-start markers at arc 0 (screen (12, 48)/(12, 52), 72px away);
 *     the blue stroke (0xFF12A4D9), the old magenta axis line and the new magenta arrow
 *     all fail the yellow filter (blue channel ≥ 0xC0 or green ≤ 0x40).
 *  3. The warning indicator draws in the fixed top-left viewport corner — a red triangle
 *     plus warning-count digits, occupying roughly screen (1..12) × (2..8). The probed
 *     corner rect (0..24, 0..14) is empty in every fixture scene (the stroke band lives at
 *     y ≥ 45 and every marker sits next to it), so probe-RED pixels (r ≥ 0xC0, g,b ≤ 0x40)
 *     in that rect can only come from the indicator.
 *
 * The conflict probe compares a warned scene (explicit `TOP_RIGHT` start vs the
 * `LEFT_TO_RIGHT` axis, an open contour — recorded as `START_OVERRIDES_DIRECTION`) against
 * a no-warn scene with the SAME toggles (`LEFT` start matches that axis). Both scenes paint
 * mirrored anchors/arrows, so frame-wide totals are not the signal; the calibrated extra
 * pixels live inside the corner rect only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawDebugOverlayTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    // ------------------------------------------------------------------ fixtures

    /** Open line authored left→right, morphing 1 viewport unit down (see class doc). */
    private val lineFrom by lazy { harness.stroked("from", harness.line()) }
    private val lineTo by lazy {
        harness.stroked("to", listOf(PathNode.MoveTo(3f, 13f), PathNode.LineTo(21f, 13f)))
    }

    /** Both resolved-draw toggles on (the overlay additions must follow exactly these). */
    private val allToggles = MorphDebugOptions(showStartPoints = true, showDirection = true)

    /** Screen rect around the resolved anchor (84, 49) — yellow-delta probe (class doc 2). */
    private val anchorRectX = 78..90
    private val anchorRectY = 42..56

    /** Corner rect the warning banner claims — red probe, empty otherwise (class doc 3). */
    private val cornerRectX = 0..24
    private val cornerRectY = 0..14

    /** The AUTO-start reveal animation the probes reuse over 0..1. */
    private fun reveal(
        start: PathStart = PathStart.AUTO,
        direction: DrawDirection,
        mode: DrawMode = DrawMode.FORWARD,
    ): VectorAnimationSpec = VectorAnimationSpec {
        defaultEasing(LinearEasing)
        allPaths {
            drawProgress(
                from = 0f, to = 1f, interval = 0f..1f,
                mode = mode, start = start, direction = direction,
            )
        }
    }

    /** One composed [MorphVector] frame of the line fixture with optional debug options. */
    private fun renderLineFrame(
        animation: VectorAnimationSpec,
        debug: MorphDebugOptions?,
    ): Bitmap = harness.renderMorph(
        lineFrom, lineTo, progress = 0.25f,
        configuration = MorphConfiguration(debug = debug),
        animation = animation,
    )

    // ------------------------------------------------------------------ probes

    /**
     * Toggles on ⇒ strictly more painted pixels, and the delta intersects the calibrated
     * neighborhood of the resolved anchor with the dot's yellow (class doc 2). The scene
     * warns (RIGHT_TO_LEFT anchors RIGHT via AUTO ⇒ no warnings), so no indicator pixels
     * can leak into the anchor rect either.
     */
    @Test
    fun resolvedAnchorDotPaintsAtTheResolvedAnchorWhenEnabled() {
        val animation = reveal(direction = DrawDirection.RIGHT_TO_LEFT)
        val disabled = renderLineFrame(animation, debug = null)
        val enabled = renderLineFrame(animation, debug = allToggles)

        val disabledKey = disabled.litPixelsKey()
        val enabledKey = enabled.litPixelsKey()
        assertTrue("calibration guard: the plain reveal must paint", disabledKey.count > 0)
        assertTrue(
            "debug overlay must ADD painted pixels (disabled=${disabledKey.count}, enabled=${enabledKey.count})",
            enabledKey.count > disabledKey.count,
        )

        val anchorPixels = countChangedPixels(disabled, enabled, anchorRectX, anchorRectY) { isProbeYellow(it) }
        assertTrue("the resolved anchor rect must gain yellow dot pixels (count=$anchorPixels)", anchorPixels >= 3)
    }

    /**
     * `debug = null` is byte-identical across renders of the same scene — both for the
     * plain scene and for a scene whose resolution produces warnings (conflicting combo):
     * collected warnings are data, never pixels, while no debug options are on.
     */
    @Test
    fun disabledOverlayStaysByteIdenticalEvenWithWarnings() {
        val animation = reveal(direction = DrawDirection.RIGHT_TO_LEFT)
        val plain = renderLineFrame(animation, debug = null)
        assertTrue("calibration guard: the plain scene must paint", plain.litPixelsKey().count > 0)
        assertTrue(
            "debug=null must not alter pixels across renders",
            plain.sameAs(renderLineFrame(animation, debug = null)),
        )

        val conflict = reveal(start = PathStart.TOP_RIGHT, direction = DrawDirection.LEFT_TO_RIGHT)
        val warned = renderLineFrame(conflict, debug = null)
        assertTrue("calibration guard: the warned scene must paint", warned.litPixelsKey().count > 0)
        assertTrue(
            "warnings alone must stay invisible with debug disabled",
            warned.sameAs(renderLineFrame(conflict, debug = null)),
        )
        assertTrue(
            "warnings must not even color-change pixels (same scene, same anchor)",
            plain.sameAs(warned),
        )
    }

    /**
     * Conflicting combo (explicit `TOP_RIGHT` start on an open contour drawn
     * `LEFT_TO_RIGHT`) with both toggles on ⇒ the warning indicator paints in the corner
     * rect; the no-conflict control scene (`LEFT` start agrees with the axis, same toggles,
     * same anchor window) keeps that rect clean. The direct-render route additionally pins
     * that the painted banner matches the warnings the [MorphRenderer.frameDrawWarnings]
     * seam reports for the very same frame.
     */
    @Test
    fun conflictComboPaintsWarningIndicatorAtTheCorner() {
        val conflict = renderLineFrame(
            reveal(start = PathStart.TOP_RIGHT, direction = DrawDirection.LEFT_TO_RIGHT),
            debug = allToggles,
        )
        val control = renderLineFrame(
            reveal(start = PathStart.LEFT, direction = DrawDirection.LEFT_TO_RIGHT),
            debug = allToggles,
        )
        assertTrue("calibration guard: the conflict scene must paint", conflict.litPixelsKey().count > 0)
        assertTrue(
            "the no-conflict control must leave the corner empty",
            countProbeRed(control, cornerRectX, cornerRectY) == 0,
        )
        val bannerPixels = countProbeRed(conflict, cornerRectX, cornerRectY)
        assertTrue("the conflict scene must paint the warning indicator (count=$bannerPixels)", bannerPixels >= 3)

        // Triangulation: the same scene through the direct renderer route reports the
        // warning through the seam AND paints the banner.
        val direct = renderDirect(
            animation = reveal(start = PathStart.TOP_RIGHT, direction = DrawDirection.LEFT_TO_RIGHT),
            debug = allToggles,
        )
        assertTrue(
            "the conflicting combo must deliver START_OVERRIDES_DIRECTION",
            DrawResolutionWarning.START_OVERRIDES_DIRECTION in direct.warnings,
        )
        assertTrue(
            "the direct render must paint the banner for its warnings (count=" +
                countProbeRed(direct.bitmap, cornerRectX, cornerRectY) + ")",
            countProbeRed(direct.bitmap, cornerRectX, cornerRectY) >= 3,
        )
    }

    // ------------------------------------------------------------------ direct-render route

    /** One locally-owned-[MorphRenderer] frame plus its frame warnings. */
    private data class DirectFrame(
        val bitmap: Bitmap,
        val warnings: List<DrawResolutionWarning>,
    )

    /**
     * Renders as the composition does (plan → [TimelineEvaluator] → [MorphRenderer], see
     * `StrokeResolutionApplicationTest.renderViaMorphRenderer`) so [MorphRenderer.frameDrawWarnings]
     * is readable alongside the pixels; [debug] rides in the configuration.
     */
    private fun renderDirect(
        animation: VectorAnimationSpec,
        debug: MorphDebugOptions?,
    ): DirectFrame {
        val config = MorphConfiguration(animation = animation, debug = debug)
        val plan = MorphPlanner.plan(
            VectorCompiler.compile(lineFrom),
            VectorCompiler.compile(lineTo),
            config.copy(animation = null),
        )
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(
            animation = animation,
            plan = plan,
            colorSpace = config.colorSpace,
            staggerDelay = if (config.staggerDelay > 0f) config.staggerDelay else animation.staggerDelay,
            staggerOrder = if (config.staggerDelay > 0f) config.staggerOrder else animation.staggerOrder,
            customDrawOrder = config.customDrawOrder,
            timingMode = config.timing,
        ).evaluate(0.25f, frame)
        val bitmap = Bitmap.createBitmap(harness.framePx, harness.framePx, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(bitmap)
        val renderer = MorphRenderer()
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(androidCanvas),
            Size(harness.framePx.toFloat(), harness.framePx.toFloat()),
        ) {
            renderer.render(this, plan, frame, 0.25f, config)
        }
        return DirectFrame(bitmap, renderer.frameDrawWarnings())
    }

    // ------------------------------------------------------------------ pixel probes

    /** Pixels inside the rect that changed between the frames and whose NEW color matches. */
    private fun countChangedPixels(
        before: Bitmap,
        after: Bitmap,
        xs: IntRange,
        ys: IntRange,
        match: (Int) -> Boolean,
    ): Int {
        var count = 0
        for (y in ys) for (x in xs) {
            if (before.getPixel(x, y) != after.getPixel(x, y) && match(after.getPixel(x, y))) count++
        }
        return count
    }

    /** Opaque-enough probe-blue-independent yellow (the anchor dot's core color). */
    private fun isProbeYellow(argb: Int): Boolean {
        if (argb ushr 24 < 0x40) return false
        return (argb ushr 16 and 0xFF) >= 0xC0 &&
            (argb ushr 8 and 0xFF) >= 0xC0 &&
            (argb and 0xFF) <= 0x40
    }

    /** Opaque-enough red (banner triangle + digits color). */
    private fun isProbeRed(argb: Int): Boolean {
        if (argb ushr 24 < 0x40) return false
        return (argb ushr 16 and 0xFF) >= 0xC0 &&
            (argb ushr 8 and 0xFF) <= 0x40 &&
            (argb and 0xFF) <= 0x40
    }

    /** Probe-red pixels inside the rect of [bitmap]. */
    private fun countProbeRed(bitmap: Bitmap, xs: IntRange, ys: IntRange): Int {
        var count = 0
        for (y in ys) for (x in xs) if (isProbeRed(bitmap.getPixel(x, y))) count++
        return count
    }
}
