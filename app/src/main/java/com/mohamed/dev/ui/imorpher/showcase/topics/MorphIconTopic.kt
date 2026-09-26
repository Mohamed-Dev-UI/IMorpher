package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.MorphIcon
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.CopySnippetButton
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestionsSection
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
import com.mohamed.dev.ui.imorpher.showcase.IconPairs
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
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.MorphConfiguration as EngineConfiguration
import kotlin.math.roundToInt

/** Selected state with a bouncy spring and name-based path matching. */
@Composable
fun StateMorphShowcase() {
    var selected by remember { mutableStateOf(false) }
    val (fromName, toName) = IconPairs.home
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "01",
            title = "State-driven icon morph",
            description = "MorphIcon owns progress and retargets smoothly when state changes.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphIcon(
                        from = IconCatalog.icon(fromName),
                        to = IconCatalog.icon(toName),
                        selected = selected,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        configuration = MorphConfiguration(pathMatching = PathMatchingStrategy.BY_NAME),
                        contentDescription = if (selected) "Selected" else "Not selected",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$fromName → $toName", fontWeight = FontWeight.SemiBold)
                Text(
                    if (selected) "Selected" else "Not selected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
        }
        ApiSignature("MorphIcon(from, to, selected, animationSpec, configuration)")
    }
}

/** One color that transitions to another as the selection state changes. */
@Composable
fun TintTransitionShowcase() {
    var selected by remember { mutableStateOf(false) }
    val (fromName, toName) = IconPairs.add
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "02",
            title = "Tint transition",
            description = "Endpoint colors interpolate across the morph; tint replaces all paint.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphIcon(
                        from = IconCatalog.icon(fromName),
                        to = IconCatalog.icon(toName),
                        selected = selected,
                        tintFrom = com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.indigo,
                        tintTo = com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.cyan,
                        contentDescription = "Tinted morph icon",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Indigo → Cyan", fontWeight = FontWeight.SemiBold)
                Text(
                    "tintFrom at progress 0, tintTo at progress 1",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
        }
        ApiSignature("MorphIcon(from, to, selected, tintFrom, tintTo)")
    }
}

/** Reduced-motion and instant preferences for accessibility. */
@Composable
fun MotionPreferenceShowcase() {
    var selected by remember { mutableStateOf(false) }
    var preference by remember { mutableStateOf(MotionPreference.REDUCED) }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "03",
            title = "Motion preference",
            description = "The caller keeps touch and semantics; only visual motion changes.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphIcon(
                        from = IconCatalog.icon(IconPairs.play.first),
                        to = IconCatalog.icon(IconPairs.play.second),
                        selected = selected,
                        motionPreference = preference,
                        contentDescription = "Motion preference icon",
                        width = 48.dp,
                        height = 48.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (selected) "Selected" else "Not selected", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionPreference.entries.forEach { option ->
                        Button(onClick = { preference = option }) {
                            Text(option.name.lowercase().replaceFirstChar { it.titlecase() })
                        }
                    }
                }
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
        }
        ApiSignature("MorphIcon(from, to, selected, motionPreference = …)")
    }
}

/**
 * The global MorphIcon playground: every parameter of the API is adjustable and applied to
 * one live icon, including the untinted/tinted overload selection.
 */
@Composable
fun MorphIconPlayground(state: ShowcaseState) {
    var selected by remember { mutableStateOf(false) }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "GP",
            title = "MorphIcon playground",
            description = "Every MorphIcon parameter, live: icons, tint endpoints, timing, and configuration.",
        )
        ShowcaseRow(
            preview = { PlaygroundPreview(state = state, selected = selected) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (selected) "Selected" else "Not selected", fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.fromIconName} → ${state.toIconName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShowcaseDropdown("Colors", ShowcaseOptions.colorSpaces, state.colorSpace) { state.colorSpace = it }
                ShowcaseDropdown("Motion", ShowcaseOptions.motionPreferences, state.motionPreference) { state.motionPreference = it }
            }
            // No stagger controls: MorphIcon runs a plain geometry morph without a
            // timeline, so staggerDelay would have nothing to fan out.
            PairSuggestionsSection(state) { suggestion ->
                state.fromIconName = suggestion.fromName
                state.toIconName = suggestion.toName
            }
            CopySnippetButton(SnippetGenerator.generate(state, SnippetGenerator.Target.MORPH_ICON))
        }
        ApiSignature("MorphIcon(from, to, selected, tintFrom, tintTo, …, configuration)")
    }
}

/** One live MorphIcon wired to the shared playground state, honoring the tint mode. */
@Composable
internal fun PlaygroundPreview(state: ShowcaseState, selected: Boolean) {
    PreviewTile {
        val from = resolveIcon(state.fromIconName)
        val to = resolveIcon(state.toIconName)
        val spec = tween<Float>(state.durationMillis.roundToInt())
        when (state.tintMode) {
            TintMode.NONE -> MorphIcon(
                from = from,
                to = to,
                selected = selected,
                animationSpec = spec,
                motionPreference = state.motionPreference,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = 52.dp,
                height = 52.dp,
            )
            TintMode.SOLID -> MorphIcon(
                from = from,
                to = to,
                selected = selected,
                tintFrom = TintBuilders.fromColor(state),
                tintTo = TintBuilders.toColor(state),
                animationSpec = spec,
                motionPreference = state.motionPreference,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = 52.dp,
                height = 52.dp,
            )
            TintMode.GRADIENT -> MorphIcon(
                from = from,
                to = to,
                selected = selected,
                tintFrom = TintBuilders.fromBrush(state),
                tintTo = TintBuilders.toBrush(state),
                animationSpec = spec,
                motionPreference = state.motionPreference,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = 52.dp,
                height = 52.dp,
            )
        }
    }
}

private fun MorphConfiguration(pathMatching: PathMatchingStrategy): EngineConfiguration =
    EngineConfiguration(pathMatching = pathMatching)
