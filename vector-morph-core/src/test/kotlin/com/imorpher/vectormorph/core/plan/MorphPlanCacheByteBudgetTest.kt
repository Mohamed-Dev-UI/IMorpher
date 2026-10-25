package com.imorpher.vectormorph.core.plan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MorphDebugOptions
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-budget eviction contract for [MorphPlanCache]. */
class MorphPlanCacheByteBudgetTest {

    @Test
    fun estimateBytesCountsPackedGeometry() {
        val plan = planFor(triangleVector("a"), triangleVector("b"))

        val bytes = MorphPlanCache.estimateBytes(plan)

        assertTrue("a morph plan must report its packed geometry", bytes > 0L)
    }

    @Test
    fun trimToBytesEvictsLeastRecentlyUsedFirst() {
        val cache = MorphPlanCache(maxEntries = 10, maxBytes = Long.MAX_VALUE)
        val a = planFor(triangleVector("a", 0f), triangleVector("b", 1f))
        val b = planFor(triangleVector("c", 2f), triangleVector("d", 3f))
        cache.getOrPut(a.from, a.to, MorphConfiguration.Default) { a }
        val perPlan = cache.byteSize
        cache.getOrPut(b.from, b.to, MorphConfiguration.Default) { b }
        assertEquals(2, cache.size)

        cache.trimToBytes(perPlan)

        assertEquals(1, cache.size)
        assertEquals(perPlan, cache.byteSize)
        // The first (least recently used) plan is the one dropped.
        cache.trimToBytes(0L)
        assertEquals(0, cache.size)
        assertEquals(0L, cache.byteSize)
    }

    @Test
    fun byteBudgetEvictsOnInsert() {
        val cache = MorphPlanCache(maxEntries = 10, maxBytes = 1L)
        val plan = planFor(triangleVector("a"), triangleVector("b"))

        cache.getOrPut(plan.from, plan.to, MorphConfiguration.Default) { plan }

        assertTrue("over-budget inserts must not stay cached", cache.size <= 1)
        assertTrue(cache.byteSize <= 1L)
    }

    @Test
    fun entryCountStillLimits() {
        val cache = MorphPlanCache(maxEntries = 2, maxBytes = Long.MAX_VALUE)
        val first = planFor(triangleVector("a", 0f), triangleVector("b", 1f))
        val second = planFor(triangleVector("c", 2f), triangleVector("d", 3f))
        val third = planFor(triangleVector("e", 4f), triangleVector("f", 5f))

        cache.getOrPut(first.from, first.to, MorphConfiguration.Default) { first }
        cache.getOrPut(second.from, second.to, MorphConfiguration.Default) { second }
        cache.getOrPut(third.from, third.to, MorphConfiguration.Default) { third }

        assertEquals(2, cache.size)
    }

    @Test
    fun clearResetsByteAccounting() {
        val cache = MorphPlanCache()
        val plan = planFor(triangleVector("a"), triangleVector("b"))
        cache.getOrPut(plan.from, plan.to, MorphConfiguration.Default) { plan }
        assertTrue(cache.byteSize > 0L)

        cache.clear()

        assertEquals(0, cache.size)
        assertEquals(0L, cache.byteSize)
    }

    @Test
    fun defaultBudgetConstantsAreSane() {
        assertEquals(32, MorphPlanCache.DEFAULT_MAX_ENTRIES)
        assertEquals(16L * 1024 * 1024, MorphPlanCache.DEFAULT_MAX_BYTES)
    }

    /** Render-only config fields must not fragment the cache (plan key < full config). */
    @Test
    fun planCacheIgnoresRenderOnlyFields() {
        val cache = MorphPlanCache()
        val plan = planFor(triangleVector("a"), triangleVector("b"))

        val first = cache.getOrPut(plan.from, plan.to, MorphConfiguration.Default) { plan }
        val second = cache.getOrPut(
            plan.from, plan.to,
            MorphConfiguration.Default.copy(
                debug = MorphDebugOptions(showCentroids = true),
                colorSpace = ColorInterpolationSpace.OKLAB,
                defaultFillMode = FillMode.RADIAL,
                keepRenderOrder = false,
                timing = TimingMode.BY_COMMAND,
            ),
        ) { error("render-only config changes must not re-plan") }

        assertEquals(
            "one plan entry across render-only config differences",
            1,
            cache.size,
        )
        assertSame("the plan instance must be reused", first, second)
    }

    /** Planning-relevant fields must still key distinct plans (guard against over-trimming). */
    @Test
    fun planCacheKeysPlanningFields() {
        val cache = MorphPlanCache()
        val plan = planFor(triangleVector("a"), triangleVector("b"))

        cache.getOrPut(plan.from, plan.to, MorphConfiguration.Default) { plan }
        val retarget = cache.getOrPut(
            plan.from, plan.to,
            MorphConfiguration.Default.copy(startPoint = StartPointStrategy.MATCH_SOURCE),
        ) { MorphPlanner.plan(plan.from, plan.to, MorphConfiguration.Default.copy(startPoint = StartPointStrategy.MATCH_SOURCE)) }

        assertEquals(2, cache.size)
        assertNotSame(plan, retarget)
    }

    private fun planFor(from: ImageVector, to: ImageVector): MorphPlan =
        MorphPlanner.plan(VectorCompiler.compile(from), VectorCompiler.compile(to))

    private fun triangleVector(name: String, offset: Float = 0f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(3f + offset, 3f),
                    PathNode.LineTo(21f, 3f),
                    PathNode.LineTo(12f, 21f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                name = "shape",
            )
        }.build()
}
