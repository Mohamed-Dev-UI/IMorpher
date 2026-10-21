package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.LinearEasing
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.vector.PathNode
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression probes for the start/direction anchor contract (start/direction coordination
 * plan, Tasks 1 + 3).
 *
 * **Target Contract rule 3:** an explicit `PathStart.START` means *literal arc position 0* of
 * the normalized contour regardless of `DrawDirection`; `PathStart.AUTO` (the DSL/`DrawConfig`
 * default) derives the anchor from the direction table — top-left for `AUTO` direction, which
 * is exactly what the old explicit `START` default used to render. The Task 1 parity probe
 * (`START ≡ AUTO`) therefore expired by design and is re-derived here as pixel-layout probes
 * on a contour whose arc 0 is not its top-left region, plus an equality probe that keeps the
 * DSL defaults pinned to the AUTO sentinel they must remain.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawDefaultAnchorRegressionTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Open polyline authored bottom-right → top-left: arc 0 sits at (21, 21), while the
     * bounding box's top-left corner lies on an INTERIOR vertex (3, 3). On this contour an
     * AUTO (top-left) anchor and a literal START (arc 0) anchor light up opposite halves.
     */
    private fun bottomRightToTopLeft() = listOf(
        PathNode.MoveTo(21f, 21f),
        PathNode.LineTo(16f, 10f),
        PathNode.LineTo(3f, 3f),
        PathNode.LineTo(10f, 12f),
    )

    /** Pair the fixture morphs into; same topology, same arc-0 vs top-left relationship. */
    private val from by lazy { harness.stroked("from", bottomRightToTopLeft(), width = 2f) }
    private val to by lazy {
        harness.stroked(
            "to",
            listOf(
                PathNode.MoveTo(21f, 19f),
                PathNode.LineTo(15f, 9f),
                PathNode.LineTo(3f, 2f),
                PathNode.LineTo(10f, 13f),
            ),
            width = 2f,
        )
    }

    // ------------------------------------------------------------------ probes

    /** (a) The no-arg DSL default (AUTO) still anchors the top-left region mid-reveal. */
    @Test
    fun autoSentinelDefaultAnchorsTopLeft() {
        listOf(0.2f, 0.25f).forEach { t ->
            val key = harness.renderMorph(from, to, t, animation = defaultAnimation()).litPixelsKey()
            assertTrue("default render must paint the reveal at t=$t", key.count > 0)
            assertTrue(
                "AUTO default must anchor top-left: centroid (${key.centroidX}, ${key.centroidY}) t=$t",
                key.centroidX < 0.5f && key.centroidY < 0.5f,
            )
        }
    }

    /** (b) Rule 3: explicit `START` anchors literal arc position 0 — the bottom-right origin here. */
    @Test
    fun explicitStartAnchorsLiteralArcZero() {
        listOf(0.2f, 0.25f).forEach { t ->
            val key = harness.renderMorph(from, to, t, animation = startStartAnimation()).litPixelsKey()
            assertTrue("explicit START must paint the reveal at t=$t", key.count > 0)
            assertTrue(
                "explicit START must anchor literal arc 0 (bottom-right): " +
                    "centroid (${key.centroidX}, ${key.centroidY}) t=$t",
                key.centroidX > 0.5f && key.centroidY > 0.5f,
            )
        }
    }

    /**
     * Intended rule-3 behavior change (a): `START` no longer mirrors the direction-derived
     * anchor, so this replaces the expired Task 1 `START ≡ AUTO` equality with a divergence
     * pin on the distinguishing contour.
     */
    @Test
    fun startAndAutoDivergeOnArcZeroMismatch() {
        val autoFrame = harness.renderMorph(from, to, 0.25f, animation = startAutoAnimation())
        val startFrame = harness.renderMorph(from, to, 0.25f, animation = startStartAnimation())
        assertFalse(
            "rule 3: START (literal arc 0) must render differently from AUTO (top-left anchor)",
            autoFrame.sameAs(startFrame),
        )
    }

    /**
     * Re-derived DSL-default parity probe: the flipped `drawProgress()`/`strokeReveal()`
     * defaults must keep the old default's *visual* (direction-derived top-left anchoring —
     * now spelled AUTO), which is equal to explicitly passing the AUTO sentinel and distinct
     * from the old explicit START default under rule 3.
     */
    @Test
    fun dslDefaultStaysDirectionAnchoredAfterRule3() {
        val explicitAuto = startAutoAnimation()
        val defaulted = VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(from = 0f, to = 1f, interval = 0f..1f)
            }
        }
        val aliased = VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                strokeReveal(interval = 0f..1f)
            }
        }
        val legacyStartDefault = startStartAnimation()
        listOf(0.2f, 0.25f).forEach { t ->
            val defaultedFrame = harness.renderMorph(from, to, t, animation = defaulted)
            assertTrue(
                "defaulted drawProgress must match the explicit AUTO sentinel at t=$t",
                harness.renderMorph(from, to, t, animation = explicitAuto).sameAs(defaultedFrame),
            )
            assertTrue(
                "defaulted strokeReveal alias must match the explicit AUTO sentinel at t=$t",
                harness.renderMorph(from, to, t, animation = aliased).sameAs(defaultedFrame),
            )
            assertFalse(
                "rule 3: DSL default must no longer equal the old explicit START default at t=$t",
                harness.renderMorph(from, to, t, animation = legacyStartDefault).sameAs(defaultedFrame),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun defaultAnimation(): VectorAnimationSpec = VectorAnimationSpec {
        defaultEasing(LinearEasing)
        allPaths {
            drawProgress(from = 0f, to = 1f, interval = 0f..1f)
        }
    }

    private fun startAutoAnimation(): VectorAnimationSpec = drawAnimation(PathStart.AUTO, DrawDirection.AUTO)

    private fun startStartAnimation(): VectorAnimationSpec = drawAnimation(PathStart.START, DrawDirection.AUTO)

    private fun drawAnimation(start: PathStart, direction: DrawDirection): VectorAnimationSpec =
        VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(
                    from = 0f, to = 1f, interval = 0f..1f,
                    start = start, direction = direction,
                )
            }
        }
}
