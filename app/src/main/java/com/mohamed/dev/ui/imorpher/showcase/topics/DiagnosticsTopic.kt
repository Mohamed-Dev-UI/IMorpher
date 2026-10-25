package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.DrawIcon
import com.imorpher.vectormorph.compose.MorphCaches
import com.imorpher.vectormorph.core.animation.CustomVectorPathOverrides
import com.imorpher.vectormorph.core.geometry.sliceArcFraction
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.CodecResultUi
import com.mohamed.dev.ui.imorpher.showcase.DiagnosticsEngine
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import com.mohamed.dev.ui.imorpher.showcase.PlanReportUi
import com.mohamed.dev.ui.imorpher.showcase.PreviewTile
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCard
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCardHeader
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseDropdown
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseRow
import com.mohamed.dev.ui.imorpher.showcase.WarmResultUi
import com.mohamed.dev.ui.imorpher.showcase.resolveIcon
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compatibility score + pair-kind breakdown straight from `planMorph`, recomputed
 * whenever the chosen pair changes.
 */
@Composable
fun PlanReportShowcase() {
    var pair by remember { mutableStateOf(IconCatalog.pairs.first()) }
    var report by remember { mutableStateOf<PlanReportUi?>(null) }
    LaunchedEffect(pair) {
        report = null
        report = DiagnosticsEngine.planReportFor(pair.first, pair.second)
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "01",
            title = "Plan report",
            description = "Pair kinds, compatibility score, fallback flags, and validation straight from planMorph.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    val r = report
                    Text(
                        if (r == null) "…" else "${(r.score * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                ShowcaseDropdown("Pair", IconCatalog.pairs, pair) { pair = it }
                val r = report
                if (r == null) {
                    Text("Planning…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Path pairs: ${r.pathPairs}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Morph ${r.morphPairs} · Fade ${r.fadePairs} · Crossfade ${r.crossfadePairs}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Score: ${(r.score * 100f).roundToInt()}%",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (r.fallbackUsed) {
                            "Fallback: ${r.fallbackReason ?: "engine choice"}"
                        } else {
                            "No fallback: every path morphs directly"
                        }
                    )
                    if (r.unmatchedSource.isNotEmpty()) {
                        Text("Unmatched source: ${r.unmatchedSource.joinToString(" · ")}")
                    }
                    if (r.unmatchedTarget.isNotEmpty()) {
                        Text("Unmatched target: ${r.unmatchedTarget.joinToString(" · ")}")
                    }
                    r.issues.take(6).forEach { issue ->
                        Text(
                            "· [${issue.severity}] ${issue.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (r.issues.size > 6) {
                        Text(
                            "… ${r.issues.size - 6} more issues",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        ApiSignature("planMorph(from, to, configuration) → MorphPlan.pairs · MorphReport")
    }
}

/**
 * Cold-plan timing plus the live plan cache: warm a pair, watch entries/bytes, then trim.
 */
@Composable
fun CachesShowcase() {
    var warm by remember { mutableStateOf<WarmResultUi?>(null) }
    var entries by remember { mutableIntStateOf(MorphCaches.size) }
    var bytes by remember { mutableLongStateOf(MorphCaches.sizeBytes) }
    val scope = rememberCoroutineScope()

    fun refreshCacheCounts() {
        entries = MorphCaches.size
        bytes = MorphCaches.sizeBytes
    }

    ShowcaseCard {
        ShowcaseCardHeader(
            number = "02",
            title = "Plan caches",
            description = "Cold-plan timing and the live plan cache: entries, estimated bytes, and trim.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("$entries", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "entries",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scope.launch {
                            warm = DiagnosticsEngine.warmAndMeasure("AlgorithmOutline", "AlgorithmSolid")
                            refreshCacheCounts()
                        }
                    }) { Text("Warm the cache") }
                    Button(onClick = {
                        // The memory-pressure hook a host wires to ComponentCallbacks2.onTrimMemory.
                        MorphCaches.trim()
                        refreshCacheCounts()
                    }) { Text("Trim softly") }
                }
                val w = warm
                if (w == null) {
                    Text(
                        "Cache entries: $entries · Estimated bytes: $bytes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Cold plan: ${w.plannedMilliseconds} ms", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cache entries: ${w.cacheEntries} · Estimated bytes: ${w.cacheEstimateBytes}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ApiSignature("MorphCaches.trim / size / sizeBytes + planMorph cold timing")
    }
}

/**
 * Encode → decode round-trip of a persisted timeline, replayed as a DrawIcon — exactly
 * what an on-disk load produces.
 */
@Composable
fun CodecShowcase() {
    var replayKey by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<CodecResultUi?>(null) }
    val animation = remember {
        VectorAnimationSpec {
            at(0f) { alpha = 0.4f; scale = 0.9f }
            at(1f) { alpha = 1f; scale = 1f }
            allPaths { strokeReveal(interval = 0.05f..0.9f) }
        }
    }
    LaunchedEffect(animation) {
        result = DiagnosticsEngine.codecRoundTrip(animation)
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "03",
            title = "Codec round-trip",
            description = "Encode a timeline, decode the bytes, and replay what persisted.",
        )
        ShowcaseRow(
            preview = {
                key(replayKey, result) {
                    val r = result
                    if (r?.decoded != null) {
                        PreviewTile {
                            DrawIcon(
                                vector = resolveIcon("AddCircleOutline"),
                                animation = r.decoded!!,
                                animationSpec = tween(1400),
                                contentDescription = "Decoded timeline icon",
                            )
                        }
                    } else {
                        PreviewTile {
                            Text("…", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                val r = result
                when {
                    r == null -> Text(
                        "Encoding…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !r.decodeOk -> Text("Codec error: ${r.error}")
                    else -> {
                        Text("Encoded ${r.encodedBytes} bytes", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Decoded warnings: ${r.timelineWarnings}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { replayKey++ }) { Text("Replay") }
                    }
                }
            }
        }
        ApiSignature("VectorAnimationCodec.encode / decode — customizers and custom brushes reject")
    }
}

/**
 * A CustomVectorAnimation callback shapes the stroke every frame: visible geometry is
 * sliced from arc length at drawProgress and the pen shrinks as it draws.
 */
@Composable
fun CustomAnimationShowcase() {
    var replayKey by remember { mutableIntStateOf(0) }
    val animation = remember {
        VectorAnimationSpec {
            allPaths { strokeReveal(mode = DrawMode.CUSTOM) }
            customAnimation { path ->
                val shape = path.to ?: path.from
                val visible = shape?.contours?.mapIndexed { index, contour ->
                    val arc = shape.arcLengths.getOrNull(index)
                    if (arc != null) {
                        contour.sliceArcFraction(0f, path.drawProgress, arc)
                    } else {
                        contour
                    }
                }
                CustomVectorPathOverrides(
                    contours = shape?.contours,
                    strokeRevealContours = visible,
                    strokeWidth = max(0.4f, 2.4f * (1f - path.drawProgress)),
                )
            }
        }
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "04",
            title = "Custom geometry",
            description = "A CustomVectorAnimation callback shapes the stroke per frame.",
        )
        ShowcaseRow(
            preview = {
                key(replayKey) {
                    PreviewTile {
                        DrawIcon(
                            vector = resolveIcon("SealOutline"),
                            animation = animation,
                            animationSpec = tween(1800),
                            contentDescription = "Custom geometry icon",
                        )
                    }
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Shrinking-pen stroke reveal", fontWeight = FontWeight.SemiBold)
                Text(
                    "strokeReveal(mode = CUSTOM) plus sliceArcFraction(0f, drawProgress) per contour",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { replayKey++ }) { Text("Replay") }
            }
        }
        ApiSignature("customAnimation { path -> CustomVectorPathOverrides }")
    }
}
