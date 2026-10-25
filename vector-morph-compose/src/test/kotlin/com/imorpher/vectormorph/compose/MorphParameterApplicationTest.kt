package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.vector.PathNode
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    // ------------------------------------------------------------------ fixtures

    /** Two disjoint squares as ONE multi-contour path: the double-fill regression shape. */
    private fun twoSquares() = harness.squareAt(3f, 9f, 6f) + harness.squareAt(15f, 9f, 6f)

    /** Diagonal pair that swaps corners between the morph endpoints. */
    private val swapFrom by lazy { listOf(harness.squareAt(3f, 3f), harness.squareAt(13f, 13f)) }
    private val swapTo by lazy { listOf(harness.squareAt(13f, 3f), harness.squareAt(3f, 13f)) }

    // ------------------------------------------------------------------ rendering

    private fun meanAlpha(bitmap: Bitmap): Float = BitmapAsserts.meanAlpha(bitmap)
    private fun paintedCount(bitmap: Bitmap): Int = BitmapAsserts.paintedCount(bitmap)
    private fun difference(a: Bitmap, b: Bitmap): Float = BitmapAsserts.meanAbsDifference(a, b)

    // ------------------------------------------------------------------ stroke ↔ fill

    @Test
    fun strokeFillMorphShrinksTheStrokeMidMorph() {
        // Same geometry both sides so the whole delta is paint choreography: explicit
        // MORPH shrinks the 6u stroke with the morph and sweeps the fill behind it;
        // CROSSFADE holds the full stroke and crossfades the legs. Today MORPH silently
        // behaves like CROSSFADE, so the frames measure exactly 0 difference.
        val square = listOf(
            PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
            PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
        )
        val from = harness.filledAndStroked("from", square, strokeWidth = 6f)
        val to = harness.filledAndStroked("to", square, strokeWidth = 6f)
        val shrink = harness.renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.MORPH),
        )
        val crossfade = harness.renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.CROSSFADE),
        )
        val delta = difference(shrink, crossfade)
        assertTrue(
            "explicit MORPH must shrink the stroke while the fill grows (stale would be exactly 0; delta=$delta)",
            delta > 0.01f,
        )
    }

    // ------------------------------------------------------------------ reveal parameters

    @Test
    fun forwardAndReverseDrawModesRenderDifferentMidFrames() {
        // The draw mode shapes the STROKE pass, so these paths must be stroked.
        val from = harness.stroked("from", harness.line(), width = 2f)
        val to = harness.stroked("to", listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 6f)), width = 2f)
        val forward = harness.renderMorph(from, to, 0.4f, animation = reveal(mode = DrawMode.FORWARD))
        val reverse = harness.renderMorph(from, to, 0.4f, animation = reveal(mode = DrawMode.REVERSE))
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
        val from = harness.stroked("from", listOf(PathNode.MoveTo(12f, 4f), PathNode.LineTo(12f, 20f)), width = 2f)
        val to = harness.stroked("to", listOf(PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 20f)), width = 2f)
        val top = harness.renderMorph(
            from, to, 0.4f,
            animation = reveal(mode = DrawMode.FORWARD, start = PathStart.TOP),
        )
        val bottom = harness.renderMorph(
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
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))
        val leftToRight = harness.renderMorph(
            from, to, 0.5f,
            animation = fillReveal(mode = FillMode.DIRECTIONAL, direction = DrawDirection.LEFT_TO_RIGHT),
        )
        val rightToLeft = harness.renderMorph(
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
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))
        val radial = harness.renderMorph(
            from, to, 0.4f,
            animation = fillReveal(mode = FillMode.RADIAL, direction = DrawDirection.AUTO),
        )
        val linear = harness.renderMorph(
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
    fun linearAndDirectionalFillModesRenderDifferentMidFrames() {
        // Two big matching squares, shape-to-shape, so the whole delta is fill choreography:
        // LINEAR wipes hard at the edge, DIRECTIONAL feathers across a 0.15*axis band.
        // Today both modes share one hard clip, so the frames measure exactly 0.
        val from = harness.filled(
            "grid",
            harness.squareAt(1f, 1f, 10f),
            harness.squareAt(13f, 1f, 10f),
        )
        val linear = harness.renderMorph(
            from, from, 0.5f,
            animation = fillReveal(mode = FillMode.LINEAR, direction = DrawDirection.LEFT_TO_RIGHT),
        )
        val directional = harness.renderMorph(
            from, from, 0.5f,
            animation = fillReveal(mode = FillMode.DIRECTIONAL, direction = DrawDirection.LEFT_TO_RIGHT),
        )
        val delta = difference(linear, directional)
        assertTrue(
            "LINEAR must be a hard wipe vs DIRECTIONAL's feather (stale would be exactly 0; delta=$delta)",
            delta > 0.005f,
        )
    }

    @Test
    fun sweepOnPairFillsHonorsDirectionStartAngle() {
        // Square→square pair fill under a SWEEP reveal: the ClipPass arm historically
        // hard-coded the -90° top start, so every clockwise direction rendered the same
        // wedge at the same progress. The anchor-derived start angle must separate them.
        val from = harness.filled(
            "grid",
            harness.squareAt(1f, 1f, 10f),
            harness.squareAt(13f, 1f, 10f),
        )
        val left = harness.renderMorph(
            from, from, 0.5f,
            animation = fillReveal(mode = FillMode.SWEEP, direction = DrawDirection.LEFT_TO_RIGHT),
        )
        val top = harness.renderMorph(
            from, from, 0.5f,
            animation = fillReveal(mode = FillMode.SWEEP, direction = DrawDirection.TOP_TO_BOTTOM),
        )
        val delta = difference(left, top)
        assertTrue(
            "SWEEP's start angle must follow the direction anchor (stale would be exactly 0; delta=$delta)",
            delta > 0.01f,
        )
    }

    @Test
    fun fadeFillModeActuallyFadesUnderAReveal() {
        // Regression: inside a morph pair's fill legs, FillMode.FADE ignored the reveal
        // progress and popped the fill in at full alpha.
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))
        val atQuarter = meanAlpha(
            harness.renderMorph(from, to, 0.25f, animation = fillReveal(mode = FillMode.FADE, direction = DrawDirection.AUTO)),
        )
        val atFull = meanAlpha(
            harness.renderMorph(from, to, 1f, animation = fillReveal(mode = FillMode.FADE, direction = DrawDirection.AUTO)),
        )
        assertTrue(
            "FADE fill must grow with progress: p=0.25 alpha $atQuarter vs p=1 alpha $atFull",
            atQuarter < atFull * 0.75f,
        )
    }

    // ------------------------------------------------------------------ timing / stagger

    /**
     * Evaluator rebuild contract: `timing` and `customDrawOrder` are consumed by
     * `TimelineEvaluator` at construction, so a recomposition that changes only those
     * fields (same PlanningKey → same plan instance) must rebuild the evaluator, not
     * reuse the stale one. Guard for the planning-key split: a stale evaluator would
     * replay the stale draws, making the frame delta exactly 0.
     *
     * Signal calibration (UNIFORM vs BY_PATH at p=0.5, `fillAlpha(0f,1f)`): BY_PATH
     * clips rank 0 to 1f (fully lit) and rank 1 to 0f (fully dark) while UNIFORM holds
     * both at 0.5 — measured deltas run ~0.04 of mean-abs pixel difference, far above
     * the 0.01 floor, and identical draws measure exactly 0 (Robolectric is
     * deterministic). `staggerDelay=0.5f` + mirrored CUSTOM orders analogously light
     * opposite squares (~0.05).
     */
    @Test
    fun recomposingTimingAndCustomDrawOrderRebuildsTheEvaluator() {
        val from = harness.filled("from", swapFrom[0], swapFrom[1])
        val to = harness.filled("to", swapTo[0], swapTo[1])
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(0f, 1f, interval = 0f..1f) }
        }
        val probe = harness.openRecompositionProbe(
            from, to, 0.5f,
            initialConfiguration = MorphConfiguration(timing = TimingMode.UNIFORM, animation = animation),
        )
        val uniform = probe.capture()

        // Timing-only recomposition (planning key unchanged): path 0 saturates, path 1 vanishes.
        probe.update(MorphConfiguration(timing = TimingMode.BY_PATH, animation = animation))
        val byPath = probe.capture()
        val timingDelta = difference(uniform, byPath)
        assertTrue(
            "timing-only recomposition must rebuild the evaluator (stale would be exactly 0; delta=$timingDelta)",
            timingDelta > 0.01f,
        )

        // customDrawOrder-only recomposition with CUSTOM stagger: with staggerDelay 0.5,
        // rank 0 lights fully and rank 1 stays dark — flipping the order mirrors the frame.
        val bFirst = MorphConfiguration(
            timing = TimingMode.UNIFORM,
            staggerDelay = 0.5f,
            staggerOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.CUSTOM,
            customDrawOrder = listOf("path-1", "path-0"),
            animation = animation,
        )
        val aFirst = bFirst.copy(customDrawOrder = listOf("path-0", "path-1"))
        probe.update(bFirst)
        val bFrame = probe.capture()
        val orderDelta = difference(byPath, bFrame)
        assertTrue(
            "customDrawOrder mid-timeline must change the stagger (stale would be exactly 0; delta=$orderDelta)",
            orderDelta > 0.01f,
        )
        probe.update(aFirst)
        val aFrame = probe.capture()
        val flipDelta = difference(bFrame, aFrame)
        assertTrue(
            "flipping customDrawOrder must mirror the reveal order (stale would be exactly 0; delta=$flipDelta)",
            flipDelta > 0.01f,
        )
    }

    @Test
    fun configStaggerOrderOverridesTimelineOrderWithoutConfigDelay() {
        // A plausible trap (documented): identical from/to geometry isolates the stagger
        // signal — planning degenerates to identity morphs, so any frame difference is
        // pure timeline. Names a/c/b are authored along the BR→TL diagonal, so the
        // timeline's ranks (b→c→a) differ from BY_NAME's (a→b→c).
        val from = harness.filled(
            "grid",
            harness.squareAt(1f, 1f, 6f),
            harness.squareAt(8f, 8f, 6f),
            harness.squareAt(15f, 15f, 6f),
            pathNames = listOf("a", "c", "b"),
        )
        val timeline = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            stagger(0.2f, com.imorpher.vectormorph.core.model.DrawOrderStrategy.BOTTOM_RIGHT_TO_TOP_LEFT)
            allPaths { fillAlpha(0f, 1f, interval = 0f..1f) }
        }
        val byName = harness.renderMorph(
            from, from, 0.5f,
            configuration = MorphConfiguration(staggerOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.BY_NAME),
            animation = timeline,
        )
        val defaulted = harness.renderMorph(
            from, from, 0.5f,
            configuration = MorphConfiguration.Default,
            animation = timeline,
        )
        val delta = difference(byName, defaulted)
        assertTrue(
            "config staggerOrder must win when the config supplies no delay (stale would be exactly 0; delta=$delta)",
            delta > 0.005f,
        )
    }

    @Test
    fun byPathTimingSequencesWholePathsMidMorph() {
        // TimingMode schedules the reveal tracks: with BY_PATH the second path's alpha
        // ramp only starts after the first finishes; UNIFORM runs both at once.
        val from = harness.filled("from", swapFrom[0], swapFrom[1])
        val to = harness.filled("to", swapTo[0], swapTo[1])
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(0.1f, 1f, interval = 0f..1f) }
        }
        val byPath = harness.renderMorph(from, to, 0.5f, configuration = MorphConfiguration(timing = TimingMode.BY_PATH, animation = animation))
        val uniform = harness.renderMorph(from, to, 0.5f, configuration = MorphConfiguration(timing = TimingMode.UNIFORM, animation = animation))
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
        val from = harness.filled("from", swapFrom[0], swapFrom[1])
        val to = harness.filled("to", swapTo[0], swapTo[1])
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(0.1f, 1f, interval = 0f..1f) }
        }
        val staggered = harness.renderMorph(from, to, 0.35f, configuration = MorphConfiguration(staggerDelay = 0.4f, animation = animation))
        val plain = harness.renderMorph(from, to, 0.35f, configuration = MorphConfiguration(animation = animation))
        val delta = difference(staggered, plain)
        assertTrue(
            "staggerDelay must change the mid-morph frame (delta=$delta)",
            delta > 0.01f,
        )
    }

    // ------------------------------------------------------------------ missing paths

    @Test
    fun missingPathBehaviorsRenderDifferentMidFrames() {
        val from = harness.filled("from", harness.squareAt(3f, 3f), harness.squareAt(13f, 13f))
        val to = harness.filled("to", harness.squareAt(3f, 3f))
        val fade = harness.renderMorph(from, to, 0.5f, configuration = MorphConfiguration(missingPath = MissingPathBehavior.FADE))
        val keep = harness.renderMorph(from, to, 0.5f, configuration = MorphConfiguration(missingPath = MissingPathBehavior.KEEP))
        val scale = harness.renderMorph(from, to, 0.5f, configuration = MorphConfiguration(missingPath = MissingPathBehavior.SCALE))
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
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", twoSquares(), alpha = 0.5f)

        val frame = harness.renderMorph(
            from, to, 1f,
            configuration = MorphConfiguration(missingPath = MissingPathBehavior.DRAW),
        )
        // Sample the center of one target square: the fill must be the authored 50% alpha,
        // not the double-painted ~75%.
        val scale = harness.framePx / 24f
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
        val from = harness.stroked("from", harness.line(), width = 2f)
        val to = harness.stroked("to", listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 6f)), width = 2f)
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            path("stroke") { strokeWidth(2f, 6f) }
        }
        val thick = paintedCount(harness.renderMorph(from, to, 0.5f, animation = animation))
        val plain = paintedCount(harness.renderMorph(from, to, 0.5f))
        assertTrue(
            "strokeWidth track must widen the stroke mid-morph: $thick vs $plain",
            thick > plain,
        )
    }

    @Test
    fun fillAlphaTrackMultipliesThePathFill() {
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(1f, 0.2f, interval = 0f..1f) }
        }
        val faded = meanAlpha(harness.renderMorph(from, to, 0.5f, animation = animation))
        val plain = meanAlpha(harness.renderMorph(from, to, 0.5f))
        assertTrue(
            "fillAlpha track must dim the fill mid-morph: $faded vs $plain",
            faded < plain * 0.8f,
        )
    }

    // ------------------------------------------------------------------ crossfade fallback

    @Test
    fun scaleCrossfadeAddsAPulseComparedToPlainCrossfade() {
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))
        val plain = harness.renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.CROSSFADE),
        )
        val pulse = harness.renderMorph(
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
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
        }
        val drawReveal = harness.renderMorph(
            from, to, 0.3f,
            configuration = MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.DRAW_REVEAL),
            animation = animation,
        )
        val crossfade = harness.renderMorph(
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
