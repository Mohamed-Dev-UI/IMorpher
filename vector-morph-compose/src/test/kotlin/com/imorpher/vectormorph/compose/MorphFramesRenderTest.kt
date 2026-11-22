package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.transition.ContentTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendered contract for the frame-source boundary: the host's latest frame is what draws, an
 * unavailable source composites as absent, an ended source freezes on its last frame, and the
 * latest-wins buffer reports the frames it dropped. Progress is never touched by any of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MorphFramesRenderTest {

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
    private val greenImage by lazy { solid(0xFF00FF00.toInt()) }
    private val blueImage by lazy { solid(0xFF0000FF.toInt()) }

    private fun render(
        from: FrameSource?,
        to: FrameSource?,
        progress: Float,
    ): Bitmap = harness.renderContent {
        MorphFrames(
            from = from,
            to = to,
            progress = progress,
            transition = ContentTransition.Crossfade(),
            width = FRAME.dp,
            height = FRAME.dp,
        )
    }

    private fun Bitmap.alphaAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 24) and 0xFF
    private fun Bitmap.redAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 16) and 0xFF
    private fun Bitmap.greenAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 8) and 0xFF
    private fun Bitmap.blueAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFF

    // ------------------------------------------------------------------ behavior

    @Test
    fun latestSubmittedFrameIsWhatRenders() {
        val outgoing = FrameSource(redImage)
        val incoming = FrameSource(blueImage)

        val start = render(outgoing, incoming, 0f)
        assertEquals("the stored frame draws", 255, start.alphaAt(FRAME / 2, FRAME / 2))
        assertEquals(255, start.redAt(FRAME / 2, FRAME / 2))

        // The host advances the outgoing source: the new frame wins immediately.
        outgoing.submit(greenImage, presentationTimeMillis = 16L)
        val advanced = render(outgoing, incoming, 0f)
        assertEquals(255, advanced.greenAt(FRAME / 2, FRAME / 2))
        assertEquals(0, advanced.redAt(FRAME / 2, FRAME / 2))
        assertEquals("timestamp metadata is preserved for host alignment", 16L, outgoing.presentationTimeMillis)
        assertEquals(FrameStatus.READY, outgoing.status)
    }

    @Test
    fun unavailableEndpointCompositesAsAbsent() {
        val outgoing = FrameSource(redImage)
        val incoming = FrameSource(blueImage)
        incoming.markUnavailable()

        assertNull(incoming.frame)
        assertEquals(FrameStatus.UNAVAILABLE, incoming.status)

        val emptyIncoming = render(outgoing, incoming, 1f)
        assertEquals(
            "an unavailable endpoint draws nothing instead of throwing",
            0,
            emptyIncoming.alphaAt(FRAME / 2, FRAME / 2),
        )

        val stillOutgoing = render(outgoing, incoming, 0f)
        assertEquals(255, stillOutgoing.alphaAt(FRAME / 2, FRAME / 2))
        assertEquals(255, stillOutgoing.redAt(FRAME / 2, FRAME / 2))
    }

    @Test
    fun endedSourceKeepsItsLastFrame() {
        val outgoing = FrameSource(redImage)
        val incoming = FrameSource(blueImage)
        outgoing.markEnded()

        assertEquals(FrameStatus.ENDED, outgoing.status)
        val frozen = render(outgoing, incoming, 0f)
        assertEquals("an ended source freezes on its last frame", 255, frozen.alphaAt(FRAME / 2, FRAME / 2))
        assertEquals(255, frozen.redAt(FRAME / 2, FRAME / 2))
    }

    @Test
    fun latestWinsBufferCountsDroppedFrames() {
        val source = FrameSource()
        source.submit(redImage, presentationTimeMillis = 0L)
        source.submit(greenImage, presentationTimeMillis = 16L)
        source.submit(blueImage, presentationTimeMillis = 32L)

        assertEquals("every submit is accepted", 3, source.submittedFrames)
        assertEquals("the two unpresented frames were dropped", 2, source.droppedFrames)
        assertEquals(0, source.presentedFrames)

        render(source, null, 0f)
        assertEquals("the rendered frame is presented exactly once", 1, source.presentedFrames)
        assertEquals(3, source.submittedFrames)

        // Nothing new to present: rendering again must not inflate the counter.
        render(source, null, 0f)
        assertEquals(1, source.presentedFrames)
    }

    @Test
    fun releaseDropsTheReferenceForDisposal() {
        val source = FrameSource(redImage)
        source.release()

        assertNull("the host must be able to dispose its bitmap", source.frame)
        assertEquals(FrameStatus.UNAVAILABLE, source.status)
        assertNull(source.presentationTimeMillis)
    }

    @Test
    fun frameTimingNeverMovesTheCallerOwnedProgress() {
        val outgoing = FrameSource(redImage)
        val incoming = FrameSource(blueImage)

        // Advance both sources several times with timestamps; the blend at 0.5 is unchanged.
        repeat(3) { index ->
            outgoing.submit(redImage, presentationTimeMillis = index * 16L)
            incoming.submit(blueImage, presentationTimeMillis = index * 16L)
        }
        val mid = render(outgoing, incoming, 0.5f)
        // Two layers at half alpha composite to ~0.75 alpha — the same through-crossfade
        // the plain MorphImage probe produces, regardless of how many frames arrived.
        assertTrue(
            "crossfade at 0.5 is the caller's clock, not the frame clock: a=${mid.alphaAt(FRAME / 2, FRAME / 2)}",
            mid.alphaAt(FRAME / 2, FRAME / 2) in 150..230,
        )
    }

    private companion object {
        const val FRAME = 96
    }
}
