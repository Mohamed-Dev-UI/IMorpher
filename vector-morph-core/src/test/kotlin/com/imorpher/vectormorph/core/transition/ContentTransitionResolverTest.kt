package com.imorpher.vectormorph.core.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the pure content-transition resolver: endpoint guarantees, composite
 * modes, wipe clamping, and effect composition. Rendering is covered separately.
 */
class ContentTransitionResolverTest {

    private fun assertEqualsAll(expected: Float, value: Float) =
        assertEquals(expected, value, 1e-4f)

    // ------------------------------------------------------------------ modes

    @Test
    fun defaultCrossfadeIsNeutralAtTheEndpoints() {
        val start = ContentTransitionResolver.resolve(0f, ContentTransition.Default)
        assertEqualsAll(1f, start.from.alpha)
        assertEqualsAll(0f, start.to.alpha)
        assertNull("crossfade has no wipe", start.wipe)
        assertEqualsAll(1f, start.from.scaleX)
        assertEqualsAll(0f, start.from.translationX)
        assertEqualsAll(0f, start.from.rotationDegrees)

        val end = ContentTransitionResolver.resolve(1f, ContentTransition.Default)
        assertEqualsAll(0f, end.from.alpha)
        assertEqualsAll(1f, end.to.alpha)
        assertEqualsAll(1f, end.to.scaleY)
    }

    @Test
    fun crossfadeMidpointShowsBothEndpointsAtHalfAlpha() {
        val mid = ContentTransitionResolver.resolve(0.5f, ContentTransition.Crossfade())
        assertEqualsAll(0.5f, mid.from.alpha)
        assertEqualsAll(0.5f, mid.to.alpha)
    }

    @Test
    fun fadeOverKeepsTheOutgoingEndpointOpaque() {
        val quarter = ContentTransitionResolver.resolve(0.25f, ContentTransition.FadeOver())
        assertEqualsAll(1f, quarter.from.alpha)
        assertEqualsAll(0.25f, quarter.to.alpha)

        val end = ContentTransitionResolver.resolve(1f, ContentTransition.FadeOver())
        assertEqualsAll(1f, end.from.alpha)
        assertEqualsAll(1f, end.to.alpha)
    }

    @Test
    fun wipeReportsDirectionRevealAndFeather() {
        val resolved = ContentTransitionResolver.resolve(
            0.3f,
            ContentTransition.Wipe(direction = WipeDirection.TOP_TO_BOTTOM, feather = 0.2f),
        )
        assertEqualsAll(1f, resolved.from.alpha)
        assertEqualsAll(1f, resolved.to.alpha)
        val wipe = resolved.wipe
        assertTrue("wipe must be present", wipe != null)
        assertEquals(WipeDirection.TOP_TO_BOTTOM, wipe!!.direction)
        assertEqualsAll(0.3f, wipe.revealedFraction)
        assertEqualsAll(0.2f, wipe.feather)
    }

    @Test
    fun wipeFeatherIsClampedAndNonFiniteValuesResolveToZero() {
        fun featherOf(value: Float): Float =
            ContentTransitionResolver.resolve(
                0.5f,
                ContentTransition.Wipe(feather = value),
            ).wipe!!.feather

        assertEqualsAll(0f, featherOf(-0.5f))
        assertEqualsAll(0.5f, featherOf(2f))
        assertEqualsAll(0f, featherOf(Float.NaN))
        assertEqualsAll(0f, featherOf(Float.POSITIVE_INFINITY))
    }

