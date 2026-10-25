package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imorpher.vectormorph.core.plan.IssueSeverity
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.plan.planMorph
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One suggested morph pair with its engine-ranked quality. */
data class PairSuggestion(
    val fromName: String,
    val toName: String,
    /** 0..1 geometric compatibility from [planMorph]'s report. */
    val score: Float,
    val fallbackUsed: Boolean,
    val warnings: Int,
)

/**
 * Ranks every catalog pair by the engine's own geometric compatibility score. Planning runs on
 * [Dispatchers.Default] (it is pure CPU work over compiled geometry) and results are cached for
 * the process lifetime; ranking uses the default configuration so scores stay comparable
 * across pairs.
 */
object PairSuggestions {

    /** Result holder observed by the UI while the ranking pass runs. */
    class Ranking {
        var isRunning by mutableStateOf(false)
        var suggestions by mutableStateOf<List<PairSuggestion>>(emptyList())

        internal fun publish(result: List<PairSuggestion>) {
            suggestions = result
            isRunning = false
        }
    }

    /**
     * One ranking per process. The double-compute race is benign (both rank the same
     * static catalog), so a volatile field plus idempotent publish is enough.
     */
    @Volatile
    private var cached: Ranking? = null

    /** Returns a cached ranking, computing it off the main thread on first use. */
    fun ranking(scope: CoroutineScope): Ranking {
        cached?.let { return it }
        val ranking = Ranking()
        cached = ranking
        ranking.isRunning = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { compute() }
            ranking.publish(result)
        }
        return ranking
    }

    /**
     * Compiled path names, keyed per pair. Snapshot-backed so a lookup that finishes off the
     * main thread recomposes the editor that read it during composition.
     */
    private val pathNamesCache = mutableStateOf<Map<Long, List<String>>>(emptyMap())

    /** Keys with a lookup scheduled but not published yet (deduplicates warm requests). */
    private val inFlightPathNames = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** Object-owned scope for warm lookups ([ranking]'s scope is passed in by callers only). */
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Working-set bound for [pathNamesCache]; evicted pairs simply warm again on demand. */
    private const val MAX_CACHED_PATH_NAMES = 64

    /** Test-only peek at the bounded cache size. */
    internal fun cachedPathNamesCountForTest(): Int = pathNamesCache.value.size

    /**
     * Compiled path names of [fromName]/[toName] (compiler-disambiguated defaults like
     * "path[1]"), for the configuration editors that key per-path parameters by name.
     * Never blocks: a miss returns the placeholder names immediately and resolves the real
     * names on [Dispatchers.Default], publishing into a snapshot-backed cache; planning
     * failures fall back to the default names once (cached) instead of being retried forever.
     */
    fun pathNamesFor(fromName: String, toName: String): List<String> {
        val key = (fromName.hashCode().toLong() shl 32) or (toName.hashCode().toLong() and 0xFFFFFFFFL)
        pathNamesCache.value[key]?.let { return it }
        if (inFlightPathNames.add(key)) {
            warmScope.launch {
                publishPathNames(key, resolvePathNames(fromName, toName))
            }
        }
        return DefaultPathNames
    }

    private fun resolvePathNames(fromName: String, toName: String): List<String> = runCatching {
        val plan = planMorph(
            from = IconCatalog.icon(fromName),
            to = IconCatalog.icon(toName),
            config = MorphConfiguration.Default,
        )
        plan.pairs.flatMap { pair -> listOfNotNull(pair.fromPath, pair.toPath).map { it.name } }
            .distinct()
    }.getOrDefault(DefaultPathNames).ifEmpty { DefaultPathNames }

    /** Publishes a resolved lookup into the snapshot cache, evicting oldest-first over the cap. */
    private fun publishPathNames(key: Long, names: List<String>) {
        var updated = pathNamesCache.value + (key to names)
        while (updated.size > MAX_CACHED_PATH_NAMES) {
            updated = updated - updated.keys.first()
        }
        pathNamesCache.value = updated
        inFlightPathNames.remove(key)
    }

    private fun compute(): List<PairSuggestion> {
        val config = MorphConfiguration.Default
        return IconCatalog.pairs.map { (fromName, toName) ->
            runCatching {
                val plan = planMorph(
                    from = IconCatalog.icon(fromName),
                    to = IconCatalog.icon(toName),
                    config = config,
                )
                PairSuggestion(
                    fromName = fromName,
                    toName = toName,
                    score = plan.report.compatibility.score,
                    fallbackUsed = plan.report.fallbackUsed,
                    warnings = plan.report.issues.count { it.severity == IssueSeverity.WARNING },
                )
            }.getOrElse {
                PairSuggestion(fromName, toName, score = 0f, fallbackUsed = true, warnings = 1)
            }
        }.sortedByDescending { it.score }
    }
}
