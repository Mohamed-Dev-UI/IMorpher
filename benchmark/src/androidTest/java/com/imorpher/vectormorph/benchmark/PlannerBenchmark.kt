package com.imorpher.vectormorph.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.plan.MorphPlanCache
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold and warm morph planning: matching, subdivision, alignment and measurement.
 *
 * Run with `:benchmark:connectedBenchmarkAndroidTest` on a physical device.
 */
@RunWith(AndroidJUnit4::class)
class PlannerBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val from = VectorCompiler.compile(BenchmarkFixtures.outline)
    private val to = VectorCompiler.compile(BenchmarkFixtures.solid)
    private val config = MorphConfiguration.Default

    @Test
    fun planCold() {
        benchmarkRule.measureRepeated {
            MorphPlanner.plan(from, to, config)
        }
    }

    @Test
    fun planStructuralCacheHit() {
        val cache = MorphPlanCache(maxEntries = 8)
        cache.getOrPut(from, to, config) { MorphPlanner.plan(from, to, config) }

        benchmarkRule.measureRepeated {
            cache.getOrPut(from, to, config) { error("benchmark expects a cache hit") }
        }
    }
}
