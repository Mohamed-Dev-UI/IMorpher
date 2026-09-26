package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.MorphVector
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.CopySnippetButton
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestionsSection
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
import com.mohamed.dev.ui.imorpher.showcase.IconPairs
import com.mohamed.dev.ui.imorpher.showcase.LabeledSlider
import com.mohamed.dev.ui.imorpher.showcase.PlaygroundControls
import com.mohamed.dev.ui.imorpher.showcase.PreviewTile
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCard
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCardHeader
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseDropdown
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseOptions
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseRow
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.TintBuilders
import com.mohamed.dev.ui.imorpher.showcase.TintMode
import com.mohamed.dev.ui.imorpher.showcase.TopicHeader
import com.mohamed.dev.ui.imorpher.showcase.resolveIcon
import com.mohamed.dev.ui.imorpher.showcase.toConfiguration
import kotlin.math.roundToInt

/** Gesture-free manual progress: the caller owns the value, a slider stands in for a pager. */
@Composable
fun ManualScrubShowcase() {
    var progress by remember { mutableFloatStateOf(0f) }
    val (fromName, toName) = IconPairs.play
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "01",
            title = "Manually scrub a morph",
            description = "Use MorphVector when a gesture, pager, or scroll position owns progress.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphVector(
                        from = IconCatalog.icon(fromName),
                        to = IconCatalog.icon(toName),
                        progress = progress,
                        contentDescription = "Manual morph icon",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("$fromName → $toName", fontWeight = FontWeight.SemiBold)
                Slider(value = progress, onValueChange = { progress = it })
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ApiSignature("MorphVector(from, to, progress)")
    }
}

/** Unmatched paths handled by the missing-path behaviors. */
@Composable
fun MissingPathShowcase() {
    var progress by remember { mutableFloatStateOf(0f) }
    var behavior by remember { mutableStateOf(MissingPathBehavior.SCALE) }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "02",
            title = "Unmatched paths",
            description = "Choose what happens to paths that only exist on one side of the morph.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphVector(
                        from = IconCatalog.icon(IconPairs.play.first),
                        to = IconCatalog.icon(IconPairs.archive.first),
                        progress = progress,
                        configuration = MorphConfiguration(missingPath = behavior),
                        contentDescription = "Missing path morph icon",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        MissingPathBehavior.FADE,
                        MissingPathBehavior.SCALE,
                        MissingPathBehavior.COLLAPSE,
                        MissingPathBehavior.KEEP,
                    ).forEach { option ->
                        androidx.compose.material3.Button(onClick = { behavior = option }) {
                            Text(option.name.lowercase().replaceFirstChar { it.titlecase() })
                        }
                    }
                }
                LabeledSlider(
                    label = "Progress",
                    value = progress,
                    range = 0f..1f,
                    suffix = "%",
                    onValueChange = { progress = (it * 100f).roundToInt() / 100f },
                )
            }
        }
        ApiSignature("MorphVector(from, to, progress, configuration(missingPath = …))")
    }
}

/** Global MorphVector playground sharing the common controls with all other topics. */
@Composable
fun MorphVectorPlayground(state: ShowcaseState) {
    var progress by remember { mutableFloatStateOf(0f) }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "GP",
            title = "MorphVector playground",
            description = "Scrub every MorphVector parameter live: icons, tint, progress, and configuration.",
        )
        ShowcaseRow(
            preview = { VectorPreview(state = state, progress = progress) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("${state.fromIconName} → ${state.toIconName}", fontWeight = FontWeight.SemiBold)
                LabeledSlider(
                    label = "Progress",
                    value = progress,
                    range = 0f..1f,
                    suffix = "%",
                    onValueChange = { progress = (it * 100f).roundToInt() / 100f },
                )
            }
        }
        PlaygroundControls(state) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShowcaseDropdown("Match", ShowcaseOptions.pathMatchings, state.pathMatching) { state.pathMatching = it }
                ShowcaseDropdown("Missing", ShowcaseOptions.missingPaths, state.missingPath) { state.missingPath = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShowcaseDropdown("Fallback", ShowcaseOptions.fallbacks, state.fallback) { state.fallback = it }
                ShowcaseDropdown("Timing", ShowcaseOptions.timings, state.timing) { state.timing = it }
            }
            ShowcaseDropdown("Colors", ShowcaseOptions.colorSpaces, state.colorSpace) { state.colorSpace = it }
            PairSuggestionsSection(state) { suggestion ->
                state.fromIconName = suggestion.fromName
                state.toIconName = suggestion.toName
            }
            CopySnippetButton(SnippetGenerator.generate(state, SnippetGenerator.Target.MORPH_VECTOR))
        }
        ApiSignature("MorphVector(from, to, progress, tintFrom, tintTo, …, configuration)")
    }
}

/** One live MorphVector wired to the shared playground state, honoring the tint mode. */
@Composable
internal fun VectorPreview(state: ShowcaseState, progress: Float) {
    PreviewTile {
        val from = resolveIcon(state.fromIconName)
        val to = resolveIcon(state.toIconName)
        when (state.tintMode) {
            TintMode.NONE -> MorphVector(
                from = from,
                to = to,
                progress = progress,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = 52.dp,
                height = 52.dp,
            )
            TintMode.SOLID -> MorphVector(
                from = from,
                to = to,
                progress = progress,
                tintFrom = TintBuilders.fromColor(state),
                tintTo = TintBuilders.toColor(state),
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = 52.dp,
                height = 52.dp,
            )
            TintMode.GRADIENT -> MorphVector(
                from = from,
                to = to,
                progress = progress,
                tintFrom = TintBuilders.fromBrush(state),
                tintTo = TintBuilders.toBrush(state),
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = 52.dp,
                height = 52.dp,
            )
        }
    }
}

/** Local alias to keep the engine configuration import short in this file. */
private fun MorphConfiguration(
    missingPath: MissingPathBehavior,
): com.imorpher.vectormorph.core.model.MorphConfiguration =
    com.imorpher.vectormorph.core.model.MorphConfiguration(missingPath = missingPath)
