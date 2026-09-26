package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imorpher.vectormorph.core.plan.IssueSeverity
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.plan.planMorph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
