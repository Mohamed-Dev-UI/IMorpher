package com.imorpher.vectormorph.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import com.imorpher.vectormorph.core.geometry.Subdivider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Arc-length-driven subdivision: the planning stage that densifies a coarse contour up to its
 * partner's segment count (or the configured segment budget).
 *
 * Run with `:benchmark:connectedBenchmarkAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SubdividerBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    /** A closed four-cubic circle, the shape most icons are authored with. */
    private val circle = ContourData(
        segments = listOf(
            Cubic(12f, 2f, 17.52f, 2f, 22f, 6.48f, 22f, 12f),
            Cubic(22f, 12f, 22f, 17.52f, 17.52f, 22f, 12f, 22f),
            Cubic(12f, 22f, 6.48f, 22f, 2f, 17.52f, 2f, 12f),
            Cubic(2f, 12f, 2f, 6.48f, 6.48f, 2f, 12f, 2f),
        ),
        closed = true,
    )

    @Test
    fun equalizeTo64Segments() {
        benchmarkRule.measureRepeated {
            Subdivider.equalize(circle, 64)
        }
    }

    @Test
    fun splitSingleSegmentInto8() {
        val segment = circle.segments.first()
        benchmarkRule.measureRepeated {
            Subdivider.splitByArcLength(segment, 8)
        }
    }
}
