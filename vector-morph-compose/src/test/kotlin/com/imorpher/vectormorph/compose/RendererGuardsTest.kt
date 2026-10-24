package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.renderer.CustomizerFailures
import com.imorpher.vectormorph.core.animation.CustomVectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.MorphConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Crash guard (task 11), Alignment (task 20), and the solid tint fast path (task 8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RendererGuardsTest {

    private lateinit var activity: ComponentActivity
    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        harness = RenderProbeHarness(activity)
        CustomizerFailures.reset()
    }

    @Test
    fun throwingCustomAnimationCallbackIsDisabledAfterFirstFailure() {
        val throwing = CustomVectorAnimation { error("boom from a custom callback") }
        val animation = VectorAnimationSpec { customAnimation(throwing) }
        val configuration = MorphConfiguration(animation = animation)
        val from = harness.filled("from", harness.squareAt(4f, 4f, 16f))
        val to = harness.filled("to", harness.squareAt(3f, 3f, 18f))

        // Two full compositions: the first draw fails once, the second must skip the callback.
        harness.renderMorph(from, to, progress = 0.5f, configuration = configuration)
        harness.renderMorph(from, to, progress = 0.5f, configuration = configuration)

        assertEquals("a throwing callback runs at most once", 1, CustomizerFailures.count)
        assertEquals("boom from a custom callback", CustomizerFailures.lastMessage)
    }

    @Test
    fun alignmentShiftsContentInsideTheCanvas() {
        val from = harness.filled("from", harness.squareAt(9f, 9f, 6f))

        val centered = renderAligned(from, Alignment.Center, widthPx = 96, heightPx = 48)
        val start = renderAligned(from, Alignment.CenterStart, widthPx = 96, heightPx = 48)

        val centeredKey = BitmapAsserts.litPixelsKey(centered)
        val startKey = BitmapAsserts.litPixelsKey(start)
        assertTrue("centered frame paints content", centeredKey.count > 0)
        assertEquals("alignment preserves painted area", centeredKey.count, startKey.count)
        assertTrue(
            "CenterStart must shift content left (centered=${centeredKey.centroidX}, start=${startKey.centroidX})",
            startKey.centroidX < centeredKey.centroidX - 0.15f,
        )
    }

    @Test
    fun solidTintFastPathPreservesCoverageAndRecolors() {
        val from = harness.filled("from", harness.squareAt(6f, 6f, 12f))

        val untinted = harness.renderMorph(from, from, progress = 1f)
        val tinted = renderTinted(from, Color.Red)

        assertEquals(
            "a solid tint must not change which pixels are covered",
            BitmapAsserts.paintedCount(untinted),
            BitmapAsserts.paintedCount(tinted),
        )
        val litPixel = firstLitPixel(tinted)
        assertTrue(litPixel != 0)
        assertTrue("tint fills the red channel", (litPixel ushr 16) and 0xFF > 200)
        assertTrue("tint clears the blue channel", (litPixel and 0xFF) < 40)
    }

    // ------------------------------------------------------------------ local renderers

    private fun renderAligned(from: androidx.compose.ui.graphics.vector.ImageVector, alignment: Alignment, widthPx: Int, heightPx: Int): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(widthPx.dp, heightPx.dp)) {
                MorphVector(
                    from = from,
                    to = from,
                    progress = 1f,
                    width = widthPx.dp,
                    height = heightPx.dp,
                    alignment = alignment,
                )
            }
        }
        return capture(view, widthPx, heightPx)
    }

    private fun renderTinted(from: androidx.compose.ui.graphics.vector.ImageVector, tint: Color): Bitmap {
        val side = harness.framePx
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(side.dp)) {
                MorphVector(
                    from = from,
                    to = from,
                    progress = 1f,
                    tintFrom = tint,
                    width = side.dp,
                    height = side.dp,
                )
            }
        }
        return capture(view, side, side)
    }

    private fun capture(view: View, widthPx: Int, heightPx: Int): Bitmap {
        activity.setContentView(view)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        repeat(2) { shadowOf(Looper.getMainLooper()).idle() }
        return bitmap
    }

    private fun firstLitPixel(bitmap: Bitmap): Int {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel != 0) return pixel
            }
        }
        return 0
    }
}
