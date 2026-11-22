package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendered contract for `ContentScale` on vector entries: a square icon viewport mapped into a
 * 2:1 canvas letterboxes with transparent bands under `Fit` and fills edge to edge under `Crop`,
 * following Compose's own `ContentScale` contract. The omitted-parameter entry must match `Fit`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ContentScaleRenderTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(
            Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            framePx = WIDTH,
        )
    }

    /** 24x24 viewport fully covered by opaque red, so any band the renderer leaves empty shows. */
    private val fullSquare: ImageVector =
        ImageVector.Builder("full-square", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = listOf(
                    PathNode.MoveTo(0f, 0f),
                    PathNode.LineTo(24f, 0f),
                    PathNode.LineTo(24f, 24f),
                    PathNode.LineTo(0f, 24f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Red),
                name = "body",
            )
            .build()

    private fun Bitmap.alphaAt(x: Int, y: Int): Int = (getPixel(x, y) ushr 24) and 0xFF

    private fun render(contentScale: ContentScale): Bitmap = harness.renderContent(
        width = WIDTH,
        height = HEIGHT,
    ) {
        MorphVector(
            from = fullSquare,
            to = fullSquare,
            progress = 0f,
            contentDescription = null,
            width = WIDTH.dp,
            height = HEIGHT.dp,
            contentScale = contentScale,
        )
    }

    /** The entry called without `contentScale` at all — the default must behave as `Fit`. */
    private fun renderDefault(): Bitmap = harness.renderContent(
        width = WIDTH,
        height = HEIGHT,
    ) {
        MorphVector(
            from = fullSquare,
            to = fullSquare,
            progress = 0f,
            contentDescription = null,
            width = WIDTH.dp,
            height = HEIGHT.dp,
        )
    }

    @Test
    fun fitLetterboxesTheSquareViewportInA2To1Canvas() {
        val bitmap = render(ContentScale.Fit)
        assertEquals("left band stays empty", 0, bitmap.alphaAt(4, HEIGHT / 2))
        assertEquals("right band stays empty", 0, bitmap.alphaAt(WIDTH - 4, HEIGHT / 2))
        assertEquals("center is painted", 255, bitmap.alphaAt(WIDTH / 2, HEIGHT / 2))
    }

    @Test
    fun cropFillsThe2To1CanvasEdgeToEdge() {
        val bitmap = render(ContentScale.Crop)
        assertEquals("left edge is painted", 255, bitmap.alphaAt(4, HEIGHT / 2))
        assertEquals("right edge is painted", 255, bitmap.alphaAt(WIDTH - 4, HEIGHT / 2))
        assertEquals("center is painted", 255, bitmap.alphaAt(WIDTH / 2, HEIGHT / 2))
    }

    @Test
    fun omittedContentScaleMatchesFit() {
        val bitmap = renderDefault()
        assertEquals("left band stays empty", 0, bitmap.alphaAt(4, HEIGHT / 2))
        assertEquals("right band stays empty", 0, bitmap.alphaAt(WIDTH - 4, HEIGHT / 2))
        assertEquals("center is painted", 255, bitmap.alphaAt(WIDTH / 2, HEIGHT / 2))
    }

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 48
    }
}
