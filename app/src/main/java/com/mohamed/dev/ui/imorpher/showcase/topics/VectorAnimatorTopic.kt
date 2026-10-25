package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.VectorAnimationDefinition
import com.imorpher.vectormorph.compose.VectorAnimator
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.ConfigurationPanel
import com.mohamed.dev.ui.imorpher.showcase.CopySnippetButton
import com.mohamed.dev.ui.imorpher.showcase.DrawConflictHint
import com.mohamed.dev.ui.imorpher.showcase.LabeledSlider
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestions
import com.mohamed.dev.ui.imorpher.showcase.PlaygroundControls
import com.mohamed.dev.ui.imorpher.showcase.PreviewTile
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCard
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCardHeader
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseDropdown
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseOptions
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseRow
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
import com.mohamed.dev.ui.imorpher.showcase.TintBuilders
import com.mohamed.dev.ui.imorpher.showcase.TintMode
import com.mohamed.dev.ui.imorpher.showcase.resolveIcon
import com.mohamed.dev.ui.imorpher.showcase.toAlignment
import com.mohamed.dev.ui.imorpher.showcase.toConfiguration
import kotlinx.coroutines.launch
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec

/** Caller-owned playhead: scrub or play a keyframed timeline. */
@Composable
fun KeyframeTimelineShowcase() {
    val coroutineScope = rememberCoroutineScope()
    val playhead = remember { Animatable(0f) }
    val progress = playhead.value
    val animation = remember {
        VectorAnimationSpec {
            at(0f) { alpha = 0.25f; scale = 0.72f; rotation = -28f }
            at(0.28f) { alpha = 1f; scale = 1.08f; rotation = 12f }
            at(1f) { alpha = 1f; scale = 1f; rotation = 0f }
        }
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "01",
            title = "Keyframe timeline",
            description = "One reusable definition combines vector keyframes with a caller-owned playhead.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    VectorAnimator(
                        animation = VectorAnimationDefinition(
                            resolveIcon("AddCircleOutline"),
                            animation
                        ),
                        progress = progress,
                        tintFrom = com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.indigo,
                        tintTo = com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.cyan,
                        contentDescription = "Keyframe timeline icon",
                        width = 60.dp,
                        height = 60.dp,
                    )
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scrub or play the timeline", fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = progress,
                    onValueChange = { value -> coroutineScope.launch { playhead.snapTo(value) } },
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        coroutineScope.launch {
                            playhead.snapTo(0f)
                            playhead.animateTo(1f, animationSpec = tween(1500))
                        }
                    }) { Text("Play") }
                    Button(onClick = { coroutineScope.launch { playhead.snapTo(0f) } }) { Text("Reset") }
                }
            }
        }
        ApiSignature("VectorAnimator(definition, progress, tintFrom, tintTo)")
    }
}

/** Stagger offsets path timelines in the configured geometric order. */
@Composable
fun StaggerShowcase() {
    var replayKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var order by remember { mutableStateOf(DrawOrderStrategy.CLOCKWISE) }
    val animation = remember(order) {
        VectorAnimationSpec {
            allPaths {
                alpha(from = 0.15f, to = 1f, interval = 0.1f..0.8f)
            }
            stagger(delay = 0.08f, order = order)
        }
    }
    // The playhead must actually move: VectorAnimator is a fully manual driver, so a
    // constant progress = 1f shows one static frame and the stagger is invisible.
    val playhead = remember { Animatable(0f) }
    LaunchedEffect(replayKey, order) {
        playhead.snapTo(0f)
        playhead.animateTo(1f, tween(1200))
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "02",
            title = "Staggered paths",
            description = "stagger(delay, order) fans each path's clock around the chosen order.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    VectorAnimator(
                        animation = VectorAnimationDefinition(
                            resolveIcon("ArchiveOutline"),
                            animation
                        ),
                        progress = playhead.value,
                        contentDescription = "Staggered icon",
                        width = 56.dp,
                        height = 56.dp,
                    )
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        DrawOrderStrategy.CLOCKWISE,
                        DrawOrderStrategy.COUNTER_CLOCKWISE,
                        DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
                    ).forEach { option ->
                        Button(onClick = { order = option }) {
                            Text(
                                option.name.lowercase().replaceFirstChar { it.titlecase() },
                                maxLines = 1,
                            )
                        }
                    }
                    Button(onClick = { replayKey++ }) { Text("Replay") }
                }
            }
        }
        ApiSignature("VectorAnimator(definition, progress) + stagger(delay, order)")
    }
}

