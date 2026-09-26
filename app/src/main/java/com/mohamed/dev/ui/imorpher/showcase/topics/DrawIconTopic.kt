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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.DrawIcon
import com.imorpher.vectormorph.core.model.TimingMode
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.CopySnippetButton
import com.mohamed.dev.ui.imorpher.showcase.LabeledSlider
import com.mohamed.dev.ui.imorpher.showcase.LabeledSlider
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
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
import androidx.compose.foundation.layout.size as layoutSize
import kotlin.math.roundToInt

/** A one-shot pen reveal with a replay control. */
@Composable
fun PenRevealShowcase() {
    var replayKey by remember { mutableIntStateOf(0) }
    val animation = remember {
        VectorAnimationSpec {
            allPaths {
                strokeReveal(
                    start = com.imorpher.vectormorph.core.model.PathStart.START,
                    direction = com.imorpher.vectormorph.core.model.DrawDirection.AUTO,
                    interval = 0f..1f,
                    mode = com.imorpher.vectormorph.core.model.DrawMode.FORWARD,
                )
            }
        }
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "01",
            title = "Draw an icon with a pen",
            description = "Named paths get their own reveal interval, anchor, and direction.",
        )
        ShowcaseRow(
            preview = {
                key(replayKey) {
                    PreviewTile {
                        DrawIcon(
                            vector = resolveIcon("AlgorithmOutline"),
                            animation = animation,
                            animationSpec = tween(1100),
                            contentDescription = "Pen reveal icon",
                            modifier = Modifier,
                        )
                    }
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Every path strokes from its start anchor", fontWeight = FontWeight.SemiBold)
                Text(
                    "Timing follows geometric path length; replay to watch again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { replayKey++ }) { Text("Replay") }
            }
        }
        ApiSignature("DrawIcon(vector, animation) + strokeReveal(start, direction, mode)")
    }
}

/** Global DrawIcon playground: anchor, direction, mode, duration, and tint, all live. */
@Composable
fun DrawIconPlayground(state: ShowcaseState) {
    var replayKey by remember { mutableIntStateOf(0) }
    val animation = remember(
        state.drawMode, state.drawDirection, state.pathStart, state.timing, state.staggerDelay,
        state.staggerOrder, state.strokeWidth,
    ) {
        VectorAnimationSpec {
            allPaths {
                strokeReveal(
                    start = state.pathStart,
                    direction = state.drawDirection,
                    interval = 0f..1f,
                    mode = state.drawMode,
                )
                // Constant stroke-width track: the only supported way to override the
                // vector's authored width from the DSL.
                strokeWidth(from = state.strokeWidth, to = state.strokeWidth)
            }
            if (state.staggerDelay > 0f) stagger(state.staggerDelay, state.staggerOrder)
        }
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "GP",
            title = "DrawIcon playground",
            description = "Every draw parameter, live: icon, anchor, direction, mode, duration, and tint.",
        )
        ShowcaseRow(
            preview = {
                key(replayKey, state.fromIconName, state.tintMode) {
                    DrawPreview(state = state, animation = animation)
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(state.fromIconName, fontWeight = FontWeight.SemiBold)
                Text(
                    "Changing any control replays the draw-on animation.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { replayKey++ }) { Text("Replay") }
            }
        }
        PlaygroundControls(state, secondIcon = false) {
            // DrawIcon animates a single vector: no second icon and no pair suggestions.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShowcaseDropdown("Mode", ShowcaseOptions.drawModes, state.drawMode) { state.drawMode = it }
                ShowcaseDropdown("Start", ShowcaseOptions.pathStarts, state.pathStart) { state.pathStart = it }
            }
            ShowcaseDropdown("Direction", ShowcaseOptions.drawDirections, state.drawDirection) { state.drawDirection = it }
            ShowcaseDropdown("Timing", ShowcaseOptions.timings, state.timing) { state.timing = it }
            LabeledSlider("Stroke width", state.strokeWidth, 0.5f..3f, "f") { state.strokeWidth = it }
        }
        ApiSignature("DrawIcon(vector, animation, tintFrom, tintTo, …)")
    }
}

/** One live DrawIcon wired to the shared playground state, honoring the tint mode. */
@Composable
private fun DrawPreview(state: ShowcaseState, animation: VectorAnimationSpec) {
    PreviewTile {
        val configuration = state.toConfiguration().copy(timing = state.timing)
        when (state.tintMode) {
            TintMode.NONE -> DrawIcon(
                vector = resolveIcon(state.fromIconName),
                animation = animation,
                animationSpec = tween(state.durationMillis.roundToInt()),
                configuration = configuration,
                contentDescription = "Playground icon",
                modifier = Modifier.layoutSize(54.dp),
            )
            TintMode.SOLID -> DrawIcon(
                vector = resolveIcon(state.fromIconName),
                animation = animation,
                animationSpec = tween(state.durationMillis.roundToInt()),
                configuration = configuration,
                tintFrom = TintBuilders.fromColor(state),
                tintTo = TintBuilders.toColor(state),
                contentDescription = "Playground icon",
                modifier = Modifier.layoutSize(54.dp),
            )
            TintMode.GRADIENT -> DrawIcon(
                vector = resolveIcon(state.fromIconName),
                animation = animation,
                animationSpec = tween(state.durationMillis.roundToInt()),
                configuration = configuration,
                tintFrom = TintBuilders.fromBrush(state),
                tintTo = TintBuilders.toBrush(state),
                contentDescription = "Playground icon",
                modifier = Modifier.layoutSize(54.dp),
            )
        }
    }
}
