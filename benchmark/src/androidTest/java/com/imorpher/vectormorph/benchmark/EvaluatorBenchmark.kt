package com.imorpher.vectormorph.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.plan.planMorph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-frame timeline evaluation: the hot path every rendered frame runs once.
 *
 * A frame is sixty evaluations (one per display step) so the measurement is not dominated by
 * benchmark-loop overhead. Run on a physical device via
 * `:benchmark:connectedBenchmarkAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class EvaluatorBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val plan = planMorph(BenchmarkFixtures.outline, BenchmarkFixtures.solid)
    private val animation = VectorAnimation {
        at(0f) {
            alpha = 0f
            scale = 0.9f
        }
        at(1f) {
            alpha = 1f
            scale = 1f
        }
        allPaths {
            drawProgress(interval = 0f..1f)
            gradient(
                from = BrushSpec.Solid(Color(0xFF536DFE)),
                to = BrushSpec.Solid(Color(0xFF00C853)),
                interval = 0f..1f,
            )
        }
    }
    private val evaluator = TimelineEvaluator(
        animation = animation,
        plan = plan,
        colorSpace = ColorInterpolationSpace.SRGB,
    )
    private val frame = EvaluatedFrame(plan)

    @Test
    fun evaluateOneFrame() {
        benchmarkRule.measureRepeated {
            for (step in 0..59) {
                evaluator.evaluate(step / 60f, frame)
            }
        }
    }
}