/** One fill mode chosen per reveal; a dropdown retargets the sweep direction. */
@Composable
fun FillRevealShowcase() {
    var mode by remember { mutableStateOf(FillMode.RADIAL) }
    var direction by remember { mutableStateOf(DrawDirection.LEFT_TO_RIGHT) }
    val animation = remember(mode, direction) {
        VectorAnimationSpec {
            allPaths {
                fill(
                    mode = mode,
                    // FADE ignores direction; clockwise angles are sweep-only; every other
                    // mode takes the edge/corner directions.
                    direction = when {
                        mode == FillMode.FADE -> null
                        mode == FillMode.SWEEP && direction.isClockwiseStyle() ->
                            direction
                        mode == FillMode.SWEEP -> DrawDirection.CLOCKWISE
                        direction.isClockwiseStyle() -> DrawDirection.AUTO
                        else -> direction
                    },
                )
            }
        }
    }
    // The playhead must actually move: VectorAnimator is a fully manual driver, so a
    // constant progress = 1f shows one static frame.
    val coroutineScope = rememberCoroutineScope()
    val playhead = remember { Animatable(0f) }
    LaunchedEffect(mode, direction) {
        playhead.snapTo(0f)
        playhead.animateTo(1f, tween(1600))
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "03",
            title = "Fill reveal modes",
            description = "Every fill mode plus a travel direction over one chosen pair.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    VectorAnimator(
                        animation = VectorAnimationDefinition(
                            resolveIcon("AlgorithmSolid"),
                            animation,
                        ),
                        progress = playhead.value,
                        tintFrom = com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.indigo,
                        tintTo = com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.cyan,
                        contentDescription = "Fill reveal icon",
                        width = 56.dp,
                        height = 56.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FillMode.entries.forEach { option ->
                        Button(onClick = { mode = option }) {
                            Text(
                                option.name.lowercase().replaceFirstChar { it.titlecase() },
                                maxLines = 1,
                            )
                        }
                    }
                }
                ShowcaseDropdown(
                    "Direction",
                    listOf(
                        DrawDirection.AUTO,
                        DrawDirection.LEFT_TO_RIGHT,
                        DrawDirection.RIGHT_TO_LEFT,
                        DrawDirection.TOP_TO_BOTTOM,
                        DrawDirection.BOTTOM_TO_TOP,
                        DrawDirection.CLOCKWISE,
                        DrawDirection.COUNTER_CLOCKWISE,
                    ),
                    direction,
                ) { direction = it }
                // Live spelling of the same clamps fill() above applies, so the remap is
                // never silent in the UI.
                val applied = when {
                    mode == FillMode.FADE -> "not used (FADE covers the whole path)"
                    mode == FillMode.SWEEP && direction.isClockwiseStyle() -> direction.name
                    mode == FillMode.SWEEP -> "${DrawDirection.CLOCKWISE.name} (SWEEP is clockwise-only)"
                    direction.isClockwiseStyle() -> "AUTO (${mode.name} takes edge/corner directions)"
                    else -> direction.name
                }
                Text(
                    "Direction applied: $applied",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    coroutineScope.launch {
                        playhead.snapTo(0f)
                        playhead.animateTo(1f, tween(1600))
                    }
                }) { Text("Replay") }
            }
        }
        ApiSignature("VectorAnimator(definition, progress) + fill(mode, direction)")
    }
}

private fun DrawDirection.isClockwiseStyle(): Boolean =
    this == DrawDirection.CLOCKWISE || this == DrawDirection.COUNTER_CLOCKWISE

