package com.imorpher.vectormorph.compose

import androidx.compose.ui.graphics.vector.ImageVector
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.planningKey
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.MorphPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Warms the process-wide morph plan cache off the main thread.
 *
 * Morph planning (matching, subdivision, measurement) is the only expensive path in the library;
 * precompiling known icon pairs before they are shown keeps the first animated frame on budget.
 * Warming writes into the same structural cache [MorphVector]/[MorphIcon]/[MorphingIcon] read, so
 * a later composition of the same pair is a cache hit.
 */
object MorphPrecompiler {

    /**
     * Compiles and plans [from] → [to] on [Dispatchers.Default], populating the shared plan cache.
     * Re-warming an already cached pair is cheap and idempotent.
     */
    suspend fun warm(
        from: ImageVector,
        to: ImageVector,
        config: MorphConfiguration = MorphConfiguration.Default,
    ) {
        withContext(Dispatchers.Default) {
            val planningConfiguration = config.copy(animation = null)
            val preparedFrom = prepared(from)
            val preparedTo = prepared(to)
            planCache.getOrPut(preparedFrom, preparedTo, planningConfiguration.planningKey()) {
                MorphPlanner.plan(preparedFrom, preparedTo, planningConfiguration)
            }
        }
    }

    /** Warms several icon pairs in order. */
    suspend fun warm(
        config: MorphConfiguration = MorphConfiguration.Default,
        vararg pairs: Pair<ImageVector, ImageVector>,
    ) {
        for ((from, to) in pairs) warm(from, to, config)
    }

    /**
     * Whether a plan for this pair is currently cached (always false after [MorphCaches.trim] or
     * cache eviction; re-warm to restore it).
     *
     * [configuration] defaults to [MorphConfiguration.Default] (source/binary compatible with
     * the historical 2-argument call). The probe keys on [configuration]'s PLANNING fields
     * via [com.imorpher.vectormorph.core.model.planningKey], exactly how [warm] keyed the
     * cache — render-only fields (debug, timing, draw-order, ...) never invalidate it.
     */
    fun isPrepared(
        from: ImageVector,
        to: ImageVector,
        configuration: MorphConfiguration = MorphConfiguration.Default,
    ): Boolean {
        val planningConfiguration = configuration.copy(animation = null)
        return planCache.get(prepared(from), prepared(to), planningConfiguration.planningKey()) != null
    }

    /** Test/diagnostic seam returning the warmed plan, if present. */
    internal fun planFor(
        from: ImageVector,
        to: ImageVector,
        config: MorphConfiguration = MorphConfiguration.Default,
    ): MorphPlan? {
        val planningConfiguration = config.copy(animation = null)
        return planCache.get(prepared(from), prepared(to), planningConfiguration.planningKey())
    }
}
