package com.imorpher.vectormorph.compose

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.vector.PathNode
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task 4 application probes (start/direction coordination plan rule 4):
 * [MorphConfiguration.directionTraversal] must let a `DrawMode.FORWARD` stroke reveal on an
 * open contour honor its draw direction's axis. Under `LEGACY` (default) the pen follows the
 * contour's own point order; under `HONOR_DIRECTION` a contour leaving the resolved anchor
 * against the requested axis reveals backwards from the same anchor instead. Closed contours
 * keep their winding reversal untouched — `CLOCKWISE`/`COUNTER_CLOCKWISE` must render
 * identically under both policies (Review Focus item 2).
 *
 * Pixel probes run through the full [MorphVector] pipeline on the shared render harness.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TraversalPolicyApplicationTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Open hook polyline: (21,2) → elbow (6,6) → leftward dive to the leftmost vertex (2,20)
     * → base (21,21). The LEFT-edge anchor (nearest sample to the bounds' left edge midpoint)
     * lands on the elbow→leftmost stretch, so the arc leaving that anchor travels net-LEFT —
     * directly against `DrawDirection.LEFT_TO_RIGHT`'s (1,0) axis.
     *
     * Consequence at t=0.3: LEGACY-FORWARD conducts the arc through the dive and the base
     * (pixels hug the authored left/base region, visible centroid < 0.5), while
     * HONOR_DIRECTION flips the sign and reveals the anchor's backwards arc — the elbow's
     * rightward stretch — landing further right. Empirically calibrated: the dive is deep
     * enough that the anchor sits on its entry slope and the eighth-length chord nets
     * leftward (see `DirectionMath.isSpatial` + the renderer's chord-dot sign).
     */
    private val hookFrom by lazy {
        harness.stroked(
            "from",
            listOf(
                PathNode.MoveTo(21f, 2f), PathNode.LineTo(6f, 6f),
                PathNode.LineTo(2f, 20f), PathNode.LineTo(21f, 21f),
            ),
        )
    }
    private val hookTo by lazy {
        harness.stroked(
            "to",
            listOf(
                PathNode.MoveTo(21f, 4f), PathNode.LineTo(6f, 8f),
                PathNode.LineTo(2f, 22f), PathNode.LineTo(21f, 23f),
            ),
        )
    }

    /** Closed square (winding-addressed CLOCKWISE/COUNTER_CLOCKWISE territory). */
    private val squareFrom by lazy { harness.stroked("from", harness.squareAt(8f, 8f)) }
    private val squareTo by lazy { harness.stroked("to", harness.squareAt(9f, 9f)) }

    private val legacyConfig = MorphConfiguration()
    private val honorConfig = MorphConfiguration(directionTraversal = DirectionTraversal.HONOR_DIRECTION)

    // ------------------------------------------------------------------ probes

    /**
     * Rule 4: mid-reveal pixels under HONOR_DIRECTION sit further along the requested axis
     * than under LEGACY, and LEGACY keeps the authored-side behavior for compatibility.
     */
    @Test
    fun honorDirectionTraversesLeftToRight() {
        val legacyMid = harness.renderMorph(
            hookFrom, hookTo, progress = 0.3f, configuration = legacyConfig,
            animation = revealAnimation(DrawDirection.LEFT_TO_RIGHT),
        )
        val honoredMid = harness.renderMorph(
            hookFrom, hookTo, progress = 0.3f, configuration = honorConfig,
            animation = revealAnimation(DrawDirection.LEFT_TO_RIGHT),
        )
        val legacyKey = legacyMid.litPixelsKey()
        val honoredKey = honoredMid.litPixelsKey()
        assertTrue("legacy mid-reveal must paint pixels", legacyKey.count > 0)
        assertTrue("honored mid-reveal must paint pixels", honoredKey.count > 0)
        assertTrue(
            "LEGACY keeps the authored-side behavior (centroidX=${legacyKey.centroidX})",
            legacyKey.centroidX < 0.5f,
        )
        assertTrue(
            "HONOR must sit further right (legacy=${legacyKey.centroidX}, honored=${honoredKey.centroidX})",
            honoredKey.centroidX > legacyKey.centroidX,
        )
    }

    /**
     * Review Focus item 2: closed contours flip through `shouldReverseWinding`, which runs
     * before any traversal-sign decision — the windings must render identically under both
     * policies (pinned for both CLOCKWISE and COUNTER_CLOCKWISE).
     */
    @Test
    fun closedContourWindingsRenderIdenticallyAcrossPolicies() {
        for (winding in listOf(DrawDirection.CLOCKWISE, DrawDirection.COUNTER_CLOCKWISE)) {
            val legacy = harness.renderMorph(
                squareFrom, squareTo, progress = 0.3f, configuration = legacyConfig,
                animation = revealAnimation(winding),
            )
            val honored = harness.renderMorph(
                squareFrom, squareTo, progress = 0.3f, configuration = honorConfig,
                animation = revealAnimation(winding),
            )
            val legacyKey = legacy.litPixelsKey()
            assertTrue("winding frame must paint pixels ($winding)", legacyKey.count > 0)
            assertTrue(
                "$winding must render identically under both policies",
                legacy.sameAs(honored),
            )
        }
    }

    /**
     * Target Contract rule 4 scopes traversal-honoring to OPEN contours: a closed contour
     * driven by a plain spatial travel axis (LEFT_TO_RIGHT) must render pixel-identical
     * under both policies — on closed contours CW/CCW remains the traversal control, so
     * the HONOR_DIRECTION chord-dot must not flip them.
     */
    @Test
    fun closedContourSpatialDirectionIgnoredUnderHonor() {
        val legacy = harness.renderMorph(
            squareFrom, squareTo, progress = 0.3f, configuration = legacyConfig,
            animation = revealAnimation(DrawDirection.LEFT_TO_RIGHT),
        )
        val honored = harness.renderMorph(
            squareFrom, squareTo, progress = 0.3f, configuration = honorConfig,
            animation = revealAnimation(DrawDirection.LEFT_TO_RIGHT),
        )
        assertTrue("closed spatial frame must paint pixels", legacy.litPixelsKey().count > 0)
        assertTrue(
            "closed contour + LEFT_TO_RIGHT must render identically under both policies",
            legacy.sameAs(honored),
        )
    }

    // ------------------------------------------------------------------ harness

    /** One FORWARD stroke-reveal animation of the whole vector toward [direction]. */
    private fun revealAnimation(direction: DrawDirection): VectorAnimationSpec =
        VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(
                    from = 0f, to = 1f, interval = 0f..1f,
                    mode = DrawMode.FORWARD, direction = direction,
                )
            }
        }
}
