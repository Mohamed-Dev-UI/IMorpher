package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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

/**
 * Line-morph rendering: stroked open paths morphing between geometries and drawing
 * themselves on. Lines are the shapes where invented geometry (stray connectors,
 * canvas-origin moveTo artifacts, chord shortcuts) is most visible, so every interim
 * frame must paint a subset of the final stroke — never extra geometry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MorphLineRenderingTest {

    private lateinit var activity: ComponentActivity
    private val framePx = 96

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // ------------------------------------------------------------------ builders

    private fun strokedVector(name: String, vararg contours: List<PathNode>): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                stroke = SolidColor(Color(0xFF536DFE)),
                strokeLineWidth = 2f,
                name = "stroke-$index",
            )
        }
        return builder.build()
    }

    private fun horizontalLine(y: Float = 12f) = listOf(
        PathNode.MoveTo(4f, y), PathNode.LineTo(20f, y),
    )

    private fun verticalLine(x: Float = 12f) = listOf(
        PathNode.MoveTo(x, 4f), PathNode.LineTo(x, 20f),
    )

    private fun diagonalLine() = listOf(
        PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 20f),
    )

    private fun polyline() = listOf(
        PathNode.MoveTo(4f, 18f), PathNode.LineTo(12f, 4f), PathNode.LineTo(20f, 18f),
    )

    private fun circle() = listOf(
        PathNode.MoveTo(12f, 3f),
        PathNode.ArcTo(9f, 9f, 0f, false, true, 21f, 12f),
        PathNode.ArcTo(9f, 9f, 0f, false, true, 12f, 21f),
        PathNode.ArcTo(9f, 9f, 0f, false, true, 3f, 12f),
        PathNode.ArcTo(9f, 9f, 0f, false, true, 12f, 3f),
        PathNode.Close,
    )

    // ------------------------------------------------------------------ rendering

    private fun renderMorph(
        from: ImageVector,
        to: ImageVector,
        progress: Float,
        colorTrack: Boolean = false,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(framePx.dp), contentAlignment = Alignment.Center) {
                MorphVector(
                    from = from,
                    to = to,
                    progress = progress,
                    contentDescription = null,
                    width = framePx.dp,
                    height = framePx.dp,
                )
            }
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
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun paintedCount(bitmap: Bitmap): Int {
        var painted = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) painted++
            }
        }
        return painted
    }

    private fun coverage(bitmap: Bitmap): Float = paintedCount(bitmap).toFloat() / (framePx * framePx)

    /** Painted pixels outside the union bounds of both endpoint strokes. */
    private fun strayPixels(bitmap: Bitmap, margin: Int = 6): Int {
        val scale = framePx / 24f
        val minX = ((4f - 1.5f) * scale).toInt() - margin
        val maxX = ((20f + 1.5f) * scale).toInt() + margin
        val minY = minX
        val maxY = maxX
        var strays = 0
        for (y in 0 until framePx) {
            for (x in 0 until framePx) {
                if (bitmap.getPixel(x, y) != 0 && (x < minX || x > maxX || y < minY || y > maxY)) strays++
            }
        }
        return strays
    }

    // ------------------------------------------------------------------ line → line morphs

    @Test
    fun lineToLineMorphStaysWithinBothEndpointsBounds() {
        val from = strokedVector("h", horizontalLine())
        val to = strokedVector("v", verticalLine())
        for (step in listOf(0.25f, 0.5f, 0.75f)) {
            val frame = renderMorph(from, to, step)
            assertEquals(
                "mid-morph must not paint outside the endpoint bounds (step=$step)",
                0,
                strayPixels(frame),
            )
            assertTrue(
                "mid-morph must paint a stroke (step=$step)",
                coverage(frame) > 0.005f,
            )
        }
    }

    @Test
    fun lineToPolylineMorphNeverInventsGeometry() {
        val from = strokedVector("line", horizontalLine())
        val to = strokedVector("poly", polyline())
        val final = paintedCount(renderMorph(from, to, 1f))
        for (step in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            val interim = paintedCount(renderMorph(from, to, step))
            assertTrue(
                "interim p=$step painted $interim > final $final: morph invented geometry",
                interim <= final + 12,
            )
        }
    }

    @Test
    fun openLineToClosedCircleMorphsBothEndpointsFaithfully() {
        val from = strokedVector("line", horizontalLine())
        val to = strokedVector("circle", circle())
        val atZero = paintedCount(renderMorph(from, to, 0f))
        val atOne = paintedCount(renderMorph(from, to, 1f))
        // the circle (2π·9 ≈ 56.5 units) covers far more pixels than the 16-unit line
        assertTrue("circle endpoint must dominate: $atOne vs $atZero", atOne > atZero * 3)
        for (step in listOf(0.3f, 0.5f, 0.7f)) {
            assertEquals(
                "mid-morph must not paint outside the endpoint bounds (step=$step)",
                0,
                strayPixels(renderMorph(from, to, step)),
            )
        }
    }

    @Test
    fun multiContourLineMorphPairsContourPositions() {
        // two parallel lines morphing to two parallel lines: mid-frame must show two
        // strokes (plus stroke width), not one merged blob or four.
        val from = strokedVector("two", horizontalLine(6f), horizontalLine(18f))
        val to = strokedVector("twoB", verticalLine(6f), verticalLine(18f))
        val mid = renderMorph(from, to, 0.5f)
        assertEquals(0, strayPixels(mid))
        val midCoverage = coverage(mid)
        // each stroke ≈ 16 units long, 2 wide → 2·32/576 ≈ 0.11
        assertTrue("two strokes expected mid-morph: $midCoverage", midCoverage in 0.04f..0.25f)
    }

    // ------------------------------------------------------------------ reveals

    @Test
    fun forwardRevealDrawsTheLineProgressively() {
        val vector = strokedVector("draw", horizontalLine())
        val animation = com.imorpher.vectormorph.core.animation.VectorAnimation {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths { strokeReveal(interval = 0f..1f) }
        }
        val atZero = paintedCount(renderedSingle(vector, animation, 0f))
        val atHalf = paintedCount(renderedSingle(vector, animation, 0.5f))
        val atOne = paintedCount(renderedSingle(vector, animation, 1f))
        assertTrue("reveal must start invisible: $atZero", atZero <= 8)
        assertTrue("reveal must grow: $atZero -> $atHalf", atHalf > atZero + 40)
        assertTrue("reveal must complete: $atHalf -> $atOne", atOne > atHalf + 20)
    }

    @Test
    fun reverseAndForwardRevealsDifferMidway() {
        val vector = strokedVector("dir", polyline())
        val forward = renderedSingle(vector, reveal(DrawModeOf("FORWARD"), start = null), 0.35f)
        val reverse = renderedSingle(vector, reveal(DrawModeOf("REVERSE"), start = null), 0.35f)
        val delta = BitmapAsserts.meanAbsDifference(forward, reverse)
        assertTrue(
            "FORWARD and REVERSE must paint different portions midway (delta=$delta)",
            delta > 0.01f,
        )
    }

    @Test
    fun reverseRevealOnClosedContourNeverDrawsAChord() {
        // Regression: REVERSE wrap legs were appended in the wrong order, so the second
        // leg continued from the first leg's END and painted a straight chord across
        // the circle's interior.
        val vector = strokedVector("circle", circle())
        val animation = com.imorpher.vectormorph.core.animation.VectorAnimation {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths {
                strokeReveal(
                    // CENTER anchors the pen halfway around the circle, so the wrap gap is
                    // at its widest right after the pen passes the start — exactly where a
                    // wrongly-ordered wrap leg would cut a chord through the interior.
                    start = com.imorpher.vectormorph.core.model.PathStart.CENTER,
                    direction = com.imorpher.vectormorph.core.model.DrawDirection.AUTO,
                    interval = 0f..1f,
                    mode = com.imorpher.vectormorph.core.model.DrawMode.REVERSE,
                )
            }
        }
        val final = paintedCount(renderedSingle(vector, animation, 1f))
        val scale = framePx / 24f
        val cx = (12f * scale).toInt()
        val cy = (12f * scale).toInt()
        for (step in listOf(0.55f, 0.6f)) {
            val interim = renderedSingle(vector, animation, step)
            assertTrue(
                "REVERSE interim p=$step painted ${paintedCount(interim)} > final $final: invented geometry",
                paintedCount(interim) <= final + 12,
            )
            // A wrap-leg chord connects two far-apart arc points and cuts straight
            // through the interior. The ring's inner edge sits at radius 8 (stroke 2 on
            // r=9), so everything inside radius 7 must stay unpainted mid-reveal; a chord
            // across a wide gap reaches deep into that disc.
            var interiorPainted = 0
            val interiorRadius = 7f * scale
            for (dy in -(interiorRadius.toInt())..interiorRadius.toInt()) {
                for (dx in -(interiorRadius.toInt())..interiorRadius.toInt()) {
                    if (dx * dx + dy * dy > interiorRadius * interiorRadius) continue
                    if (interim.getPixel(cx + dx, cy + dy) != 0) interiorPainted++
                }
            }
            assertTrue(
                "REVERSE interim p=$step painted $interiorPainted px inside the ring: chord drawn across the gap",
                interiorPainted == 0,
            )
        }
    }

    @Test
    fun centerOutRevealGrowsFromBothSidesOfTheAnchor() {
        val vector = strokedVector("draw", horizontalLine())
        val animation = com.imorpher.vectormorph.core.animation.VectorAnimation {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths {
                strokeReveal(
                    interval = 0f..1f,
                    mode = com.imorpher.vectormorph.core.model.DrawMode.CENTER_OUT,
                )
            }
        }
        val atQuarter = paintedCount(renderedSingle(vector, animation, 0.25f))
        val atHalf = paintedCount(renderedSingle(vector, animation, 0.5f))
        val atOne = paintedCount(renderedSingle(vector, animation, 1f))
        assertTrue(atOne > atHalf + 20)
        assertTrue(atHalf > atQuarter + 20)
    }

    @Test
    fun radialRevealOpensFromTheMiddleOfTheBounds() {
        val vector = strokedVector("draw", horizontalLine())
        val animation = com.imorpher.vectormorph.core.animation.VectorAnimation {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths {
                strokeReveal(
                    interval = 0f..1f,
                    mode = com.imorpher.vectormorph.core.model.DrawMode.RADIAL,
                )
            }
        }
        val atHalf = renderedSingle(vector, animation, 0.5f)
        // mid-reveal: only the central portion of the horizontal line is visible
        var leftVisible = false
        var rightVisible = false
        val scale = framePx / 24f
        val y = (12f * scale).toInt()
        for (x in 0 until (6f * scale).toInt()) if (atHalf.getPixel(x, y) != 0) leftVisible = true
        for (x in (18f * scale).toInt() until framePx) if (atHalf.getPixel(x, y) != 0) rightVisible = true
        assertTrue("radial reveal must clip the line ends at p=0.5", !leftVisible && !rightVisible)
    }

    // ------------------------------------------------------------------ helpers

    private fun DrawModeOf(name: String) = com.imorpher.vectormorph.core.model.DrawMode.valueOf(name)

    private fun reveal(
        mode: com.imorpher.vectormorph.core.model.DrawMode,
        start: com.imorpher.vectormorph.core.model.PathStart?,
    ) = com.imorpher.vectormorph.core.animation.VectorAnimation {
        defaultEasing(androidx.compose.animation.core.LinearEasing)
        allPaths {
            strokeReveal(
                start = start ?: com.imorpher.vectormorph.core.model.PathStart.START,
                interval = 0f..1f,
                mode = mode,
            )
        }
    }

    private fun renderedSingle(
        vector: ImageVector,
        animation: com.imorpher.vectormorph.core.animation.VectorAnimation,
        progress: Float,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(framePx.dp), contentAlignment = Alignment.Center) {
                VectorAnimator(
                    animation = VectorAnimationDefinition(vector, animation),
                    progress = progress,
                    contentDescription = null,
                    width = framePx.dp,
                    height = framePx.dp,
                )
            }
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
}
