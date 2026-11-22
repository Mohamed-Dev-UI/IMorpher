package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.TimingMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Recomposition contracts observed through one live composition: a recomposition that changes
 * only an evaluator-consumed or planning-consumed field must measurably change the rendered
 * frame. These probes reuse a single composition instead of building fresh ones, so a stale
 * `remember` scope (an evaluator or a plan kept across the change) would replay the exact same
 * draws and the frame delta would measure exactly 0.
 *
 * The probes run under `createComposeRule` because the Compose test scheduler drives
 * recomposition deterministically; a raw `ComposeView` plus manual looper pumping depends on
 * window-level frame-clock state that survives across suite positions and hangs stale, so the
 * identical contract written against the render harness passed in isolation but failed when
 * other test classes left the shared window machinery in the wrong state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecompositionContractTest {

    @get:Rule
    val composeRule = createAndroidComposeRule(ComponentActivity::class.java)

    private val frame = 96

    /** Diagonal pair that swaps corners between the morph endpoints. */
    private fun squareAt(x: Float, y: Float, size: Float = 8f) = listOf(
        PathNode.MoveTo(x, y),
        PathNode.LineTo(x + size, y),
        PathNode.LineTo(x + size, y + size),
        PathNode.LineTo(x, y + size),
        PathNode.Close,
    )

    private val swapFrom by lazy { listOf(squareAt(3f, 3f), squareAt(13f, 13f)) }
    private val swapTo by lazy { listOf(squareAt(13f, 3f), squareAt(3f, 13f)) }

    private fun filled(name: String, contours: List<List<PathNode>>): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                fill = SolidColor(Color(0xFF12A4D9)),
                name = "path-$index",
            )
        }
        return builder.build()
    }

    private fun rectanglePath(): Path = Path().apply { addRect(Rect(4f, 4f, 20f, 20f)) }

    private fun difference(a: Bitmap, b: Bitmap): Float = BitmapAsserts.meanAbsDifference(a, b)

    /** Horizontal pixel position of an authored frame-x coordinate at run density. */
    private fun px(x: Int): Int = (x * composeRule.density.density).toInt()

    /** Draws the host window's content into a density-scaled frame after draining. */
    private fun captureFrame(): Bitmap {
        composeRule.waitForIdle()
        val scale = composeRule.density.density
        val px = (frame * scale).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        composeRule.activity.window.decorView.draw(Canvas(bitmap))
        return bitmap
    }

    // ------------------------------------------------------------------ evaluator rebuild

    /**
     * Evaluator rebuild contract: `timing` (and `customDrawOrder`, its sibling in the
     * remember key) are consumed by `TimelineEvaluator` at construction, so a recomposition
     * that changes only those fields (same PlanningKey — same plan instance) must rebuild the
     * evaluator, not reuse the stale one. Signal calibration (UNIFORM vs BY_PATH at p=0.5,
     * `fillAlpha(0f,1f)`): BY_PATH clips rank 0 to 1f (fully lit) and rank 1 to 0f (fully
     * dark) while UNIFORM holds both at 0.5 — measured deltas run ~0.04 mean-abs pixel
     * difference, far above the 0.01 floor, and identical draws measure exactly 0.
     */
    @Test
    fun recomposingTimingRebuildsTheEvaluator() {
        val from = filled("from", swapFrom)
        val to = filled("to", swapTo)
        val animation = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { fillAlpha(0f, 1f, interval = 0f..1f) }
        }
        val configuration = mutableStateOf(
            MorphConfiguration(timing = TimingMode.UNIFORM, animation = animation),
        )
        composeRule.setContent {
            Box(Modifier.size(frame.dp)) {
                MorphVector(
                    from = from,
                    to = to,
                    progress = 0.5f,
                    configuration = configuration.value,
                    contentDescription = null,
                    width = frame.dp,
                    height = frame.dp,
                )
            }
        }
        val uniform = captureFrame()

        // Timing-only recomposition (planning key unchanged): path 0 saturates, path 1 vanishes.
        composeRule.runOnIdle {
            configuration.value = MorphConfiguration(timing = TimingMode.BY_PATH, animation = animation)
        }
        composeRule.waitForIdle()
        val byPath = captureFrame()
        val timingDelta = difference(uniform, byPath)
        assertTrue(
            "timing-only recomposition must rebuild the evaluator (stale would be exactly 0; delta=$timingDelta)",
            timingDelta > 0.01f,
        )
    }

    // ------------------------------------------------------------------ shape re-resolve

    @Test
    fun changingEndpointPaintRebuildsThePlan() {
        val paint = mutableStateOf(MorphPaint(fill = SolidColor(Color.Red)))
        composeRule.setContent {
            Box(Modifier.size(frame.dp)) {
                MorphPath(
                    from = rectanglePath(),
                    to = rectanglePath(),
                    progress = 1f,
                    paintFrom = paint.value,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                    width = frame.dp,
                    height = frame.dp,
                )
            }
        }
        val red = captureFrame()
        assertTrue(
            "start paint is red ${red.describe(px(48), px(48))}",
            red.redAt(px(48), px(48)) > 200 && red.blueAt(px(48), px(48)) < 60,
        )

        composeRule.runOnIdle { paint.value = MorphPaint(fill = SolidColor(Color.Blue)) }
        composeRule.waitForIdle()
        val blue = captureFrame()
        assertTrue(
            "new paint is blue ${blue.describe(px(48), px(48))}",
            blue.blueAt(px(48), px(48)) > 200 && blue.redAt(px(48), px(48)) < 60,
        )
    }

    @Test
    fun shapeReResolvesWhenLayoutDirectionChanges() {
        val halfShape = object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline = if (layoutDirection == LayoutDirection.Ltr) {
                Outline.Rectangle(Rect(0f, 0f, size.width / 2f, size.height))
            } else {
                Outline.Rectangle(Rect(size.width / 2f, 0f, size.width, size.height))
            }
        }
        val ltr = mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (ltr.value) LayoutDirection.Ltr else LayoutDirection.Rtl,
            ) {
                Box(Modifier.size(frame.dp)) {
                    MorphShape(
                        from = halfShape,
                        to = halfShape,
                        progress = 1f,
                        fillFrom = Color.Black,
                        width = frame.dp,
                        height = frame.dp,
                    )
                }
            }
        }
        val left = captureFrame()
        assertTrue("LTR covers the left half ${left.describe(px(24), px(48))}", left.redAt(px(24), px(48)) < 50)
        assertTrue(
            "LTR leaves the right half empty ${left.describe(px(72), px(48))}",
            left.redAt(px(72), px(48)) > 200 && left.blueAt(px(72), px(48)) > 200,
        )

        composeRule.runOnIdle { ltr.value = false }
        composeRule.waitForIdle()
        val flipped = captureFrame()
        assertTrue("RTL leaves the left half empty ${flipped.describe(px(24), px(48))}", flipped.redAt(px(24), px(48)) > 200)
        assertTrue("RTL covers the right half ${flipped.describe(px(72), px(48))}", flipped.redAt(px(72), px(48)) < 50)
    }

    private fun Bitmap.alphaAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 24) and 0xFF
    private fun Bitmap.redAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 16) and 0xFF
    private fun Bitmap.blueAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFF

    private fun Bitmap.describe(x: Int, y: Int): String =
        "($x,$y)=a${alphaAt(x, y)} r${redAt(x, y)} b${blueAt(x, y)}"
}
