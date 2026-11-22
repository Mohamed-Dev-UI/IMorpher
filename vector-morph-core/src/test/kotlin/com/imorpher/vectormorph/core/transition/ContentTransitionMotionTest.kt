package com.imorpher.vectormorph.core.transition

import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessibility contract for content transitions: the same progress/effect vocabulary must
 * resolve under every [MotionPreference], exactly as the vector entries do.
 */
class ContentTransitionMotionTest {

    private val transformsAndFade = listOf(
        ContentEffect.Fade(ContentEndpoint.TO, from = 0f, to = 1f),
        ContentEffect.Scale(ContentEndpoint.FROM, from = 1f, to = 0.5f),
        ContentEffect.Translate(ContentEndpoint.FROM, fromX = 0.5f, toX = 0f),
        ContentEffect.Rotate(ContentEndpoint.TO, fromDegrees = 0f, toDegrees = 90f),
    )

    private val wipe = ContentTransition.Wipe(
        direction = WipeDirection.LEFT_TO_RIGHT,
        feather = 0.2f,
        effects = transformsAndFade,
    )

    @Test
    fun fullPreferenceKeepsTheTransitionUntouched() {
        assertEquals(wipe, wipe.reduced(MotionPreference.FULL))
        assertEquals(
            ContentTransitionResolver.resolve(0.5f, wipe),
            ContentTransitionResolver.resolve(0.5f, wipe, MotionPreference.FULL),
        )
    }

    @Test
    fun reducedDropsLayerTransformsButKeepsTheModeAndFades() {
        val reduced = wipe.reduced(MotionPreference.REDUCED)

        assertTrue("the reveal mode must survive", reduced is ContentTransition.Wipe)
        assertEquals(WipeDirection.LEFT_TO_RIGHT, (reduced as ContentTransition.Wipe).direction)
        assertEquals(0.2f, reduced.feather, 0f)
        assertEquals(
            listOf(ContentEffect.Fade(ContentEndpoint.TO, from = 0f, to = 1f)),
            reduced.effects,
        )

        val resolved = ContentTransitionResolver.resolve(0.5f, reduced, MotionPreference.REDUCED)
        assertNotNull("a wipe still reveals the incoming layer", resolved.wipe)
        assertEquals(0.5f, resolved.wipe!!.revealedFraction, 1e-6f)
        assertEquals("scale is neutral under REDUCED", 1f, resolved.from.scaleX, 0f)
        assertEquals("rotation is neutral under REDUCED", 0f, resolved.to.rotationDegrees, 0f)
        assertEquals("translation is neutral under REDUCED", 0f, resolved.from.translationX, 0f)
        assertEquals("fades still apply", 0.5f, resolved.to.alpha, 1e-6f)
    }

    @Test
    fun crossfadeOnlyForcesTheCrossfadeFallbackAndDropsTheWipe() {
        val forced = wipe.reduced(MotionPreference.CROSSFADE_ONLY)

        assertTrue(forced is ContentTransition.Crossfade)
        assertEquals(
            "only alpha effects survive the fallback",
            listOf(ContentEffect.Fade(ContentEndpoint.TO, from = 0f, to = 1f)),
            forced.effects,
        )

        val resolved = ContentTransitionResolver.resolve(0.5f, forced, MotionPreference.CROSSFADE_ONLY)
        assertNull("no wipe survives a crossfade-only preference", resolved.wipe)
        // Mode alpha (0.5) multiplied by the surviving fade's own ramp (0..1 at t=0.5).
        assertEquals(0.25f, resolved.to.alpha, 1e-6f)
        assertEquals(0.5f, resolved.from.alpha, 1e-6f)
    }

    @Test
    fun instantSnapsProgressToTheNearerEndpoint() {
        val before = ContentTransitionResolver.resolve(0.49f, wipe, MotionPreference.INSTANT)
        assertNull("a snapped frame needs no mask", before.wipe)
        assertEquals("outgoing endpoint renders exactly", 1f, before.from.alpha, 0f)
        assertEquals("incoming endpoint is fully hidden", 0f, before.to.alpha, 0f)
        assertEquals("no transform survives INSTANT", 1f, before.from.scaleX, 0f)
        assertEquals(0f, before.to.rotationDegrees, 0f)

        val half = ContentTransitionResolver.resolve(0.5f, wipe, MotionPreference.INSTANT)
        assertEquals("0.5 resolves to the incoming endpoint", 1f, half.to.alpha, 0f)
        assertEquals(0f, half.from.alpha, 0f)

        val after = ContentTransitionResolver.resolve(0.51f, wipe, MotionPreference.INSTANT)
        assertEquals(half, after)
    }

    @Test
    fun instantEndpointOutputIsExactAtBothEnds() {
        assertEquals(
            ContentTransitionResolver.resolve(1f, ContentTransition.Crossfade()),
            ContentTransitionResolver.resolve(0.75f, wipe, MotionPreference.INSTANT),
        )
        assertEquals(
            ContentTransitionResolver.resolve(0f, ContentTransition.Crossfade()),
            ContentTransitionResolver.resolve(0.25f, wipe, MotionPreference.INSTANT),
        )
    }

    @Test
    fun twoArgumentResolveKeepsItsContract() {
        val crossfade = ContentTransitionResolver.resolve(0.25f, ContentTransition.Crossfade())
        assertEquals(0.75f, crossfade.from.alpha, 1e-6f)
        assertEquals(0.25f, crossfade.to.alpha, 1e-6f)
    }
}
