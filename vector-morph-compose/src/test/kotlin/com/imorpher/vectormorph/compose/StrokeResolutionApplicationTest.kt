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
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.plan.MorphPlanner
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import com.imorpher.vectormorph.compose.renderer.MorphRenderer
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
 * Task 3 application probes (start/direction coordination plan): the stroke reveal must
 * anchor through [com.imorpher.vectormorph.core.reveal.DirectionMath.resolve] — an explicit
 * [PathStart] wins over a contradictory direction (rule 1), `AUTO` keeps the top-left anchor
 * (rule 2), anchor-honoring is dropped by `DrawMode.OUTSIDE_IN` with a warning (rule 1/4).
 *
 * Pixel probes run through the full [MorphVector] pipeline on the shared render harness; the
 * OUTSIDE_IN probe additionally renders through a locally owned [MorphRenderer] so the
 * per-frame warnings collector is directly observable (the composition-private renderer used
 * by [MorphVector] cannot be reached from a test).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StrokeResolutionApplicationTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Open shoulder polyline: right-shoulder (21,3) → elbow (14,3) → down-left to (3,20).
     * The bounding box's top-right corner lands EXACTLY on the authored arc-0 vertex, so an
     * explicit [PathStart.TOP_RIGHT] anchor resolves to literal arc 0 unambiguously (d=0
     * sample) and the 15% reveal hugs the top-right stretch; an AUTO (top-left) anchor provably
     * reveals elsewhere (see the probes' measured separation).
     */
    private val shoulderFrom by lazy {
        harness.stroked("from", listOf(PathNode.MoveTo(21f, 3f), PathNode.LineTo(14f, 3f), PathNode.LineTo(3f, 20f)))
    }
    private val shoulderTo by lazy {
        harness.stroked("to", listOf(PathNode.MoveTo(21f, 4f), PathNode.LineTo(14f, 4f), PathNode.LineTo(3f, 21f)))
    }

    /**
     * Open chevron (a biped: left foot (4,18) → apex (12,6) → right foot (20,18)) for the
     * OUTSIDE_IN probe, where anchor identity must NOT matter.
     */
    private val chevronFrom by lazy {
        harness.stroked("from", listOf(PathNode.MoveTo(4f, 18f), PathNode.LineTo(12f, 6f), PathNode.LineTo(20f, 18f)))
    }
    private val chevronTo by lazy {
        harness.stroked("to", listOf(PathNode.MoveTo(4f, 20f), PathNode.LineTo(12f, 8f), PathNode.LineTo(20f, 20f)))
    }

    /**
     * Open diagonal authored bottom-right → top-left: arc 0 sits at (21, 21) while the
     * bounding box's top-left corner lies exactly ON an interior vertex (3, 3), so an AUTO
     * (top-left) anchor must light the top-left half at low reveal progress.
     */
    private val diagonalFrom by lazy {
        harness.stroked("from", listOf(PathNode.MoveTo(21f, 21f), PathNode.LineTo(3f, 3f), PathNode.LineTo(12f, 8f)))
    }
    private val diagonalTo by lazy {
        harness.stroked("to", listOf(PathNode.MoveTo(21f, 19f), PathNode.LineTo(4f, 2f), PathNode.LineTo(13f, 9f)))
    }

    // ------------------------------------------------------------------ probes

    /** Rule 1: an explicit start anchors its own region whether the direction agrees or not. */
    @Test
    fun explicitStartWinsOverContradictoryDirection() {
        val agree = revealed(
            shoulderFrom, shoulderTo, t = 0.15f,
            start = PathStart.TOP_RIGHT, direction = DrawDirection.TOP_TO_BOTTOM,
        )
        val contradict = revealed(
            shoulderFrom, shoulderTo, t = 0.15f,
            start = PathStart.TOP_RIGHT, direction = DrawDirection.BOTTOM_TO_TOP,
        )
        val key = agree.litPixelsKey()
        assertTrue("TOP_RIGHT anchor must paint pixels at t=0.15", key.count > 0)
        assertTrue(
            "explicit TOP_RIGHT must light the right side: centroidX=${key.centroidX}",
            key.centroidX > 0.65f,
        )
        // Anchor identical → pixels identical, direction "agreement" irrelevant.
        assertEquals(
            "both directions resolve to the same anchor",
            contradict.litPixelsKey().centroidX,
            key.centroidX,
            0.01f,
        )
        assertTrue(
            "the two renders must be pixel-identical",
            agree.sameAs(contradict),
        )
    }

    /** Rule 2: `AUTO` start + `AUTO` direction keeps the top-left anchor mid-reveal. */
    @Test
    fun autoStillAnchorsTopLeftForDefaultDirection() {
        val frame = revealed(
            diagonalFrom, diagonalTo, t = 0.15f,
            start = PathStart.AUTO, direction = DrawDirection.AUTO,
        )
        val key = frame.litPixelsKey()
        assertTrue("default reveal must paint pixels at t=0.15", key.count > 0)
        assertTrue(
            "AUTO must anchor the top-left region: centroid (${key.centroidX}, ${key.centroidY})",
            key.centroidX < 0.5f && key.centroidY < 0.5f,
        )
    }

    /** Rule 1/4 review focus: OUTSIDE_IN ignores the anchor and warns about it. */
    @Test
    fun outsideInIgnoresStartWithSamePixels() {
        // Rendered through a locally owned MorphRenderer (see renderViaMorphRenderer) so the
        // per-frame warnings collector is observable; pixels still come from the real render.
        val center = renderViaMorphRenderer(
            chevronFrom, chevronTo, t = 0.3f,
            animation = revealAnimation(PathStart.CENTER, DrawDirection.AUTO, DrawMode.OUTSIDE_IN),
        )
        val bottomLeft = renderViaMorphRenderer(
            chevronFrom, chevronTo, t = 0.3f,
            animation = revealAnimation(PathStart.BOTTOM_LEFT, DrawDirection.AUTO, DrawMode.OUTSIDE_IN),
        )
        assertTrue(
            "OUTSIDE_IN must ignore the anchor: both starts render identical pixels",
            center.sameAs(bottomLeft),
        )
        assertTrue(
            "CENTER start must deliver ANCHOR_IGNORED_BY_MODE",
            DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE in center.warnings,
        )
        assertTrue(
            "BOTTOM_LEFT start must deliver ANCHOR_IGNORED_BY_MODE",
            DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE in bottomLeft.warnings,
        )
    }

    // ------------------------------------------------------------------ harness

    /** One [MorphVector] frame + its lit-pixel fingerprint. */
    private data class ProbeFrame(
        private val bitmap: Bitmap,
        /** Draw-resolution warnings observed while rendering (direct route only). */
        val warnings: List<DrawResolutionWarning> = emptyList(),
    ) {
        fun litPixelsKey(): LitPixelsKey = bitmap.litPixelsKey()

        fun litCentroidX(): Float = bitmap.litCentroidX()

        fun sameAs(other: ProbeFrame): Boolean = bitmap.sameAs(other.bitmap)
    }

    private fun revealed(
        from: androidx.compose.ui.graphics.vector.ImageVector,
        to: androidx.compose.ui.graphics.vector.ImageVector,
        t: Float,
        start: PathStart,
        direction: DrawDirection,
        mode: DrawMode = DrawMode.FORWARD,
    ): ProbeFrame = ProbeFrame(
        harness.renderMorph(from, to, t, animation = revealAnimation(start, direction, mode)),
    )

    private fun revealAnimation(start: PathStart, direction: DrawDirection, mode: DrawMode): VectorAnimationSpec =
        VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(
                    from = 0f, to = 1f, interval = 0f..1f,
                    mode = mode, start = start, direction = direction,
                )
            }
        }

    /**
     * Rule-1/4 pin through a locally owned [MorphRenderer] rendered onto a canvas, so the
     * per-frame warnings collector is readable: identical pixels must carry the warning that
     * the anchor was ignored by the mode.
     */
    private fun renderViaMorphRenderer(
        from: androidx.compose.ui.graphics.vector.ImageVector,
        to: androidx.compose.ui.graphics.vector.ImageVector,
        t: Float,
        animation: VectorAnimationSpec,
    ): ProbeFrame {
        val config = MorphConfiguration(animation = animation)
        val preparedFrom = VectorCompiler.compile(from)
        val preparedTo = VectorCompiler.compile(to)
        val plan = MorphPlanner.plan(preparedFrom, preparedTo, config.copy(animation = null))
        val frame = EvaluatedFrame(plan)
        TimelineEvaluator(
            animation = animation,
            plan = plan,
            colorSpace = config.colorSpace,
            staggerDelay = if (config.staggerDelay > 0f) config.staggerDelay else animation.staggerDelay,
            staggerOrder = if (config.staggerDelay > 0f) config.staggerOrder else animation.staggerOrder,
            customDrawOrder = config.customDrawOrder,
            timingMode = config.timing,
        ).evaluate(t, frame)
        val bitmap = Bitmap.createBitmap(harness.framePx, harness.framePx, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(bitmap)
        val renderer = MorphRenderer()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(androidCanvas), Size(harness.framePx.toFloat(), harness.framePx.toFloat())) {
            renderer.render(this, plan, frame, t, config)
        }
        return ProbeFrame(bitmap, renderer.frameDrawWarnings())
    }
}
