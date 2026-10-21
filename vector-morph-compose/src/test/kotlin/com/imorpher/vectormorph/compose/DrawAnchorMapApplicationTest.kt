package com.imorpher.vectormorph.compose

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.vector.PathNode
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.StartPointStrategy
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
 * Task 5 application probes (start/direction coordination plan rule 6): the `PathStart.CUSTOM`
 * draw anchor must come from [MorphConfiguration.customDrawAnchors] (the new map), falling back
 * to [MorphConfiguration.customStartOffsets] only when the new map has no entry for the path —
 * legacy compatibility pinned — and the legacy map must still drive **morph alignment only**:
 * when both maps are populated, the draw anchor comes from `customDrawAnchors` alone and the
 * alignment map must not move the pen (Review Focus item 4).
 *
 * Pixel probes run through the full [MorphVector] pipeline on the shared render harness
 * (`RenderProbeHarness`). The fixture re-uses Task 3/4's open-contour style: the chevron's
 * authored geometry maps arc fractions onto clean regions — and the shared harness's
 * `stroked()` builder names the path "p" (its `pathName` parameter) so the anchor maps can
 * key on it, mirroring the plan's sketch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawAnchorMapApplicationTest {

    private lateinit var harness: RenderProbeHarness

    /** Minimum painted pixels before a centroid is trusted. */
    private val minPainted = 50

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Open chevron (left foot (4,18) → apex (12,6) → right foot (20,18)) with the stroke path
     * named "p" — the anchor map key. Both legs measure exactly `sqrt(8²+12²)` each, so from
     * and to (an identical chevron shifted +2 in y) keep equal contour lengths and the default
     * BY_PATH_LENGTH timing remap is an identity: draw progress 0.25 reveals a quarter arc.
     *
     * Arc-region map on the interpolated chevron (total arc ≈ 28.84):
     * - anchor 0.00 reveals arc [0.00, 0.25] → the bottom-left foot (4,18)→(8,12);
     * - anchor 0.50 reveals arc [0.50, 0.75] → the apex's right descent (12,6)→(16,12);
     * - anchor 0.75 reveals arc [0.75, 1.00] → the bottom-right foot (16,12)→(20,18).
     * Three disjoint screen regions (centroidX ≈ 0.25 / 0.58 / 0.75), so an anchor claim is
     * falsifiable from where the frame lights up. The contour is OPEN, so
     * `StartPointStrategy.CUSTOM` + `customStartOffsets` performs no geometry rotation in the
     * planner — the alignment map provably cannot move the pixels either way in probe 3.
     */
    private val chevronFrom by lazy {
        harness.stroked(
            "p-vector",
            listOf(PathNode.MoveTo(4f, 18f), PathNode.LineTo(12f, 6f), PathNode.LineTo(20f, 18f)),
            pathName = "p",
        )
    }
    private val chevronTo by lazy {
        harness.stroked(
            "p-vector",
            listOf(PathNode.MoveTo(4f, 20f), PathNode.LineTo(12f, 8f), PathNode.LineTo(20f, 20f)),
            pathName = "p",
        )
    }

    // ------------------------------------------------------------------ probes

    /**
     * Rule 6 (new map): `PathStart.CUSTOM` + `customDrawAnchors["p"]=0.75` sends the pen to arc
     * fraction 0.75 — at t=0.25 the lit pixels occupy the arc's final quarter (bottom-right).
     */
    @Test
    fun newMapAnchorsPen() {
        val frame = harness.renderMorph(
            chevronFrom, chevronTo, progress = 0.25f,
            configuration = MorphConfiguration(customDrawAnchors = mapOf("p" to 0.75f)),
            animation = customStartDrawAnimation(),
        )
        assertLitRegion(frame, "customDrawAnchors=0.75 must paint the bottom-right tail")
    }

    /**
     * Rule 6 (legacy fallback, Review Focus item 4): with the new map blank, a
     * `customStartOffsets` entry still anchors the draw exactly as it always did — pixel for
     * pixel identical to the `customDrawAnchors` probe.
     */
    @Test
    fun legacyMapStillAnchorsDrawsWhenNewMapBlank() {
        val legacy = harness.renderMorph(
            chevronFrom, chevronTo, progress = 0.25f,
            configuration = MorphConfiguration(customStartOffsets = mapOf("p" to 0.75f)),
            animation = customStartDrawAnimation(),
        )
        val modern = harness.renderMorph(
            chevronFrom, chevronTo, progress = 0.25f,
            configuration = MorphConfiguration(customDrawAnchors = mapOf("p" to 0.75f)),
            animation = customStartDrawAnimation(),
        )
        assertLitRegion(legacy, "legacy map must paint the bottom-right tail")
        assertTrue(
            "legacy fallback must anchor identically (legacy=${legacy.litPixelsKey()}, " +
                "modern=${modern.litPixelsKey()})",
            legacy.sameAs(modern),
        )
    }

    /**
     * Rule 6 (no cross-contamination, Review Focus item 4): with BOTH maps populated, the draw
     * anchor must come from `customDrawAnchors` (0 → arc-0, the bottom-left foot), while
     * `customStartOffsets` (0.5) keeps serving morph alignment — if the maps crossed, the pen
     * would light the apex-descent region instead.
     */
    @Test
    fun alignmentMapDoesNotMovePenWhenNewMapPresent() {
        val frame = harness.renderMorph(
            chevronFrom, chevronTo, progress = 0.25f,
            configuration = MorphConfiguration(
                startPoint = StartPointStrategy.CUSTOM,
                customStartOffsets = mapOf("p" to 0.5f),
                customDrawAnchors = mapOf("p" to 0f),
            ),
            animation = customStartDrawAnimation(),
        )
        assertLitArcZero(frame, "customDrawAnchors must out-prioritize customStartOffsets for the pen")
    }

    // ------------------------------------------------------------------ harness

    /** The reveal animation the probes share: FORWARD draw with `PathStart.CUSTOM`. */
    private fun customStartDrawAnimation(): VectorAnimationSpec =
        VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(
                    from = 0f, to = 1f, interval = 0f..1f,
                    mode = DrawMode.FORWARD, start = PathStart.CUSTOM, direction = DrawDirection.AUTO,
                )
            }
        }

    /** Lit pixels must sit in the arc-0.75 region (bottom-right foot) with a painted guard. */
    private fun assertLitRegion(bitmap: android.graphics.Bitmap, message: String) {
        val key = bitmap.litPixelsKey()
        assertTrue("$message (painted ${key.count})", key.count >= minPainted)
        assertTrue(
            "$message: centroid (${key.centroidX}, ${key.centroidY})",
            key.centroidX > 0.62f && key.centroidY > 0.55f,
        )
    }

    /** Lit pixels must sit in the arc-0 region (bottom-left foot) with a painted guard. */
    private fun assertLitArcZero(bitmap: android.graphics.Bitmap, message: String) {
        val key = bitmap.litPixelsKey()
        assertTrue("$message (painted ${key.count})", key.count >= minPainted)
        assertTrue(
            "$message: centroid (${key.centroidX}, ${key.centroidY})",
            key.centroidX < 0.4f && key.centroidY > 0.5f,
        )
    }
}
