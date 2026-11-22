package com.imorpher.vectormorph.benchmark

import android.graphics.Bitmap
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.imorpher.vectormorph.compose.FrameSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live-frame source bookkeeping: the per-frame cost a host pays when feeding a changing
 * frame source into `MorphFrames` (`FrameSource.submit` bookkeeping and the snapshot-state
 * story that drives the composition). Latest-wins drops are the path a 60fps decoder hits
 * when a frame is submitted faster than the next draw composes.
 *
 * Run on a physical device via `:benchmark:connectedBenchmarkAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FrameSourceBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val frame: ImageBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val nextFrame: ImageBitmap = frame

    @Test
    fun submitAndDrop() {
        // No composition consumes the frames here, so every submit beyond the first counts
        // as a latest-wins drop — the exact decoder beats the renderer scenario.
        val source = FrameSource()
        var i = 0
        benchmarkRule.measureRepeated {
            source.submit(nextFrame, i.toLong())
            i++
        }
    }

    @Test
    fun markUnavailableCycle() {
        val source = FrameSource(frame)
        benchmarkRule.measureRepeated {
            source.markUnavailable()
            source.submit(nextFrame, 0L)
        }
    }
}
