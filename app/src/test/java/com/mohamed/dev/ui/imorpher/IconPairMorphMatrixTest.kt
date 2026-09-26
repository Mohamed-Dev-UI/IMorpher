package com.mohamed.dev.ui.imorpher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
 *    shapes, and wrong windings for every real icon family, outline or solid);
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

    private val framePx = 48

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // ------------------------------------------------------------------ rendering

    private fun render(from: ImageVector, to: ImageVector, progress: Float): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            MorphVector(
                from = from,
                to = to,
                progress = progress,
                contentDescription = null,
                width = 48.dp,
                height = 48.dp,
            )
        }
        activity.setContentView(view)
        pumpMainLoop()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, framePx, framePx)
        pumpMainLoop()
        val bitmap = Bitmap.createBitmap(framePx, framePx, Bitmap.Config.ARGB_8888)
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
                assertParity(atZero, baselineFrom, "$tag: t=0 must paint the from icon exactly")
                assertParity(atOne, baselineTo, "$tag: t=1 must paint the to icon exactly")
                assertTrue(
                    "$tag: frames must change across progress",
                    meanAbsDifference(atZero, atMid) > 0.002f || meanAbsDifference(atMid, atOne) > 0.002f,
                )
            }
        }
    }
}
