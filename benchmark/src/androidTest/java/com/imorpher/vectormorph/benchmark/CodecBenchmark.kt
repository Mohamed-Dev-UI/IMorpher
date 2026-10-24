package com.imorpher.vectormorph.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimationCodec
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Encoding and decoding a realistic timeline: the cost paid when animations are shipped as
 * prebuilt blobs instead of being compiled from the DSL on the device.
 *
 * Run with `:benchmark:connectedBenchmarkAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class CodecBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val animation = VectorAnimation {
        at(0f) {
            alpha = 0f
            scale = 0.92f
        }
        at(1f) {
            alpha = 1f
            scale = 1f
            fillColor = Color(0xFF00C853)
        }
        path("frame") {
            drawProgress(mode = DrawMode.FORWARD, direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT)
            fill(mode = FillMode.DIRECTIONAL, interval = 0.3f..1f)
            gradient(
                from = BrushSpec.Solid(Color(0xFF536DFE)),
                to = BrushSpec.Linear(
                    colors = listOf(Color(0xFF536DFE), Color(0xFF00C853)),
                    stops = null,
                    startX = 0f, startY = 0f, endX = 24f, endY = 24f,
                ),
            )
        }
        path("core") {
            drawProgress(interval = 0.2f..0.9f)
            strokeWidth(from = 1f, to = 2f)
        }
    }

    private val encoded: ByteArray = VectorAnimationCodec.encode(animation)

    @Test
    fun encode() {
        benchmarkRule.measureRepeated {
            VectorAnimationCodec.encode(animation)
        }
    }

    @Test
    fun decode() {
        benchmarkRule.measureRepeated {
            VectorAnimationCodec.decode(encoded)
        }
    }
}
