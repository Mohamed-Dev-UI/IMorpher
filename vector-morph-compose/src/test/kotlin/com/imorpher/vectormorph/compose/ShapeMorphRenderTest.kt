package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.geometry.PathGeometry
import com.imorpher.vectormorph.core.geometry.bounds
import com.imorpher.vectormorph.core.geometry.signedArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Rendered contract for the Compose `Shape`/`Path` geometry adapter: endpoints reproduce their
 * source outlines, fills interpolate, explicit paths map through their declared viewport,
 * multi-contour paths keep holes, and geometry re-resolves when layout size or direction changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShapeMorphRenderTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(
            Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            framePx = FRAME,
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun Bitmap.alphaAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 24) and 0xFF
    private fun Bitmap.redAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 16) and 0xFF
    private fun Bitmap.greenAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 8) and 0xFF
    private fun Bitmap.blueAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFF

    private fun Bitmap.describe(x: Int, y: Int): String =
        "($x,$y)=a${alphaAt(x, y)} r${redAt(x, y)} g${greenAt(x, y)} b${blueAt(x, y)}"

    private fun renderShape(progress: Float): Bitmap = harness.renderContent {
        MorphShape(
            from = RectangleShape,
            to = CircleShape,
            progress = progress,
            fillFrom = Color.Red,
            fillTo = Color.Blue,
            width = FRAME.dp,
            height = FRAME.dp,
        )
    }

    private fun renderPath(progress: Float): Bitmap = harness.renderContent {
        MorphPath(
            from = rectanglePath(),
            to = ovalPath(),
            progress = progress,
            fillFrom = Color.Red,
            fillTo = Color.Blue,
            viewportWidth = 24f,
            viewportHeight = 24f,
            width = FRAME.dp,
            height = FRAME.dp,
        )
    }

    private fun rectanglePath(): Path = Path().apply { addRect(Rect(4f, 4f, 20f, 20f)) }

    private fun ovalPath(): Path = Path().apply { addOval(Rect(4f, 4f, 20f, 20f)) }

    // ------------------------------------------------------------------ endpoints + fills

    @Test
    fun shapeEndpointsReproduceTheirOutlinesAndFills() {
        val start = renderShape(0f)
        assertEquals("start corner ${start.describe(4, 4)}", 255, start.alphaAt(4, 4))
        assertEquals("start center ${start.describe(48, 48)}", 255, start.redAt(48, 48))
        assertEquals("start center ${start.describe(48, 48)}", 0, start.blueAt(48, 48))

        val end = renderShape(1f)
        assertEquals("end corner ${end.describe(4, 4)}", 0, end.alphaAt(4, 4))
        assertEquals("end center ${end.describe(48, 48)}", 255, end.blueAt(48, 48))
        assertEquals("end center ${end.describe(48, 48)}", 0, end.redAt(48, 48))

        val middle = renderShape(0.5f)
        assertTrue("midpoint corner must be partially morphed: ${middle.describe(4, 4)}", middle.alphaAt(4, 4) < 255)
        // Two complementary fill legs (from at 1-t, to at t) overlap at the midpoint, so the
        // opaque endpoints composite to 0.75 alpha (192) while sharing the pixel.
        assertTrue("midpoint center ${middle.describe(48, 48)}", middle.alphaAt(48, 48) >= 128)
        assertTrue("midpoint fill interpolates red→blue: ${middle.describe(48, 48)}", middle.redAt(48, 48) in 60..200)
        assertTrue("midpoint fill interpolates red→blue: ${middle.describe(48, 48)}", middle.blueAt(48, 48) in 60..200)
    }

    @Test
    fun explicitPathsMapThroughTheirDeclaredViewport() {
        val start = renderPath(0f)
        // 24-unit viewport at 96px → ×4. Rect (4..20) covers 16..80.
        assertEquals("start margin ${start.describe(12, 12)}", 0, start.alphaAt(12, 12))
        assertEquals("start fill ${start.describe(20, 20)}", 255, start.alphaAt(20, 20))
        assertEquals("start fill ${start.describe(20, 20)}", 255, start.redAt(20, 20))

        val end = renderPath(1f)
        assertEquals("end corner ${end.describe(20, 20)}", 0, end.alphaAt(20, 20))
        assertEquals("end center ${end.describe(48, 48)}", 255, end.blueAt(48, 48))
        assertEquals("end margin ${end.describe(12, 12)}", 0, end.alphaAt(12, 12))
    }

    // ------------------------------------------------------------------ brushes + strokes

    @Test
    fun shapeGradientFillUsesTheLayoutPixelViewport() {
        val paint = MorphPaint(
            fill = Brush.linearGradient(
                colors = listOf(Color.Red, Color.Blue),
                start = Offset(0f, 0f),
                end = Offset(FRAME.toFloat(), 0f),
            ),
        )
        val frame = harness.renderContent {
            MorphShape(
                from = RectangleShape,
                to = RectangleShape,
                progress = 0f,
                paintFrom = paint,
                width = FRAME.dp,
                height = FRAME.dp,
            )
        }

        assertTrue("left edge ${frame.describe(4, 48)}", frame.redAt(4, 48) > 200 && frame.blueAt(4, 48) < 60)
        assertTrue("right edge ${frame.describe(92, 48)}", frame.blueAt(92, 48) > 200 && frame.redAt(92, 48) < 60)
    }

    @Test
    fun pathGradientAndStrokeUseTheAuthoredViewport() {
        val paint = MorphPaint(
            fill = Brush.linearGradient(
                colors = listOf(Color.Red, Color.Blue),
                start = Offset(0f, 0f),
                end = Offset(24f, 0f),
            ),
            stroke = SolidColor(Color.Green),
            strokeWidth = 8.dp,
        )
        val frame = harness.renderContent {
            MorphPath(
                from = rectanglePath(),
                to = rectanglePath(),
                progress = 0f,
                paintFrom = paint,
                viewportWidth = 24f,
                viewportHeight = 24f,
                width = FRAME.dp,
                height = FRAME.dp,
            )
        }

        // The 24-unit viewport renders at x4. The 8dp stroke is 2 authored units wide, so it
        // hugs x=16; a mistakenly unscaled 8-unit width would still paint x=24 green.
        assertTrue("stroke ${frame.describe(16, 48)}", frame.greenAt(16, 48) > 180)
        assertTrue("stroke must stop before x=24 ${frame.describe(24, 48)}", frame.greenAt(24, 48) < 40)
        assertTrue(
            "left of the gradient ${frame.describe(24, 48)}",
            frame.redAt(24, 48) > 180 && frame.blueAt(24, 48) < 80,
        )
        assertTrue("right of the gradient ${frame.describe(70, 48)}", frame.blueAt(70, 48) > 150)
    }

    @Test
    fun strokeOnlyPaintLeavesTheInteriorEmpty() {
        val paint = MorphPaint(
            fill = null,
            stroke = SolidColor(Color.Red),
            strokeWidth = 8.dp,
        )
        val frame = harness.renderContent {
            MorphShape(
                from = RectangleShape,
                to = RectangleShape,
                progress = 0f,
                paintFrom = paint,
                width = FRAME.dp,
                height = FRAME.dp,
            )
        }

        assertEquals("stroke on the top edge ${frame.describe(48, 2)}", 255, frame.alphaAt(48, 2))
        assertEquals("unfilled interior ${frame.describe(48, 48)}", 0, frame.alphaAt(48, 48))
    }

    // ------------------------------------------------------------------ path sampling

    @Test
    fun pathSamplingPreservesClosednessDirectionAndHoles() {
        val outline = Path().apply {
            // Outer clockwise in Y-down screen space.
            moveTo(0f, 0f)
            lineTo(20f, 0f)
            lineTo(20f, 20f)
            lineTo(0f, 20f)
            close()
            // Hole counter-clockwise.
            moveTo(5f, 5f)
            lineTo(5f, 15f)
            lineTo(15f, 15f)
            lineTo(15f, 5f)
            close()
        }
        val result = PathGeometry.contours(outline)

        assertEquals(2, result.contours.size)
        assertEquals(0, result.openContourCount)
        assertTrue(result.warnings.isEmpty())
        val outer = result.contours[0]
        val hole = result.contours[1]
        assertTrue(outer.closed)
        assertTrue(hole.closed)
        val outerArea = signedArea(outer)
        val holeArea = signedArea(hole)
        assertTrue(
            "outer and hole must keep opposite winding (outer=$outerArea hole=$holeArea)",
            outerArea * holeArea < 0f,
        )
        assertEquals(20f, bounds(outer)[2], 0f)
        assertEquals(15f, bounds(hole)[2], 0f)
    }

    @Test
    fun openPathIsReportedAndKeptOpen() {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(10f, 0f)
            lineTo(10f, 10f)
        }
        val result = PathGeometry.contours(path)

        assertEquals(1, result.contours.size)
        assertEquals(1, result.openContourCount)
        assertTrue(!result.contours.single().closed)
    }

    @Test
    fun emptyPathReportsNoGeometry() {
        val result = PathGeometry.contours(Path())

        assertTrue(result.isEmpty)
        assertTrue(result.warnings.single().contains("no measurable contours"))
    }

    // ------------------------------------------------------------------ re-evaluation

    @Test
    fun shapeReResolvesWhenLayoutSizeChanges() {
        var size by mutableStateOf(48.dp)
        val view = ComposeView(harness.activity)
        view.setContent {
            Box(Modifier.size(FRAME.dp)) {
                MorphShape(
                    from = RoundedCornerShape(20.dp),
                    to = RoundedCornerShape(20.dp),
                    progress = 1f,
                    fillFrom = Color.Black,
                    width = size,
                    height = size,
                )
            }
        }
        harness.activity.setContentView(view)
        val small = capture(view)
        assertEquals("small rounded rect fills its corner", 255, small.alphaAt(10, 10))

        size = 96.dp
        harness.pumpMainLoop()
        val large = capture(view)
        // A stale 48px plan would scale its 20px radius to 40px and lose this pixel.
        assertEquals("large rounded rect keeps its 20px (not scaled) radius", 255, large.alphaAt(10, 10))
    }

    /** Measures, lays out, and draws [view] after flushing Compose's work. */
    private fun capture(view: View): Bitmap {
        harness.pumpMainLoop()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(FRAME, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(FRAME, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, FRAME, FRAME)
        harness.pumpMainLoop()
        val bitmap = Bitmap.createBitmap(FRAME, FRAME, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        harness.pumpMainLoop()
        return bitmap
    }

    private companion object {
        const val FRAME = 96
    }
}
