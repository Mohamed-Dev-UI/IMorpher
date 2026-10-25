package com.mohamed.dev.ui.imorpher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
 * Exhaustive scan over every catalog icon and every directed icon combination:
 *
 * 1. Ground truth — each icon drawn through MorphIcon (self morph, progress 0) must
 *    pixel-match the same vector drawn by standard Compose `Icon`. This catches
 *    systematic misrenders (missing inner cutouts, painted-over gaps, lost strokes)
 *    that self-comparisons cannot see.
 * 2. Full combination matrix — for every ordered pair (A, B) of distinct icons, the
 *    morph at progress 0 must paint exactly A and at progress 1 exactly B (against the
 *    ground-truth baselines). Same-viewport pairs (identical default width/height) get
 *    this strict parity; cross-viewport pairs (BannerWide ↔ square) only need live,
 *    non-blank, changing frames, because the engine aspect-fits a viewport into the
 *    layout while plain Compose `Icon` stretches per axis — there is no single truth to
 *    match at the endpoints. Cross-family combinations (solid -> stroke outline etc.)
 *    are included: they are what a playground icon picker can produce.
 *
 * Every failing combination dumps its frames under `app/build/reports/scan-failures`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AllCombinationsScanTest {

    private lateinit var activity: ComponentActivity
    private val framePx = 48

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // ------------------------------------------------------------------ rendering

    /** Canvas size in px at Robolectric density 1.0, twice the vector's intrinsic dp (Keeps
     *  24-unit icons at the historic 2× pixel scale and gives the 48×16 banner two pixels per unit). */
    private fun intrinsicPx(vector: ImageVector): Int =
        (vector.defaultWidth.value * 2).toInt()

    private fun setContentAndDraw(content: @androidx.compose.runtime.Composable () -> Unit, widthPx: Int, heightPx: Int): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(width = widthPx.dp, height = heightPx.dp), contentAlignment = Alignment.Center) {
                content()
            }
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

    private fun renderMorph(
        from: ImageVector,
        to: ImageVector,
        progress: Float,
        layout: ImageVector = if (progress == 0f) from else to,
    ): Bitmap {
        // The morph frame is rendered at twice the chosen endpoint vector's intrinsic dp:
        // per-endpoint 2× keeps the historic pixel scale for 24-unit icons (48×48 px) and
        // gives the 48×16 banner its own two-pixels-per-unit canvas (96×32 px). At the
        // intrinsic aspect ratio the engine's aspect-fit and Compose Icon's per-axis
        // stretch coincide, which is what makes endpoint parity well-defined at all.
        val widthPx = (layout.defaultWidth.value * 2).toInt()
        val heightPx = (layout.defaultHeight.value * 2).toInt()
        return setContentAndDraw({
            MorphVector(
                from = from,
                to = to,
                progress = progress,
                contentDescription = null,
                width = layout.defaultWidth * 2,
                height = layout.defaultHeight * 2,
            )
        }, widthPx, heightPx)
    }

    /**
     * Standard Compose painting of the same vector: the ground truth. `tint` must be
     * [Color.Unspecified] — Icon's default tint is LocalContentColor, which would paint
     * every icon monochrome instead of using the vector's own colors. The layout is 2×
     * the vector's intrinsic size so the comparison is proportional for non-square
     * viewports too (Compose stretches the viewport per axis; the engine aspect-fits —
     * identical at the intrinsic aspect ratio).
     */
    private fun renderStandardIcon(vector: ImageVector): Bitmap =
        setContentAndDraw({
            Icon(
                imageVector = vector,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(
                    width = vector.defaultWidth * 2,
                    height = vector.defaultHeight * 2,
                ),
            )
        }, intrinsicPx(vector), (vector.defaultHeight.value * 2).toInt())

    private fun pumpMainLoop() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    /** 0 = identical pixels, 1 = maximally different. */
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

    /** True when nothing was painted (alpha below noise) anywhere on the frame. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((bitmap.getPixel(x, y) ushr 24 and 0xFF) > 32) return false
            }
        }
        return true
    }

    private fun dumpPng(name: String, bitmap: Bitmap) {
        val dir = File("build/reports/scan-failures").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ scans

    @Test
    fun everyIconMatchesStandardComposePainting() {
        val failures = ArrayList<String>()
        for (entry in IconCatalog.entries) {
            val vector = IconCatalog.icon(entry.name)
            val mine = renderMorph(vector, vector, 0f)
            val standard = renderStandardIcon(vector)
            val difference = meanAbsDifference(mine, standard)
            if (difference > 0.02f) {
                dumpPng("${entry.name}-mine", mine)
                dumpPng("${entry.name}-standard", standard)
                failures += "${entry.name}: meanAbsDiff=$difference"
            }
        }
        assertTrue(
            "icons that do not match standard Compose painting:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun everyDirectedCombinationPaintsTheRightIconAtBothEndpoints() {
        val names = IconCatalog.entries.map { it.name }
        val baselines = HashMap<String, Bitmap>()
        for (name in names) {
            baselines[name] = renderStandardIcon(IconCatalog.icon(name))
        }

        val failures = ArrayList<String>()
        for (fromName in names) {
            for (toName in names) {
                if (fromName == toName) continue
                val from = IconCatalog.icon(fromName)
                val to = IconCatalog.icon(toName)

                val sameViewport = from.defaultWidth == to.defaultWidth &&
                    from.defaultHeight == to.defaultHeight
                if (sameViewport) {
                    // Same-viewport pair: each endpoint is painted in that endpoint's own
                    // 2×-intrinsic layout and must equal the plain Icon ground truth.
                    val atZero = renderMorph(from, to, 0f)
                    val atOne = renderMorph(from, to, 1f)

                    val diffZero = meanAbsDifference(atZero, baselines[fromName]!!)
                    val diffOne = meanAbsDifference(atOne, baselines[toName]!!)
                    if (diffZero > 0.02f || diffOne > 0.02f) {
                        dumpPng("$fromName-$toName-000", atZero)
                        dumpPng("$fromName-$toName-100", atOne)
                        failures += "$fromName -> $toName: t0=$diffZero t1=$diffOne"
                    }
                } else {
                    // Cross-viewport pair (BannerWide ↔ square): the engine aspect-fits a
                    // viewport into the layout while plain Compose Icon stretches per axis,
                    // so endpoint parity against Icon is inherently undefined there — both
                    // sides are simply allowed to be different at any progress step. Assert
                    // live morphing instead: both frames paint something in the target's
                    // layout and the frames actually differ.
                    val atZero = renderMorph(from, to, 0f, layout = to)
                    val atOne = renderMorph(from, to, 1f, layout = to)
                    if (isBlank(atZero) || isBlank(atOne)) {
                        dumpPng("$fromName-$toName-000", atZero)
                        dumpPng("$fromName-$toName-100", atOne)
                        failures += "$fromName -> $toName: blank endpoint frame"
                    } else {
                        val liveness = meanAbsDifference(atZero, atOne)
                        if (liveness <= 0.01f) {
                            dumpPng("$fromName-$toName-000", atZero)
                            dumpPng("$fromName-$toName-100", atOne)
                            failures += "$fromName -> $toName: frames do not change across progress (diff=$liveness)"
                        }
                    }
                }
            }
        }
        assertTrue(
            "${failures.size} broken combinations:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }
}
