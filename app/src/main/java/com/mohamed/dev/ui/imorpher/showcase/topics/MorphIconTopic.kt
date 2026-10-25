package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.MorphIcon
import com.imorpher.vectormorph.compose.MorphingIcon
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.ConfigurationPanel
import com.mohamed.dev.ui.imorpher.showcase.CopySnippetButton
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestions
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestionsSection
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
import com.mohamed.dev.ui.imorpher.showcase.IconPairs
import com.mohamed.dev.ui.imorpher.showcase.PlaygroundControls
import com.mohamed.dev.ui.imorpher.showcase.PreviewTile
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCard
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCardHeader
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseRow
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.TintBuilders
import com.mohamed.dev.ui.imorpher.showcase.TintMode
import com.mohamed.dev.ui.imorpher.showcase.TopicHeader
import com.mohamed.dev.ui.imorpher.showcase.resolveIcon
import com.mohamed.dev.ui.imorpher.showcase.toAlignment
import com.mohamed.dev.ui.imorpher.showcase.toAnimationSpec
import com.mohamed.dev.ui.imorpher.showcase.toConfiguration
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.MorphConfiguration as EngineConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

/**
 * Stroke → fill handover strategies for outline↔filled pairs: pick one and toggle to watch
 * how the outline's paint is handed over to the target's fill.
 */
@Composable
fun StrokeFillShowcase() {
    var selected by remember { mutableStateOf(false) }
    var strategy by remember { mutableStateOf(StrokeFillStrategy.MORPH) }
    val (fromName, toName) = IconPairs.add
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "03",
            title = "Stroke → fill handover",
            description = "How an outline hands itself over to the fill: MORPH, CROSSFADE, DRAW, AUTO.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphIcon(
                        from = IconCatalog.icon(fromName),
                        to = IconCatalog.icon(toName),
                        selected = selected,
                        configuration = EngineConfiguration(strokeFill = strategy),
                        contentDescription = "Stroke fill morph icon",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("$fromName → $toName", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StrokeFillStrategy.entries.forEach { option ->
                        Button(onClick = { strategy = option }) {
                            Text(option.name.lowercase().replaceFirstChar { it.titlecase() })
                        }
                    }
                }
                Text(
                    "Toggling plays handover under the chosen strategy.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
        }
        ApiSignature("MorphIcon(from, to, selected, configuration(strokeFill = …))")
    }
}

/**
 * A morph that retargets instantly from whatever icon last settled — the MorphingIcon
 * contract: mid-flight retargets restart from the settled icon, never from a frame.
 */
@Composable
fun ChainedIconShowcase() {
    val cycle = remember { listOf("ArrowUp1", "ArrowDown1", "AddCircleOutline", "SealOutline") }
    var index by remember { mutableIntStateOf(0) }
    val target = IconCatalog.icon(cycle[index])
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "04",
            title = "Chained icon retargeting",
            description = "MorphingIcon always morphs from the last settled icon, never from a mid-flight frame.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    MorphingIcon(
                        target = target,
                        animationSpec = tween(650),
                        contentDescription = "Chained morph icon",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Settled target: ${cycle[index]}", fontWeight = FontWeight.SemiBold)
                Text(
                    "Fast-forward: tapping mid-morph restarts from the last settle.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { index = (index + 1) % cycle.size }) { Text("Next icon") }
        }
        ApiSignature("MorphingIcon(target, animationSpec, configuration)")
    }
}

/** Reduced-motion and instant preferences for accessibility. */
@Composable
fun MotionPreferenceShowcase() {
    var selected by remember { mutableStateOf(false) }
    var preference by remember { mutableStateOf(MotionPreference.REDUCED) }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "05",
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
 * one live icon, including the untinted/tinted overload selection and settled-state events.
 */
@Composable
fun MorphIconPlayground(state: ShowcaseState) {
    var selected by remember { mutableStateOf(false) }
    var settleLog by remember { mutableStateOf(listOf<String>()) }
    val stamp = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "GP",
            title = "MorphIcon playground",
            description = "Every MorphIcon parameter, live: icons, tint endpoints, timing, and configuration.",
        )
        ShowcaseRow(
            preview = {
                PlaygroundPreview(
                    state = state,
                    selected = selected,
                    onSettled = { settled ->
                        settleLog = (settleLog + "${if (settled) "settled" else "interrupted"} · ${stamp.format(Date())}")
                            .takeLast(4)
                    },
                )
            },
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
            // The shared ConfigurationPanel owns every MorphConfiguration knob, so the
            // playgrounds cannot drift apart; overrides below are MorphIcon-specific.
            ConfigurationPanel(state, pathNames = PairSuggestions.pathNamesFor(state.fromIconName, state.toIconName))
            PairSuggestionsSection(state) { suggestion ->
                state.fromIconName = suggestion.fromName
                state.toIconName = suggestion.toName
            }
            CopySnippetButton(SnippetGenerator.generate(state, SnippetGenerator.Target.MORPH_ICON))
        }
        if (settleLog.isNotEmpty()) {
            Text(
                "onSettled log\n" + settleLog.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ApiSignature("MorphIcon(from, to, selected, tintFrom, tintTo, …, configuration)")
    }
}

/** One live MorphIcon wired to the shared playground state, honoring the tint mode. */
@Composable
internal fun PlaygroundPreview(
    state: ShowcaseState,
    selected: Boolean,
    onSettled: ((Boolean) -> Unit)? = null,
) {
    PreviewTile {
        val from = resolveIcon(state.fromIconName)
        val to = resolveIcon(state.toIconName)
        val spec = state.toAnimationSpec()
        val placement = state.toAlignment()
        when (state.tintMode) {
            TintMode.NONE -> MorphIcon(
                from = from,
                to = to,
                selected = selected,
                animationSpec = spec,
                motionPreference = state.motionPreference,
                onSettled = onSettled,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = (state.iconWidthDp ?: 52f).dp,
                height = (state.iconHeightDp ?: 52f).dp,
                alignment = placement,
            )
            TintMode.SOLID -> MorphIcon(
                from = from,
                to = to,
                selected = selected,
                tintFrom = TintBuilders.fromColor(state),
                tintTo = TintBuilders.toColor(state),
                animationSpec = spec,
                motionPreference = state.motionPreference,
                onSettled = onSettled,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = (state.iconWidthDp ?: 52f).dp,
                height = (state.iconHeightDp ?: 52f).dp,
                alignment = placement,
            )
            TintMode.GRADIENT -> MorphIcon(
                from = from,
                to = to,
                selected = selected,
                tintFrom = TintBuilders.fromBrush(state),
                tintTo = TintBuilders.toBrush(state),
                animationSpec = spec,
                motionPreference = state.motionPreference,
                onSettled = onSettled,
                configuration = state.toConfiguration(),
                contentDescription = "Playground icon",
                width = (state.iconWidthDp ?: 52f).dp,
                height = (state.iconHeightDp ?: 52f).dp,
                alignment = placement,
            )
        }
    }
}

private fun MorphConfiguration(pathMatching: PathMatchingStrategy): EngineConfiguration =
    EngineConfiguration(pathMatching = pathMatching)