/** gradient()/strokeGradient() endpoints: fill and stroke paints move between brushes. */
@Composable
fun GradientTimelineShowcase() {
    val coroutineScope = rememberCoroutineScope()
    val playhead = remember { Animatable(0f) }
    val progress = playhead.value
    val fillAnimation = remember {
        VectorAnimationSpec {
            allPaths {
                gradient(
                    from = BrushSpec.Solid(com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.indigo),
                    to = BrushSpec.Linear(
                        colors = listOf(
                            com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.indigo,
                            com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.cyan,
                        ),
                        stops = null,
                        startX = 0f,
                        startY = 0f,
                        endX = 24f,
                        endY = 24f,
                    ),
                    interval = 0.1f..0.9f,
                )
            }
        }
    }
    val strokeAnimation = remember {
        VectorAnimationSpec {
            allPaths {
                strokeGradient(
                    from = BrushSpec.Solid(com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.slate),
                    to = BrushSpec.Linear(
                        colors = listOf(
                            com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.cyan,
                            com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors.coral,
                        ),
                        stops = null,
                        startX = 0f,
                        startY = 0f,
                        endX = 24f,
                        endY = 24f,
                    ),
                    interval = 0.1f..0.9f,
                )
            }
        }
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "04",
            title = "Gradient endpoints",
            description = "gradient() and strokeGradient() move fill and stroke paints between brushes.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile {
                    VectorAnimator(
                        animation = VectorAnimationDefinition(
                            resolveIcon("AlgorithmSolid"),
                            fillAnimation,
                        ),
                        progress = progress,
                        contentDescription = "Gradient timeline icon",
                        width = 56.dp,
                        height = 56.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Fill: solid indigo → diagonal linear ramp", fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = progress,
                    onValueChange = { value -> coroutineScope.launch { playhead.snapTo(value) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        coroutineScope.launch {
                            playhead.snapTo(0f)
                            playhead.animateTo(1f, animationSpec = tween(1500))
                        }
                    }) { Text("Play") }
                    Button(onClick = { coroutineScope.launch { playhead.snapTo(0f) } }) { Text("Reset") }
                }
            }
        }
        ShowcaseRow(
            preview = {
                PreviewTile {
                    VectorAnimator(
                        animation = VectorAnimationDefinition(
                            resolveIcon("AlgorithmOutline"),
                            strokeAnimation,
                        ),
                        progress = progress,
                        contentDescription = "Gradient stroke icon",
                        width = 56.dp,
                        height = 56.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stroke: slate → cyan/coral ramp", fontWeight = FontWeight.SemiBold)
                Text(
                    "One shared playhead drives both timelines; gradient↔gradient and solid↔gradient interpolate.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ApiSignature("VectorAnimator(definition, progress) + gradient(from, to) / strokeGradient(from, to)")
    }
}

/** Global VectorAnimator playground: compose keyframe + reveal + color tracks from state. */
@Composable
fun VectorAnimatorPlayground(state: ShowcaseState) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animation = remember(
        state.startScale, state.endScale, state.startRotation, state.endRotation,
        state.drawMode, state.drawDirection, state.pathStart, state.staggerDelay,
        state.staggerOrder, state.primary, state.secondary, state.revealEnabled, state.colorEnabled,
    ) {
        VectorAnimationSpec {
            at(0f) { scale = state.startScale; rotation = state.startRotation }
            at(1f) { scale = state.endScale; rotation = state.endRotation }
            if (state.revealEnabled) {
                allPaths {
                    strokeReveal(
                        start = state.pathStart,
                        direction = state.drawDirection,
                        interval = 0f..0.75f,
                        mode = state.drawMode,
                    )
                }
            }
            if (state.colorEnabled) {
                allPaths {
                    // Emit both channels: filled paths take the fill track, stroke-only
                    // paths the stroke track (the engine ignores the inapplicable one,
                    // so a stroked chevron is recolored instead of being filled).
                    color(from = state.primary, to = state.secondary, interval = 0.2f..0.95f)
                    strokeColor(from = state.primary, to = state.secondary, interval = 0.2f..0.95f)
                }
            }
            if (state.staggerDelay > 0f) stagger(state.staggerDelay, state.staggerOrder)
        }
    }
    ShowcaseCard {
        ShowcaseCardHeader(
            number = "GP",
            title = "VectorAnimator playground",
            description = "Compose keyframes, stroke reveals, color tracks, and stagger from every control.",
        )
        ShowcaseRow(
            preview = {
                AnimatorPreview(
                    state = state,
                    animation = animation,
                    progress = progress
                )
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("${state.fromIconName} (definition vector)", fontWeight = FontWeight.SemiBold)
                LabeledSlider("Progress", progress, 0f..1f, "%") { progress = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        state.revealEnabled = state.revealEnabled.not()
                    }) { Text("Toggle reveal") }
                    Button(onClick = {
                        state.colorEnabled = state.colorEnabled.not()
                    }) { Text("Toggle color") }
                }
                Text(
                    "reveal: ${state.revealEnabled} · color track: ${state.colorEnabled}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PlaygroundControls(state, secondIcon = false) {
            // VectorAnimator animates ONE vector driven by caller progress: no second
            // icon, and pair suggestions would be unrelated here. The shared panel
            // hides duration/spec (no animationSpec parameter exists) but keeps all
            // MorphConfiguration knobs, including the stagger block wired below.
            ConfigurationPanel(
                state,
                pathNames = PairSuggestions.pathNamesFor(state.fromIconName, state.fromIconName),
                includeSpec = false,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShowcaseDropdown(
                    "Mode",
                    ShowcaseOptions.drawModes,
                    state.drawMode
                ) { state.drawMode = it }
                ShowcaseDropdown(
                    "Start",
                    ShowcaseOptions.pathStarts,
                    state.pathStart
                ) { state.pathStart = it }
            }
            ShowcaseDropdown(
                "Direction",
                ShowcaseOptions.drawDirections,
                state.drawDirection
            ) { state.drawDirection = it }
            // Conflicts between the Mode/Start/Direction pickers above resolve into the
            // state's warnings and surface here while they disagree.
            DrawConflictHint(state)
            LabeledSlider("Start scale", state.startScale, 0.2f..1.5f, "×") {
                state.startScale = it
            }
            LabeledSlider("End scale", state.endScale, 0.2f..1.5f, "×") { state.endScale = it }
            LabeledSlider(
                "Start rotation",
                state.startRotation,
                -180f..180f,
                "°"
            ) { state.startRotation = it }
            LabeledSlider("End rotation", state.endRotation, -180f..180f, "°") {
                state.endRotation = it
            }
            CopySnippetButton(
                SnippetGenerator.generate(
                    state,
                    SnippetGenerator.Target.VECTOR_ANIMATOR
                )
            )
        }
        ApiSignature("VectorAnimator(definition, progress, tintFrom, tintTo, …)")
    }
}

/** One live VectorAnimator wired to the shared playground state, honoring the tint mode. */
@Composable
internal fun AnimatorPreview(
    state: ShowcaseState,
    animation: VectorAnimationSpec,
    progress: Float,
) {
    PreviewTile {
        val definition = VectorAnimationDefinition(
            resolveIcon(state.fromIconName),
            animation,
            state.toConfiguration()
        )
        when (state.tintMode) {
            TintMode.NONE -> VectorAnimator(
                animation = definition,
                progress = progress,
                contentDescription = "Playground icon",
                width = (state.iconWidthDp ?: 56f).dp,
                height = (state.iconHeightDp ?: 56f).dp,
                alignment = state.toAlignment(),
            )

            TintMode.SOLID -> VectorAnimator(
                animation = definition,
                progress = progress,
                tintFrom = TintBuilders.fromColor(state),
                tintTo = TintBuilders.toColor(state),
                contentDescription = "Playground icon",
                width = (state.iconWidthDp ?: 56f).dp,
                height = (state.iconHeightDp ?: 56f).dp,
                alignment = state.toAlignment(),
            )

            TintMode.GRADIENT -> VectorAnimator(
                animation = definition,
                progress = progress,
                tintFrom = TintBuilders.fromBrush(state),
                tintTo = TintBuilders.toBrush(state),
                contentDescription = "Playground icon",
                width = (state.iconWidthDp ?: 56f).dp,
                height = (state.iconHeightDp ?: 56f).dp,
                alignment = state.toAlignment(),
            )
        }
    }
}
