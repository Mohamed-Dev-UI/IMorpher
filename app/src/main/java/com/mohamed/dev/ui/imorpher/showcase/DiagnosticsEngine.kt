package com.mohamed.dev.ui.imorpher.showcase

import com.imorpher.vectormorph.compose.MorphCaches
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimationCodec
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.plan.PairKind
import com.imorpher.vectormorph.core.plan.planMorph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One planning finding rendered in the plan-report card. */
data class PlanIssueUi(
    /** INFO, WARNING, or ERROR. */
    val severity: String,
    /** Machine-readable grouping ("contour-count", "gradient", "missing"…). */
    val category: String,
    val message: String,
)

/** Everything the plan-report card shows for one icon pair, derived from [planMorph]. */
data class PlanReportUi(
    val fromName: String,
    val toName: String,
    /** Path pairs handed to the renderer (morphs + fades + crossfades). */
    val pathPairs: Int,
    val morphPairs: Int,
    /** Fade-in plus fade-out pairs. */
    val fadePairs: Int,
    val crossfadePairs: Int,
    /** 0..1 weighted plan quality (1 ≈ every path pairs up with identical structure). */
    val score: Float,
    val fallbackUsed: Boolean,
    val fallbackReason: String?,
    val unmatchedSource: List<String>,
    val unmatchedTarget: List<String>,
    val issues: List<PlanIssueUi>,
)

/** Cache measurement shown by the caches card after a warm pass. */
data class WarmResultUi(
    /** Wall time of the cold plan (compile + match + measure). */
    val plannedMilliseconds: Long,
    val cacheEntries: Int,
    val cacheEstimateBytes: Long,
)

/** One encode→decode round-trip outcome, decoding payload included for playback. */
data class CodecResultUi(
    val encodedBytes: Int,
    val decodeOk: Boolean,
    /** The restored timeline, playable exactly as a on-disk load would hand it over. */
    val decoded: VectorAnimation? = null,
    val timelineWarnings: Int = 0,
    val error: String? = null,
)

/**
 * [PairSuggestions]-style engine port behind the Diagnostics topic screens: pure planning,
 * cache, and codec calls, always shuffled off the main thread.
 */
object DiagnosticsEngine {

    /**
     * Plans [fromName]/[toName] off the main thread and summarizes [MorphReport]-style
     * findings. A hostile configuration (for example a throwing fallback strategy) fails
     * planning; the error is surfaced as an ERROR issue rather than crashing the screen.
     */
    suspend fun planReportFor(
        fromName: String,
        toName: String,
        config: MorphConfiguration = MorphConfiguration.Default,
    ): PlanReportUi = withContext(Dispatchers.Default) {
        val plan = runCatching {
            planMorph(
                from = IconCatalog.icon(fromName),
                to = IconCatalog.icon(toName),
                config = config,
            )
        }.getOrElse { error ->
            return@withContext PlanReportUi(
                fromName = fromName,
                toName = toName,
                pathPairs = 0,
                morphPairs = 0,
                fadePairs = 0,
                crossfadePairs = 0,
                score = 0f,
                fallbackUsed = true,
                fallbackReason = "planning failed: ${error.message}",
                unmatchedSource = emptyList(),
                unmatchedTarget = emptyList(),
                issues = listOf(PlanIssueUi("ERROR", "planner", error.message ?: "unknown error")),
            )
        }
        PlanReportUi(
            fromName = fromName,
            toName = toName,
            pathPairs = plan.pairs.size,
            morphPairs = plan.pairs.count { it.kind == PairKind.MORPH },
            fadePairs = plan.pairs.count { it.kind == PairKind.FADE_IN || it.kind == PairKind.FADE_OUT },
            crossfadePairs = plan.pairs.count { it.kind == PairKind.CROSSFADE },
            score = plan.report.compatibility.score,
            fallbackUsed = plan.usedFallback,
            fallbackReason = plan.fallbackReason,
            unmatchedSource = plan.report.unmatchedSourcePaths,
            unmatchedTarget = plan.report.unmatchedTargetPaths,
            issues = plan.report.issues.map {
                PlanIssueUi(
                    severity = it.severity.name,
                    category = it.category,
                    message = it.message,
                )
            },
        )
    }

    /**
     * Clears the plan cache, plans [fromName]/[toName] cold, and reports how long the
     * rebuild took plus what [MorphCaches] now holds.
     */
    suspend fun warmAndMeasure(
        fromName: String,
        toName: String,
        config: MorphConfiguration = MorphConfiguration.Default,
    ): WarmResultUi = withContext(Dispatchers.Default) {
        MorphCaches.clear()
        val startedAt = System.nanoTime()
        runCatching {
            planMorph(
                from = IconCatalog.icon(fromName),
                to = IconCatalog.icon(toName),
                config = config,
            )
        }
        WarmResultUi(
            plannedMilliseconds = (System.nanoTime() - startedAt) / 1_000_000L,
            cacheEntries = MorphCaches.size,
            cacheEstimateBytes = MorphCaches.sizeBytes,
        )
    }

    /**
     * Encodes [animation] and decodes it back — mirroring what a persisted timeline would
     * survive on disk. Customizer-bearing timelines and custom brushes are rejected by the
     * codec; the error text is returned instead of thrown so the card can display it.
     */
    suspend fun codecRoundTrip(animation: VectorAnimation): CodecResultUi = withContext(Dispatchers.Default) {
        runCatching {
            val bytes = VectorAnimationCodec.encode(animation)
            val decoded = VectorAnimationCodec.decode(bytes)
            CodecResultUi(
                encodedBytes = bytes.size,
                decodeOk = true,
                decoded = decoded,
                timelineWarnings = decoded.warnings.size,
            )
        }.getOrElse { error ->
            CodecResultUi(
                encodedBytes = 0,
                decodeOk = false,
                timelineWarnings = animation.warnings.size,
                error = error.message ?: "codec failed",
            )
        }
    }
}
