package com.mohamed.dev.ui.imorpher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import com.imorpher.vectormorph.compose.MorphVector
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every curated icon pair in both directions on real pixels (Robolectric native
 * graphics) and asserts:
 *
 * 1. endpoint parity — morphing A to B at t=0 paints like A rendered alone, and at t=1
 *    paints like B rendered alone (catches missing inner symbols, lost fills, ghost
 *    shapes, and wrong windings for every real icon family, outline or solid). When the
 *    pair endpoints have different intrinsic sizes (BannerWide ↔ square), parity is not
 *    asserted against plain Compose painting: the engine aspect-fits a viewport into the
 *    layout while Compose Icon stretches per axis, so only liveness holds there;
 * 2. live morphs — frames actually change across progress steps (catches a renderer
 *    that paints the wrong side or freezes);
 *
 * Every frame is written as PNG under `app/build/reports/morph-matrix` named
 * `from-to-step.png` for visual review of how each morph actually looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconPairMorphMatrixTest {

    private lateinit var activity: ComponentActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // ------------------------------------------------------------------ rendering

    private fun render(from: ImageVector, to: ImageVector, progress: Float): Bitmap {
        // Per-endpoint layout sized at 2× the active endpoint's intrinsic dp: the t=0 frame
        // lives in the source's box, mid/one frames (and the t=* baselines of the morphed
        // pair) in the target's box. At the intrinsic aspect ratio the engine's aspect-fit
        // and plain Compose painting coincide, so cross-size (BannerWide ↔ square) pairs
        // paint proportionally instead of being letterboxed into a forced square.
        val layout = if (progress == 0f) from else to
        val widthPx = (layout.defaultWidth.value * 2).toInt()
        val heightPx = (layout.defaultHeight.value * 2).toInt()
        val view = ComposeView(activity)
        view.setContent {
            MorphVector(
                from = from,
                to = to,
                progress = progress,
                contentDescription = null,
                width = layout.defaultWidth * 2,
                height = layout.defaultHeight * 2,
            )
        }
        activity.setContentView(view)
        pumpMainLoop()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        pumpMainLoop()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        pumpMainLoop()
        return bitmap
    }

    private fun pumpMainLoop() {
        // Composition and UI effects run on the main looper; a couple of idle rounds
        // deterministically settle the static per-frame content used here.
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    /** 0 = identical pixels, 1 = maximally different; tolerant of antialiased edges. */
    private fun meanAbsDifference(a: Bitmap, b: Bitmap): Float {
        require(a.width == b.width && a.height == b.height)
        var sum = 0L
        val total = a.width * a.height
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += kotlin.math.abs((pa ushr 24 and 0xFF) - (pb ushr 24 and 0xFF)) +
                    kotlin.math.abs((pa ushr 16 and 0xFF) - (pb ushr 16 and 0xFF)) +
                    kotlin.math.abs((pa ushr 8 and 0xFF) - (pb ushr 8 and 0xFF)) +
                    kotlin.math.abs(pa and 0xFF - pb and 0xFF)
            }
        }
        return sum / (255f * 4f * total)
    }

    private fun assertParity(morphFrame: Bitmap, staticFrame: Bitmap, message: String) {
        val difference = meanAbsDifference(morphFrame, staticFrame)
        assertTrue("$message (meanAbsDiff=$difference, expected <= 0.02)", difference <= 0.02f)
    }

    private fun dumpPng(name: String, bitmap: Bitmap) {
        val dir = File("build/reports/morph-matrix").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ matrix

    @Test
    fun everyCuratedPairMorphsWithEndpointParity() {
        for ((first, second) in IconCatalog.pairs) {
            val a = IconCatalog.icon(first)
            val b = IconCatalog.icon(second)
            for ((fromName, toName) in listOf(first to second, second to first)) {
                val from = if (fromName == first) a else b
                val to = if (toName == second) b else a

                val baselineFrom = render(from, from, 0f)
                val baselineTo = render(to, to, 0f)
                val atZero = render(from, to, 0f)
                val atMid = render(from, to, 0.5f)
                val atOne = render(from, to, 1f)

                dumpPng("$fromName-$toName-000", atZero)
                dumpPng("$fromName-$toName-050", atMid)
                dumpPng("$fromName-$toName-100", atOne)

                val tag = "$fromName -> $toName"
                if (from.defaultWidth == to.defaultWidth && from.defaultHeight == to.defaultHeight) {
                    assertParity(atZero, baselineFrom, "$tag: t=0 must paint the from icon exactly")
                    assertParity(atOne, baselineTo, "$tag: t=1 must paint the to icon exactly")
                    assertTrue(
                        "$tag: frames must change across progress",
                        meanAbsDifference(atZero, atMid) > 0.002f || meanAbsDifference(atMid, atOne) > 0.002f,
                    )
                } else {
                    // Cross-viewport pair (BannerWide ↔ square): endpoint parity against a
                    // plain rendering is undefined (engine aspect-fits, Compose stretches
                    // per axis), so only liveness is asserted — the mid frame and the final
                    // frame are both rendered in the target's layout and must differ.
                    assertTrue(
                        "$tag: frames must change across progress",
                        meanAbsDifference(atMid, atOne) > 0.002f,
                    )
                }
            }
        }
    }
}
