package com.imorpher.vectormorph.compose

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.vector.PathNode
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FallbackStrategy
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
 * Task 4 application probes (start/direction coordination plan rule 4):
 * [MorphConfiguration.directionTraversal] must let a `DrawMode.FORWARD` stroke reveal on an
 * open contour honor its draw direction's axis. Under `LEGACY` (opt-in) the pen follows the
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

    // HONOR_DIRECTION is the 0.2.0 default; these probes compare against an explicit LEGACY opt-in.
    private val legacyConfig = MorphConfiguration(directionTraversal = DirectionTraversal.LEGACY)
    private val honorConfig = MorphConfiguration()

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

    // ------------------------------------------------------------------ task 9: ignored render sites

    /**
     * Rule 4 at the missing-path DRAW site: the open hook matches nothing in the from
     * vector and arrives via `missingPath=DRAW` — its pen reveal runs through
     * `StrokePass.drawMissingSide`, which must honor the traversal policy exactly like
     * the morph stroke pass does. Today the site hard-codes LEGACY behavior, so the two
     * frames measure exactly 0 difference (the matched closed square renders identically
     * under both policies; only the hook's site can contribute).
     */
    @Test
    fun drawMissingPathHonorsTraversalPolicy() {
        val from = harness.strokedPaths("fixture", harness.squareAt(3f, 3f), pathNames = listOf("match"))
        val to = harness.strokedPaths(
            "fixture",
            harness.squareAt(3f, 3f),
            listOf(
                PathNode.MoveTo(21f, 2f), PathNode.LineTo(6f, 6f),
                PathNode.LineTo(2f, 20f), PathNode.LineTo(21f, 21f),
            ),
            pathNames = listOf("match", "odd"),
        )
        val legacy = harness.renderMorph(
            from, to, 0.3f,
            configuration = MorphConfiguration(
                missingPath = MissingPathBehavior.DRAW,
                directionTraversal = DirectionTraversal.LEGACY,
            ),
            animation = revealAnimation(DrawDirection.LEFT_TO_RIGHT),
        )
        val honored = harness.renderMorph(
            from, to, 0.3f,
            configuration = MorphConfiguration(missingPath = MissingPathBehavior.DRAW),
            animation = revealAnimation(DrawDirection.LEFT_TO_RIGHT),
        )
        val delta = BitmapAsserts.meanAbsDifference(legacy, honored)
        assertTrue(
            "missing-path DRAW must honor the traversal policy (stale would be exactly 0; delta=$delta)",
            delta > 0.01f,
        )
    }

    /**
     * The FillPass PATH_TRAVERSAL arm must honor the configuration's timing: with a
     * two-contour missing path under BY_PATH each contour owns an equal-time slice
     * (long zigzag finishes; the tiny tail starts), while UNIFORM reveals the combined
     * arc uniformly. An empty from vector keeps a SINGLE pair so the evaluator's own
     * per-pair timing cannot brighten either frame — the render site is the only
     * contributor — and today the arm always maps UNIFORM, so the frames measure
     * exactly 0 difference.
     */
    @Test
    fun pathTraversalFillHonorsTimingMode() {
        val longContour = listOf(
            PathNode.MoveTo(2f, 2f), PathNode.LineTo(10f, 8f),
            PathNode.LineTo(2f, 14f), PathNode.LineTo(10f, 20f),
        )
        val shortContour = listOf(PathNode.MoveTo(16f, 3f), PathNode.LineTo(17.5f, 4.5f))
        val from = harness.filled("from")
        // BOTH contours concatenated into ONE path (one addPath = one path element; the
        // compiler splits subpaths into contours): the render site's per-contour spread
        // is the unit under test — separate path elements would plan two single-contour
        // pairs and bypass it.
        val to = harness.filled("to", longContour + shortContour)
        val common = MorphConfiguration(
            fallback = FallbackStrategy.BEST_EFFORT,
            missingPath = MissingPathBehavior.DRAW,
            defaultFillMode = FillMode.PATH_TRAVERSAL,
        )
        val uniform = harness.renderMorph(
            from, to, 0.6f, configuration = common.copy(timing = TimingMode.UNIFORM),
        )
        val byPath = harness.renderMorph(
            from, to, 0.6f, configuration = common.copy(timing = TimingMode.BY_PATH),
        )
        val delta = BitmapAsserts.meanAbsDifference(uniform, byPath)
        assertTrue(
            "PATH_TRAVERSAL fill must honor the timing mode (stale would be exactly 0; delta=$delta)",
            delta > 0.01f,
        )
    }

    /**
     * The FillPass PATH_TRAVERSAL arm must resolve the custom draw anchor: a timeline
     * `draw(start = PathStart.CUSTOM)` steers the traversal choreography, so
     * `customDrawAnchors["stroke"]=0.5` starts the fill's reveal arc at the half-length
     * point instead of arc 0. Today the arm drops the anchor, so the frames measure
     * exactly 0 difference.
     */
    @Test
    fun pathTraversalFillHonorsCustomDrawAnchors() {
        val anchorHook = listOf(
            PathNode.MoveTo(21f, 2f), PathNode.LineTo(6f, 6f),
            PathNode.LineTo(2f, 20f), PathNode.LineTo(21f, 21f),
        )
        val from = harness.filled("from")
        val to = harness.filled("to", anchorHook, pathNames = listOf("stroke"))
        val animation = VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(
                    from = 0f, to = 1f, interval = 0f..1f,
                    mode = DrawMode.FORWARD, start = PathStart.CUSTOM,
                )
            }
        }
        val anchored = harness.renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(
                fallback = FallbackStrategy.BEST_EFFORT,
                missingPath = MissingPathBehavior.DRAW,
                defaultFillMode = FillMode.PATH_TRAVERSAL,
                customDrawAnchors = mapOf("stroke" to 0.5f),
            ),
            animation = animation,
        )
        val atZero = harness.renderMorph(
            from, to, 0.5f,
            configuration = MorphConfiguration(
                fallback = FallbackStrategy.BEST_EFFORT,
                missingPath = MissingPathBehavior.DRAW,
                defaultFillMode = FillMode.PATH_TRAVERSAL,
            ),
            animation = animation,
        )
        val delta = BitmapAsserts.meanAbsDifference(anchored, atZero)
        assertTrue(
            "PATH_TRAVERSAL fill must honor the custom draw anchor (stale would be exactly 0; delta=$delta)",
            delta > 0.01f,
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