    @Test
    fun progressIsClampedAndNonFiniteProgressFallsBackToTheStart() {
        fun fractionAt(progress: Float): Float =
            ContentTransitionResolver.resolve(
                progress,
                ContentTransition.Wipe(),
            ).wipe!!.revealedFraction

        assertEqualsAll(0f, fractionAt(-2f))
        assertEqualsAll(0f, fractionAt(Float.NaN))
        assertEqualsAll(0f, fractionAt(Float.NEGATIVE_INFINITY))
        assertEqualsAll(1f, fractionAt(2f))
        assertEqualsAll(1f, fractionAt(Float.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------------------ effects

    @Test
    fun effectsInterpolateAndComposePerEndpoint() {
        val resolved = ContentTransitionResolver.resolve(
            0.5f,
            ContentTransition.Crossfade(
                effects = listOf(
                    ContentEffect.Fade(ContentEndpoint.TO, from = 1f, to = 0.5f),
                    ContentEffect.Scale(ContentEndpoint.FROM, from = 1f, to = 2f),
                    ContentEffect.Scale(ContentEndpoint.FROM, from = 2f, to = 2f),
                    ContentEffect.Translate(ContentEndpoint.TO, toX = 0.25f, toY = -0.5f),
                    ContentEffect.Rotate(ContentEndpoint.TO, toDegrees = 90f),
                ),
            ),
        )

        // Mode alpha (0.5) multiplied by the fade ramp (1..0.5 -> 0.75).
        assertEqualsAll(0.375f, resolved.to.alpha)
        // Outgoing alpha is untouched by TO-scoped effects.
        assertEqualsAll(0.5f, resolved.from.alpha)
        // Scale effects multiply: 1.5 (ramp) * 2 (constant).
        assertEqualsAll(3f, resolved.from.scaleX)
        assertEqualsAll(3f, resolved.from.scaleY)
        assertEqualsAll(1f, resolved.to.scaleX)
        assertEqualsAll(0.125f, resolved.to.translationX)
        assertEqualsAll(-0.25f, resolved.to.translationY)
        assertEqualsAll(45f, resolved.to.rotationDegrees)
        assertEqualsAll(0f, resolved.from.rotationDegrees)
    }

    @Test
    fun effectsNeverEmitNaNOrOutOfRangeLayers() {
        val resolved = ContentTransitionResolver.resolve(
            0.5f,
            ContentTransition.Wipe(
                effects = listOf(
                    ContentEffect.Scale(ContentEndpoint.FROM, from = Float.NaN, to = -3f),
                    ContentEffect.Fade(ContentEndpoint.TO, from = Float.NaN, to = 2f),
                    ContentEffect.Translate(ContentEndpoint.TO, fromX = Float.NaN, toX = Float.POSITIVE_INFINITY),
                    ContentEffect.Rotate(ContentEndpoint.FROM, fromDegrees = Float.NEGATIVE_INFINITY, toDegrees = Float.NaN),
                ),
            ),
        )

        assertEqualsAll(0f, resolved.from.scaleX)
        assertEqualsAll(0f, resolved.from.scaleY)
        assertEqualsAll(1f, resolved.to.alpha)
        assertEqualsAll(0f, resolved.to.translationX)
        assertEqualsAll(0f, resolved.from.rotationDegrees)
        for (layer in listOf(resolved.from, resolved.to)) {
            assertTrue("alpha in range: $layer", layer.alpha in 0f..1f)
            assertTrue("scale in range: $layer", layer.scaleX in 0f..4f && layer.scaleY in 0f..4f)
            assertTrue("translation finite: $layer", layer.translationX.isFinite() && layer.translationY.isFinite())
            assertTrue("rotation finite: $layer", layer.rotationDegrees.isFinite())
        }
    }

    @Test
    fun scaleIsClampedToTheSupportedMaximum() {
        val resolved = ContentTransitionResolver.resolve(
            1f,
            ContentTransition.Crossfade(
                effects = listOf(
                    ContentEffect.Scale(ContentEndpoint.TO, from = 4f, to = 4f),
                    ContentEffect.Scale(ContentEndpoint.TO, from = 4f, to = 4f),
                ),
            ),
        )
        assertEqualsAll(4f, resolved.to.scaleX)
        assertEqualsAll(4f, resolved.to.scaleY)
    }
}
