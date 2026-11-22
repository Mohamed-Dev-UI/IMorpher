package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.transition.ContentEffect
import com.imorpher.vectormorph.core.transition.ContentEndpoint
import com.imorpher.vectormorph.core.transition.ContentTransition
import com.imorpher.vectormorph.core.transition.WipeDirection
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
 * Rendered contract for content transitions: endpoints are exact, the two composite modes are
 * observably distinct, wipes reveal from the requested edge, feathering softens the edge, and
 * layer effects move/scale/rotate the right endpoint. Painter and slot endpoints share the
 * same resolved math, so each suite exercises its own drawing path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ContentTransitionRenderTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(
            Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            framePx = FRAME,
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun solid(argb: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(FRAME, FRAME, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(argb)
        return bitmap.asImageBitmap()
    }

    private val redImage by lazy { solid(0xFFFF0000.toInt()) }
    private val blueImage by lazy { solid(0xFF0000FF.toInt()) }

    private fun renderImages(
        progress: Float,
        transition: ContentTransition,
        preference: MotionPreference = MotionPreference.FULL,
    ): Bitmap = harness.renderContent {
        MorphImage(
            from = redImage,
            to = blueImage,
            progress = progress,
            transition = transition,
            motionPreference = preference,
            width = FRAME.dp,
            height = FRAME.dp,
        )
    }

    private fun renderSlots(
        progress: Float,
        transition: ContentTransition,
        preference: MotionPreference = MotionPreference.FULL,
    ): Bitmap = harness.renderContent {
        MorphContent(
            progress = progress,
            transition = transition,
            motionPreference = preference,
            modifier = Modifier.size(FRAME.dp),
            from = { Box(Modifier.fillMaxSize().background(Color.Red)) },
            to = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
        )
    }

    // ------------------------------------------------------------------ pixel helpers

    private fun Bitmap.alphaAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 24) and 0xFF
    private fun Bitmap.redAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 16) and 0xFF
    private fun Bitmap.greenAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 8) and 0xFF
    private fun Bitmap.blueAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFF

    private fun Bitmap.describe(x: Int, y: Int): String =
        "($x,$y)=a${alphaAt(x, y)} r${redAt(x, y)} g${greenAt(x, y)} b${blueAt(x, y)}"

    private fun assertOpaqueRed(bitmap: Bitmap, x: Int, y: Int) {
        assertEquals("red pixel ${bitmap.describe(x, y)}", 255, bitmap.alphaAt(x, y))
        assertEquals("red pixel ${bitmap.describe(x, y)}", 255, bitmap.redAt(x, y))
        assertEquals("red pixel ${bitmap.describe(x, y)}", 0, bitmap.blueAt(x, y))
    }

    private fun assertOpaqueBlue(bitmap: Bitmap, x: Int, y: Int) {
        assertEquals("blue pixel ${bitmap.describe(x, y)}", 255, bitmap.alphaAt(x, y))
        assertEquals("blue pixel ${bitmap.describe(x, y)}", 255, bitmap.blueAt(x, y))
        assertEquals("blue pixel ${bitmap.describe(x, y)}", 0, bitmap.redAt(x, y))
    }

    // ------------------------------------------------------------------ modes

    @Test
    fun painterEndpointsRenderExactlyAtProgressZeroAndOne() {
        val start = renderImages(0f, ContentTransition.Crossfade())
        assertOpaqueRed(start, FRAME / 2, FRAME / 2)

        val end = renderImages(1f, ContentTransition.Crossfade())
        assertOpaqueBlue(end, FRAME / 2, FRAME / 2)
    }

    @Test
    fun crossfadeMidpointLetsTheBackgroundThrough() {
        val mid = renderImages(0.5f, ContentTransition.Crossfade())
        val pixel = mid.describe(FRAME / 2, FRAME / 2)
        assertTrue("both endpoints contribute: $pixel", mid.redAt(FRAME / 2, FRAME / 2) > 30)
        assertTrue("both endpoints contribute: $pixel", mid.blueAt(FRAME / 2, FRAME / 2) > 100)
        // Both layers at half alpha: the composite is translucent, unlike FadeOver.
        assertTrue("through-composite alpha: $pixel", mid.alphaAt(FRAME / 2, FRAME / 2) in 150..230)
    }

    @Test
    fun fadeOverKeepsTheCompositeOpaque() {
        val mid = renderImages(0.5f, ContentTransition.FadeOver())
        val pixel = mid.describe(FRAME / 2, FRAME / 2)
        assertEquals("over-composite alpha: $pixel", 255, mid.alphaAt(FRAME / 2, FRAME / 2))
        assertTrue("incoming endpoint blends over outgoing: $pixel", mid.redAt(FRAME / 2, FRAME / 2) in 60..200)
        assertTrue("incoming endpoint blends over outgoing: $pixel", mid.blueAt(FRAME / 2, FRAME / 2) in 60..200)

        val crossfade = renderImages(0.5f, ContentTransition.Crossfade())
        assertTrue(
            "FadeOver must differ from Crossfade",
            BitmapAsserts.meanAbsDifference(mid, crossfade) > 0.01f,
        )
    }

    @Test
    fun wipeRevealsTheIncomingEndpointFromTheRequestedEdge() {
        val leftToRight = renderImages(
            0.5f,
            ContentTransition.Wipe(direction = WipeDirection.LEFT_TO_RIGHT),
        )
        assertOpaqueBlue(leftToRight, 10, FRAME / 2)
        assertOpaqueRed(leftToRight, FRAME - 10, FRAME / 2)

        val rightToLeft = renderImages(
            0.5f,
            ContentTransition.Wipe(direction = WipeDirection.RIGHT_TO_LEFT),
        )
        assertOpaqueRed(rightToLeft, 10, FRAME / 2)
        assertOpaqueBlue(rightToLeft, FRAME - 10, FRAME / 2)

        val topToBottom = renderImages(
            0.5f,
            ContentTransition.Wipe(direction = WipeDirection.TOP_TO_BOTTOM),
        )
        assertOpaqueBlue(topToBottom, FRAME / 2, 10)
        assertOpaqueRed(topToBottom, FRAME / 2, FRAME - 10)

        val bottomToTop = renderImages(
            0.5f,
            ContentTransition.Wipe(direction = WipeDirection.BOTTOM_TO_TOP),
        )
        assertOpaqueRed(bottomToTop, FRAME / 2, 10)
        assertOpaqueBlue(bottomToTop, FRAME / 2, FRAME - 10)
    }

    @Test
    fun wipeBoundsAreEmptyAtZeroAndFullAtOne() {
        val hidden = renderImages(0f, ContentTransition.Wipe())
        assertOpaqueRed(hidden, 10, FRAME / 2)
        assertOpaqueRed(hidden, FRAME - 10, FRAME / 2)

        val revealed = renderImages(1f, ContentTransition.Wipe())
        assertOpaqueBlue(revealed, 10, FRAME / 2)
        assertOpaqueBlue(revealed, FRAME - 10, FRAME / 2)
    }

    @Test
    fun featherSoftensTheWipeEdge() {
        val hard = renderImages(0.5f, ContentTransition.Wipe(feather = 0f))
        val soft = renderImages(0.5f, ContentTransition.Wipe(feather = 0.3f))

        val hardEdge = hard.describe(FRAME / 2, FRAME / 2)
        assertTrue(
            "a hard wipe has no blended edge pixel: $hardEdge",
            hard.redAt(FRAME / 2, FRAME / 2) == 0 || hard.blueAt(FRAME / 2, FRAME / 2) == 0,
        )

        val softEdge = soft.describe(FRAME / 2, FRAME / 2)
        assertTrue(
            "a feathered wipe blends the edge: $softEdge",
            soft.redAt(FRAME / 2, FRAME / 2) > 20 && soft.blueAt(FRAME / 2, FRAME / 2) > 20,
        )
        // Well outside the feather band the wipe is still fully opaque on each side.
        assertOpaqueBlue(soft, 10, FRAME / 2)
        assertOpaqueRed(soft, FRAME - 10, FRAME / 2)
    }

    // ------------------------------------------------------------------ effects

    @Test
    fun scaleEffectShrinksTheVisibleEndpoint() {
        val neutral = renderImages(0f, ContentTransition.Crossfade())
        val scaled = renderImages(
            0f,
            ContentTransition.Crossfade(
                effects = listOf(ContentEffect.Scale(ContentEndpoint.FROM, from = 0.5f, to = 1f)),
            ),
        )

        assertEquals("neutral endpoint fills the frame", FRAME * FRAME, BitmapAsserts.paintedCount(neutral))
        assertTrue(
            "scaled endpoint covers less: ${BitmapAsserts.paintedCount(scaled)}",
            BitmapAsserts.paintedCount(scaled) < FRAME * FRAME / 2,
        )
        assertEquals("outside the scaled layer", 0, scaled.alphaAt(5, FRAME / 2))
        assertOpaqueRed(scaled, FRAME / 2, FRAME / 2)
    }

    @Test
    fun translationEffectMovesTheVisibleEndpoint() {
        val translated = renderImages(
            0f,
            ContentTransition.Crossfade(
                effects = listOf(ContentEffect.Translate(ContentEndpoint.FROM, fromX = 0.25f)),
            ),
        )
        assertEquals("the left edge is vacated", 0, translated.alphaAt(5, FRAME / 2))
        assertOpaqueRed(translated, FRAME - 10, FRAME / 2)
    }

    @Test
    fun rotationEffectChangesTheEndpointFootprint() {
        val neutral = renderImages(0f, ContentTransition.Crossfade())
        val rotated = renderImages(
            0f,
            ContentTransition.Crossfade(
                effects = listOf(ContentEffect.Rotate(ContentEndpoint.FROM, fromDegrees = 45f)),
            ),
        )
        assertTrue(
            "a rotated square leaves its corners empty: ${BitmapAsserts.paintedCount(rotated)}",
            BitmapAsserts.paintedCount(rotated) < BitmapAsserts.paintedCount(neutral),
        )
        assertEquals("corner outside the rotated square", 0, rotated.alphaAt(2, 2))
        assertOpaqueRed(rotated, FRAME / 2, FRAME / 2)
    }

    // ------------------------------------------------------------------ motion preference

    @Test
    fun instantPreferenceSnapsToTheNearerEndpoint() {
        val wipe = ContentTransition.Wipe(direction = WipeDirection.LEFT_TO_RIGHT)

        // Under FULL the wipe has already uncovered the left edge by 0.4; INSTANT must not
        // paint that part-way frame.
        assertOpaqueBlue(renderImages(0.4f, wipe), 10, FRAME / 2)
        assertOpaqueRed(renderImages(0.4f, wipe, MotionPreference.INSTANT), 10, FRAME / 2)

        // …and by 0.6 the right edge is still outgoing under FULL, fully incoming under INSTANT.
        assertOpaqueRed(renderImages(0.6f, wipe), FRAME - 10, FRAME / 2)
        assertOpaqueBlue(renderImages(0.6f, wipe, MotionPreference.INSTANT), FRAME - 10, FRAME / 2)
    }

    @Test
    fun reducedPreferenceDropsTransformsButKeepsTheReveal() {
        val transform = ContentTransition.Wipe(
            effects = listOf(ContentEffect.Scale(ContentEndpoint.FROM, from = 1f, to = 0f)),
        )
        val plain = ContentTransition.Wipe()

        val fullRender = renderImages(0.5f, transform)
        val reducedRender = renderImages(0.5f, transform, MotionPreference.REDUCED)
        val plainRender = renderImages(0.5f, plain)

        assertTrue(
            "FULL keeps the transform: ${BitmapAsserts.meanAbsDifference(fullRender, plainRender)}",
            BitmapAsserts.meanAbsDifference(fullRender, plainRender) > 0.01f,
        )
        assertTrue(
            "REDUCED renders the same reveal without the transform",
            BitmapAsserts.meanAbsDifference(reducedRender, plainRender) < 0.01f,
        )
    }

    @Test
    fun crossfadeOnlyForcesTheCrossfadeForSlots() {
        val wipe = ContentTransition.Wipe(direction = WipeDirection.LEFT_TO_RIGHT)

        val revealed = renderSlots(0.5f, wipe)
        assertEquals("a revealed wipe stays opaque", 255, revealed.alphaAt(FRAME - 10, FRAME / 2))

        val forced = renderSlots(0.5f, wipe, MotionPreference.CROSSFADE_ONLY)
        assertTrue(
            "crossfade-only lets the background through: ${forced.describe(FRAME - 10, FRAME / 2)}",
            forced.alphaAt(FRAME - 10, FRAME / 2) < 200,
        )
    }

    // ------------------------------------------------------------------ slots

    @Test
    fun composableSlotsCrossfadeWithTheSameContract() {
        val start = renderSlots(0f, ContentTransition.Crossfade())
        assertOpaqueRed(start, FRAME / 2, FRAME / 2)

        val end = renderSlots(1f, ContentTransition.Crossfade())
        assertOpaqueBlue(end, FRAME / 2, FRAME / 2)

        val mid = renderSlots(0.5f, ContentTransition.Crossfade())
        val pixel = mid.describe(FRAME / 2, FRAME / 2)
        assertTrue("both slots contribute: $pixel", mid.redAt(FRAME / 2, FRAME / 2) > 30)
        assertTrue("both slots contribute: $pixel", mid.blueAt(FRAME / 2, FRAME / 2) > 100)
        assertTrue("through-composite alpha: $pixel", mid.alphaAt(FRAME / 2, FRAME / 2) in 150..230)
    }

    @Test
    fun composableSlotsWipeAndTransform() {
        val wipe = renderSlots(
            0.5f,
            ContentTransition.Wipe(direction = WipeDirection.LEFT_TO_RIGHT),
        )
        assertOpaqueBlue(wipe, 10, FRAME / 2)
        assertOpaqueRed(wipe, FRAME - 10, FRAME / 2)

        val translated = renderSlots(
            0f,
            ContentTransition.Crossfade(
                effects = listOf(ContentEffect.Translate(ContentEndpoint.FROM, fromX = 0.25f)),
            ),
        )
        assertEquals("the left edge is vacated", 0, translated.alphaAt(5, FRAME / 2))
        assertOpaqueRed(translated, FRAME - 10, FRAME / 2)
    }

    @Test
    fun missingEndpointFadesAgainstNothing() {
        val onlyFrom = harness.renderContent {
            MorphImage(
                from = redImage,
                to = null,
                progress = 1f,
                width = FRAME.dp,
                height = FRAME.dp,
            )
        }
        assertEquals("progress 1 with no incoming endpoint is empty", 0, onlyFrom.alphaAt(FRAME / 2, FRAME / 2))
    }

    private companion object {
        const val FRAME = 96
    }
}
