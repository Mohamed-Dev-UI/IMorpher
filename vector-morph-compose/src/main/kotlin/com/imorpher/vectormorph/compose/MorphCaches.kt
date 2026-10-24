package com.imorpher.vectormorph.compose

import com.imorpher.vectormorph.core.plan.MorphPlanCache

/**
 * Process-level caches used by the composables.
 *
 * Compiled vectors and morph plans are immutable, so they are shared across every caller in the
 * process. On memory pressure (for example `ComponentCallbacks2.onTrimMemory`) call [trim] to
 * drop least-recently-used plans until the cache fits the requested byte budget; plans rebuild
 * lazily on the next composition.
 */
object MorphCaches {

    /**
     * Trims the morph plan cache to at most [bytes] estimated bytes, evicting least-recently-used
     * plans first. The default keeps 8 MiB — half the cache's steady-state budget.
     */
    fun trim(bytes: Long = 8L * 1024 * 1024) {
        planCache.trimToBytes(bytes)
    }

    /** Estimated bytes currently held by cached morph plans. */
    val sizeBytes: Long get() = planCache.byteSize

    /** Number of cached morph plans. */
    val size: Int get() = planCache.size

    /** Drops every cached plan (the next composition re-plans). */
    fun clear() {
        planCache.clear()
    }

    /** The shared plan cache, exposed internally for tests and tooling. */
    internal val cache: MorphPlanCache get() = planCache
}
