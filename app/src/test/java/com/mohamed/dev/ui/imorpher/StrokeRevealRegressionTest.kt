package com.mohamed.dev.ui.imorpher

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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.VectorAnimationDefinition
import com.imorpher.vectormorph.compose.VectorAnimator
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.mohamed.dev.ui.imorpher.icons.AdditemSolid
import com.mohamed.dev.ui.imorpher.icons.ArrowUp1
import com.mohamed.dev.ui.imorpher.icons.IMorpherIcons
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StrokeRevealRegressionTest {

    private lateinit var activity: ComponentActivity
    private val framePx = 96

    private fun render(progress: Float, colorTrack: Boolean, name: String): Bitmap {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        val animation = VectorAnimationSpec {
            allPaths {
                strokeReveal(interval = 0f..1f)
                if (colorTrack) {
                    color(from = Color(0xFF536DFE), to = Color(0xFF00BCD4))
                }
            }
        }
        view.setContent {
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                VectorAnimator(
                    animation = VectorAnimationDefinition(IMorpherIcons.ArrowUp1, animation),
                    progress = progress,
                    width = 96.dp,
                    height = 96.dp,
                )
            }
        }
        activity.setContentView(view)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, framePx, framePx)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        val bitmap = Bitmap.createBitmap(framePx, framePx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/reports/reveal-diagnostic").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** Is the region between the chevron's feet (bottom middle) painted? */
    private fun bottomMiddlePainted(bitmap: Bitmap): Boolean {
        var painted = 0
        val y0 = (framePx * 0.72f).toInt()
        for (x in (framePx * 0.35f).toInt()..(framePx * 0.65f).toInt()) {
            if (bitmap.getPixel(x, y0) != 0) painted++
        }
        return painted > 4
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

    @Test
    fun dumpRevealFrames() {
        val out = StringBuilder()
        for (progress in listOf(0.2f, 0.4f, 0.6f, 0.85f, 1f)) {
            val plain = render(progress, colorTrack = false, name = "stroke-$progress")
            out.appendLine("stroke p=$progress feetConnected=${bottomMiddlePainted(plain)}")
        }
        for (progress in listOf(0.2f, 0.6f, 1f)) {
            val colored = render(progress, colorTrack = true, name = "color-$progress")
            out.appendLine("color p=$progress feetConnected=${bottomMiddlePainted(colored)}")
        }
        File("build/reports/reveal-diagnostic/report.txt").writeText(out.toString())
    }

    @Test
    fun openContourRevealNeverConnectsItsEnds() {
        // Regression: when the reveal wraps the arc on an OPEN contour, the two legs were
        // continued into one contour, painting a straight connector between the chevron's
        // feet. Interim frames must stay a subset of the final shape: never more pixels.
        val final = paintedCount(render(1f, colorTrack = false, name = "regression-final"))
        for (progress in listOf(0.35f, 0.6f, 0.85f)) {
            val interim = paintedCount(render(progress, colorTrack = false, name = "regression-$progress"))
            org.junit.Assert.assertTrue(
                "interim p=$progress painted $interim > final $final: reveal invented geometry",
                interim <= final + 8,
            )
        }
    }

    @Test
    fun colorTrackDoesNotInventFillOnStrokeOnlyIcons() {
        // Regression: a FILL track applied to a stroke-only chevron filled its implicit
        // close (blue triangle, 10x more painted pixels). The color track must only
        // recolor existing paint: early frames must equal the stroke-only frame.
        val strokeOnly = paintedCount(render(0.2f, colorTrack = false, name = "fillguard-stroke"))
        val colored = paintedCount(render(0.2f, colorTrack = true, name = "fillguard-color"))
        org.junit.Assert.assertTrue(
            "color track invented fill: strokeOnly=$strokeOnly colored=$colored",
            colored <= strokeOnly + 8,
        )
    }

    @Test
    fun fillFollowsTheRevealOnFilledIcons() {
        // Regression: strokeReveal only gated the stroke pass, so a filled icon
        // (AdditemSolid) stayed fully painted at draw progress 0. The fill must follow
        // the reveal: ~invisible at 0, partially visible mid-draw, complete at 1.
        val atZero = paintedCount(renderFilled(0f, "filled-reveal-0"))
        val atHalf = paintedCount(renderFilled(0.5f, "filled-reveal-50"))
        val atOne = paintedCount(renderFilled(1f, "filled-reveal-100"))
        org.junit.Assert.assertTrue("filled icon must vanish at reveal start, painted=$atZero", atZero <= 8)
        org.junit.Assert.assertTrue("fill must grow with the reveal: $atZero -> $atHalf", atHalf > atZero + 40)
        org.junit.Assert.assertTrue("fill must complete: $atHalf -> $atOne", atOne > atHalf + 40)
    }

    /** Filled two-contour icon (AdditemSolid-like) under a stroke reveal. */
    private fun renderFilled(progress: Float, name: String): Bitmap {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        val animation = VectorAnimationSpec {
            allPaths { strokeReveal(interval = 0f..1f) }
        }
        view.setContent {
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                VectorAnimator(
                    animation = VectorAnimationDefinition(IMorpherIcons.AdditemSolid, animation),
                    progress = progress,
                    width = 96.dp,
                    height = 96.dp,
                )
            }
        }
        activity.setContentView(view)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, framePx, framePx)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        val bitmap = Bitmap.createBitmap(framePx, framePx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/reports/reveal-diagnostic").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
