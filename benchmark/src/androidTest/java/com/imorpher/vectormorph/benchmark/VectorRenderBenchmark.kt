package com.imorpher.vectormorph.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Shader
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imorpher.vectormorph.core.plan.planMorph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vector-rendering paint step: the per-frame work a rendered morph frame performs after
 * timeline evaluation (see [EvaluatorBenchmark]).
 *
 * The library's draw surface lives behind Compose composables (`MorphIcon`/`MorphVector`/…),
 * so this benchmark measures the equivalent public-primitive pipeline on a software canvas:
 * interpolated-geometry lerping into reusable `Path` objects, per-frame combined fill leg
 * assembly (`FillPass.combinedMorphFillPath`: rewind + fill rule + one addPath per contour),
 * per-frame animated brush rebuild, and the final `drawPath` commands. On Android Compose's
 * `drawPath` maps to exactly these android-graphics calls, so the minus-side is the DrawScope
 * wrapper and composition overhead only; treat numbers as regression baselines for the paint
 * hot path, taken on an emulator (see docs/BENCHMARKS.md).
 */
@RunWith(AndroidJUnit4::class)
class VectorRenderBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val plan = planMorph(BenchmarkFixtures.outline, BenchmarkFixtures.solid)

    /** Per-(pair, contour) lerped contour paths, mirroring `MorphRenderer.morphContourPath`. */
    private val contourPathCache = HashMap<Long, AndroidPath>()

    /** Cached combined fill legs per (pair, slot) — the renderer's `combinedFillPathCache`. */
    private val legCache = HashMap<Int, AndroidPath>()

    private val scratch = FloatArray(1024)
    private val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    private val canvas = AndroidCanvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val solidFrom = Color.argb(255, 83, 109, 254)
    private val solidTo = Color.argb(255, 0, 200, 83)

    /**
     * Lerps one contour pair at morphT into a cached path — identical arithmetic to the
     * renderer's `morphContourPath` (shared coords buffer, cubicTo per segment, closure that
     * follows the active side so open↔closed transitions never paint a ghost segment).
     */
    private fun morphContourPath(pairIndex: Long, contourIndex: Long, morphT: Float, from: FloatArray, to: FloatArray, fromClosed: Boolean, toClosed: Boolean): AndroidPath {
        val path = contourPathCache.getOrPut(pairIndex * 4096L + contourIndex) { AndroidPath() }
        path.rewind()
        val n = from.size
        val mt = 1f - morphT
        for (i in 0 until n) scratch[i] = from[i] * mt + to[i] * morphT
        if (n >= 8) {
            path.moveTo(scratch[0], scratch[1])
            var i = 0
            while (i + 8 <= n) {
                path.cubicTo(scratch[i + 2], scratch[i + 3], scratch[i + 4], scratch[i + 5], scratch[i + 6], scratch[i + 7])
                i += 8
            }
        }
        if (if (morphT < 0.5f) fromClosed else toClosed) path.close()
        return path
    }

    /** `FilPass.combinedMorphFillPath`: rewind, fill rule, one addPath per contour. */
    private fun combinedLeg(slotKey: Int, paths: List<AndroidPath>): AndroidPath {
        val leg = legCache.getOrPut(slotKey) { AndroidPath() }
        leg.rewind()
        leg.fillType = AndroidPath.FillType.EVEN_ODD
        paths.forEach { leg.addPath(it) }
        return leg
    }

    private fun legsOf(morphT: Float): List<Pair<AndroidPath, Int>> {
        val results = ArrayList<Pair<AndroidPath, Int>>(plan.pairs.size * 2)
        plan.pairs.forEach { pair ->
            val fromLegs = ArrayList<AndroidPath>(pair.contourPairs.size)
            val toLegs = ArrayList<AndroidPath>(pair.contourPairs.size)
            pair.contourPairs.forEachIndexed { ci, cp ->
                val contour = morphContourPath(pair.index.toLong(), ci.toLong(), morphT, cp.from.coords, cp.to.coords, cp.from.closed, cp.to.closed)
                fromLegs.add(contour)
                toLegs.add(contour)
            }
            results.add(combinedLeg(pair.index * 2, fromLegs) to ((1f - morphT) * 255).toInt())
            results.add(combinedLeg(pair.index * 2 + 1, toLegs) to (morphT * 255).toInt())
        }
        return results
    }

    /** Two complementary legs at (1 - t)/t alpha — `FillPass.drawMorph`'s crossfade contract. */
    private fun paintSolidMorphFrame(morphT: Float) {
        canvas.drawColor(Color.WHITE)
        legsOf(morphT).forEach { (leg, alpha) ->
            paint.shader = null
            paint.color = if (alpha > 127) solidTo else solidFrom
            paint.alpha = alpha
            canvas.drawPath(leg, paint)
        }
    }

    @Test
    fun paintSolidMorphFrame() {
        benchmarkRule.measureRepeated {
            paintSolidMorphFrame(0.5f)
        }
    }

    @Test
    fun paintAnimatedBrushMorphFrame() {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        benchmarkRule.measureRepeated {
            // The renderer rebuilds animated brush values from their immutable specs every
            // frame (MorphRenderer's animated-brush rebuild); mirror that rebuild cost.
            paint.shader = LinearGradient(
                0f, 0f, width, height,
                solidFrom, solidTo,
                Shader.TileMode.CLAMP,
            )
            canvas.drawColor(Color.WHITE)
            legsOf(0.5f).forEach { (leg, _) ->
                paint.alpha = 255
                canvas.drawPath(leg, paint)
            }
        }
    }
}
