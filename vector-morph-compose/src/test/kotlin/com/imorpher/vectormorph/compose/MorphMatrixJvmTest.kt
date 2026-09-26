package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController

/**
 * JVM pixel matrix: renders every morph mechanism with Robolectric's native graphics and
 * asserts per-frame coverage invariants. Every rendered frame is also written as PNG to
 * `build/reports/morph-matrix` so regressions can be inspected visually.
 *
 * Rendering strategy: the compose-test-rule host window has no surface under Robolectric,
 * so `captureToImage` cannot work; instead each frame is composed inside a real activity's
 * [ComposeView], which is measured, laid out, and drawn straight into a bitmap.
 *
 * Coverage fractions are computed from the synthetic geometry (24-unit viewport,
 * axis-aligned squares): the ring 4..20 minus hole 9..15 covers (16^2 - 6^2) / 24^2 ~ 0.38
 * of the icon area; bounds leave room for antialiased edges.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MorphMatrixJvmTest {

    private lateinit var activity: ComponentActivity
    private lateinit var activityController: ActivityController<ComponentActivity>

    private val fill = Color(0xFF12A4D9)
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val framePx = 48

    @Before
    fun setUp() {
        activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = activityController.get()
    }

    // ------------------------------------------------------------------ builders

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float, clockwise: Boolean): List<PathNode> {
        val corners = if (clockwise) {
            listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        } else {
            listOf(x0 to y0, x0 to y1, x1 to y1, x1 to y0)
        }
        return buildList {
            add(PathNode.MoveTo(corners[0].first, corners[0].second))
            corners.drop(1).forEach { (x, y) -> add(PathNode.LineTo(x, y)) }
            add(PathNode.Close)
        }
    }

    private fun solidOf(color: Color) = androidx.compose.ui.graphics.SolidColor(color)

    private fun vectorOf(name: String, pathData: List<PathNode>, color: Color): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(pathData = pathData, fill = solidOf(color), name = "body")
        return builder.build()
    }

    /** Single filled square: coverage 0.44. */
    private fun disc(name: String): ImageVector =
        vectorOf(name, square(4f, 4f, 20f, 20f, clockwise = true), fill)

    /** Outer square + inner symbol contour, opposite windings: ring, coverage 0.38. */
    private fun holed(name: String, color: Color = fill): ImageVector = vectorOf(
        name,
        square(4f, 4f, 20f, 20f, clockwise = true) +
            square(9f, 9f, 15f, 15f, clockwise = false),
        color,
    )

    /** Two symbols inside one fill, both holes (multi-symbol solid), coverage 0.36. */
    private fun twoHoles(name: String): ImageVector = vectorOf(
        name,
        square(4f, 4f, 20f, 20f, clockwise = true) +
            square(6f, 13f, 11f, 16f, clockwise = false) +
            square(13f, 8f, 18f, 11f, clockwise = false),
        fill,
    )

    /** Mirrored authoring convention: outer CCW, hole CW. */
    private fun mirroredHoled(name: String): ImageVector = vectorOf(
        name,
        square(4f, 4f, 20f, 20f, clockwise = false) +
            square(9f, 9f, 15f, 15f, clockwise = true),
        fill,
    )

    /** AdditemSolid-like: one stroked tail path plus one filled two-contour body path. */
    private fun strokePlusHoledBody(name: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(2f, 12f), PathNode.LineTo(8f, 12f),
            ),
            stroke = solidOf(fill),
            strokeLineWidth = 1.5f,
            name = "tail",
        )
        builder.addPath(
            pathData = square(8f, 6f, 22f, 18f, clockwise = true) +
                square(13f, 9f, 17f, 15f, clockwise = false),
            fill = solidOf(fill),
            name = "body",
        )
        return builder.build()
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Composes [from] morphing to [to] at [progress] inside a fresh [ComposeView], then
     * draws it into a bitmap: measure + layout + draw are synchronous, and Robolectric's
     * native graphics rasterizes the real Compose draw commands.
     */
    private fun renderFrame(
        from: ImageVector,
        to: ImageVector,
        progress: Float,
        tint: Color? = null,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            if (tint != null) {
                MorphVector(
                    from = from,
                    to = to,
                    progress = progress,
                    tintFrom = tint,
                    tintTo = tint,
                    contentDescription = null,
                    width = 48.dp,
                    height = 48.dp,
                )
            } else {
                MorphVector(
                    from = from,
                    to = to,
                    progress = progress,
                    contentDescription = null,
                    width = 48.dp,
                    height = 48.dp,
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
        // Composition and UI effects run on the main looper; a couple of idle rounds
        // deterministically settle the static per-frame content used here.
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    /** Coverage fraction of the grid that differs from the (transparent) background. */
    private fun coverage(bitmap: Bitmap): Float {
        var painted = 0
        val total = bitmap.width * bitmap.height
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) painted++
            }
        }
        return painted.toFloat() / total
    }

    private fun assertCoverage(bitmap: Bitmap, min: Float, max: Float, message: String) {
        val c = coverage(bitmap)
        assertTrue("$message (coverage=$c, expected $min..$max)", c in min..max)
    }

    private fun dumpPng(prefix: String, step: Float, bitmap: Bitmap) {
        val dir = File("build/reports/morph-matrix").apply { mkdirs() }
        val file = File(dir, "$prefix-${(step * 100).toInt().toString().padStart(3, '0')}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ matrix

    @Test
    fun sameStyleMorphKeepsSymbolHoleAtEveryStep() {
        val from = holed("hh-from")
        val to = holed("hh-to")
        steps.forEachIndexed { index, step ->
            val frame = renderFrame(from, to, step)
            dumpPng("same-style", step, frame)
            assertCoverage(frame, 0.33f, 0.43f, "same-style t=$step must stay a ring, not a disc")
        }
    }

    @Test
    fun crossStyleDiscToSolidKeepsHoleAtEndpoints() {
        val from = disc("ds-from")
        val to = holed("ds-to")
        steps.forEachIndexed { index, step ->
            val frame = renderFrame(from, to, step)
            dumpPng("disc-to-solid", step, frame)
            when (index) {
                0 -> assertCoverage(frame, 0.40f, 0.49f, "disc endpoint must be filled")
                steps.lastIndex -> assertCoverage(frame, 0.33f, 0.43f, "solid endpoint must keep the symbol hole")
                else -> assertCoverage(frame, 0.30f, 0.50f, "mid-morph must interpolate between disc and ring")
            }
        }
    }

    @Test
    fun crossStyleSolidToDiscFillsHoleByEnd() {
        val from = holed("sd-from")
        val to = disc("sd-to")
        steps.forEachIndexed { index, step ->
            val frame = renderFrame(from, to, step)
            dumpPng("solid-to-disc", step, frame)
            when (index) {
                0 -> assertCoverage(frame, 0.33f, 0.43f, "solid start must keep the symbol hole")
                steps.lastIndex -> assertCoverage(
                    frame, 0.40f, 0.49f,
                    "disc end must be fully filled; a stale carved hole here means the erase did not fade with its leg",
                )
                else -> assertCoverage(frame, 0.30f, 0.50f, "mid-morph must interpolate between ring and disc")
            }
        }
    }

    @Test
    fun multiSymbolSolidKeepsBothHolesAtEndpoint() {
        val from = disc("ms-from")
        val to = twoHoles("ms-to")
        listOf(0f, 0.5f, 1f).forEachIndexed { index, step ->
            val frame = renderFrame(from, to, step)
            dumpPng("multi-symbol", step, frame)
            if (index == 2) {
                assertCoverage(frame, 0.31f, 0.41f, "multi-symbol endpoint must keep both holes")
            }
        }
    }

    @Test
    fun mirroredConventionSolidKeepsHoleInBothDirections() {
        val forward = renderFrame(disc("mh-from"), mirroredHoled("mh-to"), 1f)
        dumpPng("mirrored-forward", 1f, forward)
        assertCoverage(forward, 0.33f, 0.43f, "mirrored-convention solid endpoint must keep the hole")

        val backward = renderFrame(mirroredHoled("mb-from"), disc("mb-to"), 1f)
        dumpPng("mirrored-backward", 1f, backward)
        assertCoverage(backward, 0.40f, 0.49f, "mirrored solid must collapse to a filled disc")
    }

    @Test
    fun tintedSameStyleMorphKeepsHoleAtEndpoint() {
        val tint = Color(0xFFE2574C)
        val frame = renderFrame(holed("th-from"), holed("th-to"), 1f, tint = tint)
        dumpPng("tinted", 1f, frame)
        assertCoverage(frame, 0.33f, 0.43f, "tinted solid endpoint must keep the hole")
    }

    @Test
    fun strokePlusHoledBodyMorphKeepsHoleAndStroke() {
        val frame = renderFrame(disc("sf-from"), strokePlusHoledBody("sf-to"), 1f)
        dumpPng("stroke-plus-fill", 1f, frame)
        // body (14x12 minus 4x6 hole = 144) + tail stroke (~9) over 24x24 = ~0.266;
        // a lost body hole would push coverage to ~0.31.
        assertCoverage(frame, 0.24f, 0.29f, "stroke+body endpoint must keep the body hole and gain the stroke")
    }
}
