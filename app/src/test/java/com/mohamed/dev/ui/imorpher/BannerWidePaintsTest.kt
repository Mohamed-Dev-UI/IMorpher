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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.MorphVector
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
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
 * Pins the `ViewportNormalization.FIT` contract on the wide demo pair
 * (48×16 banner → 24×24 square drawn at progress 1) in a 3:1 layout. The square target is
 * a thin 1.5-unit circle stroke, so the painted band is narrow in height terms; width is
 * what discriminates the modes:
 *
 * - `STRETCH` (default) scales the target axes independently, so the stretched circle
 *   spans the full layout width (only its round caps keep it off the exact edges).
 * - `FIT` scales uniformly by the smaller axis ratio (16/24) and centers: the painted
 *   band is the uniform-scaled circle, roughly a quarter of the layout width, sitting
 *   between equal sideways margins — not the full-bleed square letterbox of a 1:1 layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BannerWidePaintsTest {

    private lateinit var activity: ComponentActivity
    private val frameW = 96
    private val frameH = 32

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    private fun renderMorph(normalization: ViewportNormalization): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(width = frameW.dp, height = frameH.dp), contentAlignment = Alignment.Center) {
                MorphVector(
                    from = IconCatalog.icon("BannerWide"),
                    to = IconCatalog.icon("AddCircleOutline"),
                    progress = 1f,
                    configuration = MorphConfiguration(viewportNormalization = normalization),
                    contentDescription = null,
                    width = frameW.dp,
                    height = frameH.dp,
                )
            }
        }
        activity.setContentView(view)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(frameW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(frameH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, frameW, frameH)
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        val bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        return bitmap
    }

    /** Extents of non-transparent pixels (the painted morph). */
    private fun paintedBounds(bitmap: Bitmap): IntArray {
        var minX = bitmap.width
        var maxX = -1
        var minY = bitmap.height
        var maxY = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((bitmap.getPixel(x, y) ushr 24 and 0xFF) > 32) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        check(maxX >= 0) { "nothing painted" }
        return intArrayOf(minX, maxX, minY, maxY)
    }

    @Test
    fun stretchFillsTheWideLayoutWhileFitLetterboxesIt() {
        val stretched = paintedBounds(renderMorph(ViewportNormalization.STRETCH))
        val stretchWidth = stretched[1] - stretched[0] + 1
        // The target circle spans 19.5 of 24 viewport units; STRETCH maps it across the
        // full 96-px layout width (76 px) and the round line caps add a few px — measured
        // ~84 px. No height claim: the thin stroke only spans ~26 of the 32 layout rows.
        assertTrue(
            "STRETCH scaled the square across the full banner viewport width (got $stretchWidth px)",
            stretchWidth >= frameW * 0.75,
        )

        val fit = paintedBounds(renderMorph(ViewportNormalization.FIT))
        val fitWidth = fit[1] - fit[0] + 1
        // FIT scales uniformly by the smaller axis ratio (16/24): the circle lands at
        // ~0.667×2px/u ⇒ ~26 px on the 96-px layout — at least 15% of the width but at
        // most half of the stretched width.
        assertTrue(
            "FIT letterboxed the square to the uniform min-axis scale " +
                "(got $fitWidth px, stretched was $stretchWidth px)",
            fitWidth >= (frameW * 0.15).toInt() && fitWidth <= stretchWidth / 2,
        )
        // Uniform scaling centers the target inside the wide layout: the painted band sits
        // near the middle with equal side margins (~32 px each on the 96-px layout).
        val leftMargin = fit[0]
        val rightMargin = frameW - 1 - fit[1]
        assertTrue(
            "FIT centered the letterbox sideways (left=$leftMargin px, right=$rightMargin px)",
            leftMargin in 20..44 && rightMargin in 20..44 &&
                kotlin.math.abs(leftMargin - rightMargin) <= 8,
        )
    }
}
