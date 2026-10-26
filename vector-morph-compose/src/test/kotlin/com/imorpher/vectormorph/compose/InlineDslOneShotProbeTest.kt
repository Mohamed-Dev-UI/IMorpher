package com.imorpher.vectormorph.compose

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationDsl
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task 17 compose probe (plan-literal settle lock): an inline-DSL-built one-shot plays
 * through the `VectorAnimation(vector, animation)` auto-play entry to its settled frame —
 * the played bitmap must equal the explicit `progress = 1f` reference.
 *
 * Scope note: this environment's Robolectric choreography drains whole animations per
 * idle round (verified empirically: even `tween(10_000)` completes within 24 pump rounds,
 * and identity-keyed restarts between rounds re-complete), so a restart regression is not
 * looper-reproducible at rest here — the identity-restart end-to-end case stays covered
 * by the core `AnimationStableKeyTest` contract plus on-device androidTests
 * (`RetargetProbeTest`, skipped locally; noted in the PR description). This lock catches
 * wiring breakage: unwired folding, never-settling keys, or a fold regression that leaves
 * the one-shot short of its settled state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class InlineDslOneShotProbeTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    /** A plain stroked square. */
    private fun self() = harness.stroked(
        "oneShot",
        harness.squareAt(6f, 6f),
        width = 2f,
    )

    private fun build() = VectorAnimationDsl {
        defaultEasing(LinearEasing)
        allPaths {
            drawProgress(
                from = 0f, to = 1f, interval = 0f..1f,
                mode = DrawMode.FORWARD, direction = DrawDirection.LEFT_TO_RIGHT,
            )
        }
    }

    @Test
    fun inlineDslOneShotReachesTheSettledFrame() {
        val settled = harness.renderAutoPlayVector(self(), build(), progress = 1f)
        val played = harness.renderAutoPlayVector(self(), build(), progress = null)
        val delta = BitmapAsserts.meanAbsDifference(settled, played)
        assertTrue(
            "the auto-played inline-DSL one-shot must reach the settled frame (wiring/lock regression never gets there; delta=$delta)",
            delta < 0.01f,
        )
    }
}
