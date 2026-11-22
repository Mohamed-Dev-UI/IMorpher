package com.imorpher.vectormorph.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Raster compositing: the per-frame GPU-submit-ready work of the content-transition layer
 * (`MorphPainter`/`MorphImage`/`MorphFrames`/`MorphContent` endpoint compositing).
 *
 * The transition layer composites opaque endpoints as two layer draws over the shared
 * progress with per-endpoint alpha/scale/translation/rotation, directional clips, and
 * feathered wipes (`saveLayer` + DstIn mask). Its draw scope lives behind composables, so
 * this benchmark measures the public-primitive equivalent on a software canvas at two sizes:
 * two drawImage commands with per-endpoint alpha and transform (crossfade/transform), and
 * the clipped wipe with its saveLayer round-trip. Composition overheads are NOT included;
 * treat numbers as regression baselines for the compositing hot path, taken on an emulator
 * (see docs/BENCHMARKS.md).
 */
@RunWith(AndroidJUnit4::class)
class RasterCompositeBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val bitmap96From = solidBitmap(96, Color.rgb(83, 109, 254))
    private val bitmap96To = solidBitmap(96, Color.rgb(0, 200, 83))
    private val bitmap240From = solidBitmap(240, Color.rgb(83, 109, 254))
    private val bitmap240To = solidBitmap(240, Color.rgb(0, 200, 83))

    private val canvas96 = AndroidCanvas(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888))
    private val canvas240 = AndroidCanvas(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888))

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val transformMatrix = Matrix()
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    private fun solidBitmap(size: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(color)
        return bitmap
    }

    /** Crossfade of two endpoints at progress, each transformed by the layer effects. */
    private fun paintCrossfadeFrame(canvas: AndroidCanvas, from: Bitmap, to: Bitmap, progress: Float, size: Int) {
        canvas.drawColor(Color.WHITE)
        // From endpoint: fade out; scale grows slightly to the to sizes (endpoint scale 1.0).
        val fromAlpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
        val toAlpha = (progress * 255f).toInt().coerceIn(0, 255)
        val half = size / 2f
        // From endpoint: fades out, translated + rotated like the layer's TRANSFORM effects.
        transformMatrix.reset()
        transformMatrix.setTranslate(0f, -half * 0.1f * (1f - progress))
        transformMatrix.postRotate(180f * (1f - progress), half, half)
        paint.setAlpha(fromAlpha)
        canvas.drawBitmap(from, transformMatrix, paint)
        // To endpoint: fades in.
        transformMatrix.reset()
        transformMatrix.setTranslate(0f, half * 0.1f * progress)
        transformMatrix.postRotate(180f * progress, half, half)
        paint.setAlpha(toAlpha)
        canvas.drawBitmap(to, transformMatrix, paint)
        paint.setAlpha(255)
    }

    @Test
    fun crossfade96dp() {
        benchmarkRule.measureRepeated {
            paintCrossfadeFrame(canvas96, bitmap96From, bitmap96To, 0.5f, 96)
        }
    }

    @Test
    fun crossfade240dp() {
        benchmarkRule.measureRepeated {
            paintCrossfadeFrame(canvas240, bitmap240From, bitmap240To, 0.5f, 240)
        }
    }

    /** Directional wipe at 240dp: the to endpoint draws into a saveLayer mask-mul, the from below. */
    @Test
    fun wipeWithEffects240dp() {
        val width = 240f
        val height = 240f
        val layerBounds = android.graphics.RectF(0f, 0f, width, height)
        val edge = width * 0.75f // wipe edge sweep position at progress 0.75
        benchmarkRule.measureRepeated {
            canvas240.drawColor(Color.WHITE)
            // Wipe: the from endpoint is fully drawn below; the to endpoint draws clipped by
            // the moving edge (hard wipe = clipRect; feathered would add the saveLayer/DstIn
            // pass, mirrored here by the kill switch: a saveLayer + mask-level alpha blend).
            paint.setAlpha(255)
            canvas240.drawBitmap(bitmap240From, 0f, 0f, paint)
            canvas240.saveLayer(layerBounds, layerPaint)
            canvas240.clipRect(0f, 0f, edge, height)
            canvas240.drawBitmap(bitmap240To, 0f, 0f, paint)
            canvas240.restore()
        }
    }
}
