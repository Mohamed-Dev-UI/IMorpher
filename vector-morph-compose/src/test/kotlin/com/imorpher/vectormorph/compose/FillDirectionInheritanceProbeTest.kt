package com.imorpher.vectormorph.compose

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.FillMode
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
 * Fill-default parity probe for the fill-direction inheritance contract (start/direction
 * coordination plan, C5): direction inheritance must adopt only **explicitly declared**
 * stroke directions. The all-defaulted combo below therefore keeps the fill's
 * `fillDirection = null`, and the renderer resolves `MorphConfiguration.defaultFillDirection`
 * — pixel-identical to explicitly sweeping that same direction. (Before the review fix an
 * `AUTO` stroke leaked into the fill config, routing the sweep along `axisFor(AUTO)`'s
 * top-left→bottom-right fallback diagonal instead.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FillDirectionInheritanceProbeTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    /** (b) Fill-default guarantee: `strokeReveal(); fill()` renders the documented default sweep. */
    @Test
    fun defaultedStrokeAndFillRenderExplicitDefaultFillDirectionParity() {
        val from = harness.filled("from", harness.squareAt(3f, 3f))
        val to = harness.filled("to", harness.squareAt(13f, 13f))

        val defaulted = VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths { strokeReveal(interval = 0f..0.5f) }                          // stroke direction defaulted (AUTO)
            allPaths { fill(mode = FillMode.DIRECTIONAL, interval = 0.5f..1f) }     // fill direction omitted
        }
        val explicitDefault = VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths { strokeReveal(interval = 0f..0.5f) }
            allPaths {
                fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT, interval = 0.5f..1f)
            }
        }
        // The discrimination frame proves this probe can actually fail: an opposite-diagonal
        // sweep must differ at the mid-sweep moment (same shapes otherwise).
        val contrast = VectorAnimationSpec {
            defaultEasing(LinearEasing)
            allPaths { strokeReveal(interval = 0f..0.5f) }
            allPaths {
                fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT, interval = 0.5f..1f)
            }
        }

        // mid-sweep: fillProgress = 0.5 (halfway along the 0.5..1 ramp) shows the sweep front.
        val t = 0.75f
        val defaultedFrame = harness.renderMorph(from, to, t, animation = defaulted)
        val explicitFrame = harness.renderMorph(from, to, t, animation = explicitDefault)
        val contrastFrame = harness.renderMorph(from, to, t, animation = contrast)

        // Painted-count guards: two empty frames would `sameAs` vacuously.
        val defaultedPainted = BitmapAsserts.paintedCount(defaultedFrame)
        val explicitPainted = BitmapAsserts.paintedCount(explicitFrame)
        assertTrue("mid-sweep defaulted fill must paint (painted=$defaultedPainted)", defaultedPainted > 0)
        assertTrue("mid-sweep explicit fill must paint (painted=$explicitPainted)", explicitPainted > 0)

        assertTrue(
            "defaulted stroke+fill must resolve to defaultFillDirection " +
                "(identical sweep to the explicit TOP_RIGHT_TO_BOTTOM_LEFT fill)",
            defaultedFrame.sameAs(explicitFrame),
        )
        assertFalse(
            "probe sanity: the opposite diagonal must sweep differently at t=$t",
            defaultedFrame.sameAs(contrastFrame),
        )
    }
}
