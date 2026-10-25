package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import kotlin.math.roundToInt

/** Fallback path names used before the compiled pair reports its own. */
internal val DefaultPathNames = listOf("path[1]", "path[2]", "path[3]")

/**
 * The playground-wide configuration panel: every `MorphConfiguration` parameter, the
 * geometry budget, sizing/placement, motion, and the debug overlay set, each behind one
 * collapsible section. All four playgrounds render this exact panel, so coverage cannot
 * drift between them; composable-API knobs (icons, tint, per-playground DSL extras)
 * stay outside it under [PlaygroundControls]. Mutates [state] directly like every other
 * showcase control (persistence is debounced upstream in ShowcaseScreen). [pathNames]
 * feeds the per-path editors (anchors, mappings, custom order) and comes from the
 * compiled pair; [includeSpec] hides the duration/spec pair for the manual-progress
 * APIs (MorphVector and VectorAnimator take no `animationSpec` parameter).
 */
@Composable
fun ConfigurationPanel(
    state: ShowcaseState,
    pathNames: List<String> = DefaultPathNames,
    includeSpec: Boolean = true,
) {
    var expanded by remember { mutableStateOf(setOf("Matching", "Start & direction")) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val sections = buildSections(state, pathNames, includeSpec)
        sections.forEachIndexed { index, section ->
            val open = section.title in expanded
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (open) expanded - section.title else expanded + section.title
                    }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    if (open) "▾" else "▸",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(20.dp),
                )
                Text(section.title, style = MaterialTheme.typography.titleSmall)
            }
            AnimatedVisibility(visible = open) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.content()
                }
            }
            if (index != sections.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** One panel section: its title and the controls revealed by expanding it. */
private class Section(val title: String, val content: @Composable () -> Unit)

private fun buildSections(
    state: ShowcaseState,
    pathNames: List<String>,
    includeSpec: Boolean,
): List<Section> {
    return listOf(
        section("Matching") {
            ShowcaseDropdown("Match", ShowcaseOptions.pathMatchings, state.pathMatching) { state.pathMatching = it }
            if (state.pathMatching == com.imorpher.vectormorph.core.model.PathMatchingStrategy.CUSTOM) {
                EditableList(
                    label = "Mappings (source → target)",
                    entries = state.customMappings,
                    emptyLabel = "No explicit mappings — defaults would match nothing",
                    onRemove = { removed -> state.customMappings = state.customMappings.filter { it != removed } },
                    rowLabel = { "${it.source} → ${it.target}" },
                )
                MappingAddRow(pathNames) { entry ->
                    state.customMappings = state.customMappings + entry
                }
            }
        },
        section("Start & direction") {
            ShowcaseDropdown("Start point", ShowcaseOptions.startPoints, state.startPoint) { state.startPoint = it }
            ShowcaseDropdown("Path direction", ShowcaseOptions.pathDirections, state.pathDirection) {
                state.pathDirection = it
            }
            ShowcaseDropdown("Travel policy", ShowcaseOptions.directionTraversals, state.directionTraversal) {
                state.directionTraversal = it
            }
            EditableList(
                label = "Draw anchors (pen start arc fraction)",
                entries = state.customAnchors,
                emptyLabel = "No custom anchors",
                onRemove = { removed -> state.customAnchors = state.customAnchors.filter { it != removed } },
                rowLabel = { "${it.pathName} · ${(it.fraction * 100).roundToInt()}%" },
            )
            AnchorAddRow(pathNames) { entry ->
                state.customAnchors = state.customAnchors + entry
            }
        },
        section("Draw order & stagger") {
            ShowcaseDropdown("Draw order", ShowcaseOptions.drawOrders, state.drawOrder) { state.drawOrder = it }
            if (state.drawOrder == DrawOrderStrategy.CUSTOM) {
                EditableList(
                    label = "Custom draw order",
                    entries = state.customDrawOrderNames,
                    emptyLabel = "Custom order is empty",
                    onRemove = { removed -> state.customDrawOrderNames = state.customDrawOrderNames.filter { it != removed } },
                )
                OrderAddRow(pathNames) { name ->
                    state.customDrawOrderNames = state.customDrawOrderNames + name
                }
            }
            LabeledSwitch("Keep render order", state.keepRenderOrder) { state.keepRenderOrder = it }
            ShowcaseSlider(
                label = "Stagger delay",
                value = state.staggerDelay,
                onValueChange = { state.staggerDelay = it },
                valueRange = 0f..0.3f,
                format = { "%.2f".format(it) },
            )
            ShowcaseDropdown("Stagger order", ShowcaseOptions.staggerOrders, state.staggerOrder) {
                state.staggerOrder = it
            }
        },
        section("Paint & color") {
            ShowcaseDropdown("Colors", ShowcaseOptions.colorSpaces, state.colorSpace) { state.colorSpace = it }
            ShowcaseDropdown("Stroke ↔ fill", ShowcaseOptions.strokeFills, state.strokeFill) { state.strokeFill = it }
        },
        section("Fill reveal") {
            ShowcaseDropdown("Fill mode", ShowcaseOptions.fillModes, state.fillMode) { state.fillMode = it }
            ShowcaseDropdown("Fill direction", ShowcaseOptions.fillDirections, state.defaultFillDirection) {
                state.defaultFillDirection = it
            }
        },
        section("Missing & fallback") {
            ShowcaseDropdown("Missing paths", ShowcaseOptions.missingPaths, state.missingPath) { state.missingPath = it }
            ShowcaseDropdown("Fallback", ShowcaseOptions.fallbacks, state.fallback) { state.fallback = it }
        },
        section("Timing & easing") {
            ShowcaseDropdown("Timing", ShowcaseOptions.timings, state.timing) { state.timing = it }
            if (includeSpec) {
                ShowcaseSlider(
                    label = "Duration",
                    value = state.durationMillis,
                    onValueChange = { state.durationMillis = it },
                    valueRange = 100f..1500f,
                    format = { "${it.roundToInt()} ms" },
                )
                ShowcaseDropdown("Spec", ShowcaseOptions.specKinds, state.specKind) { state.specKind = it }
            } else {
                Text(
                    "This API owns no duration spec (progress is driven directly).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        section("Motion") {
            ShowcaseDropdown("Motion", ShowcaseOptions.motionPreferences, state.motionPreference) {
                state.motionPreference = it
            }
        },
        section("Geometry budget") {
            val enabled = state.segmentBudget != null
            LabeledSwitch("Limit cubic segments", enabled) { on ->
                state.segmentBudget = if (on) 16 else null
            }
            state.segmentBudget?.let { budget ->
                ShowcaseSlider(
                    label = "Segment budget",
                    value = budget.toFloat(),
                    onValueChange = { state.segmentBudget = it.roundToInt() },
                    valueRange = 4f..48f,
                    format = { "${it.roundToInt()} cubic" },
                )
                ShowcaseSlider(
                    label = "Simplification tolerance",
                    value = state.simplificationTolerance,
                    onValueChange = { state.simplificationTolerance = it },
                    valueRange = 0.1f..1f,
                    format = { "%.2f vp".format(it) },
                )
            }
            Text(
                "Lower budget = cheaper interpolation, softer detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        section("Viewport") {
            ShowcaseDropdown("Normalization", ShowcaseOptions.viewportNormalizations, state.viewportNormalization) {
                state.viewportNormalization = it
            }
        },
        section("Size & placement") {
            val sized = state.iconWidthDp != null
            LabeledSwitch("Override size", sized) { on ->
                state.iconWidthDp = if (on) 48f else null
                state.iconHeightDp = if (on) 48f else null
            }
            if (sized) {
                ShowcaseSlider(
                    label = "Size",
                    value = state.iconWidthDp ?: 48f,
                    onValueChange = {
                        state.iconWidthDp = it
                        state.iconHeightDp = it
                    },
                    valueRange = 20f..96f,
                    format = { "${it.roundToInt()} dp" },
                )
            }
            ShowcaseDropdown("Placement", ShowcaseOptions.alignments, state.alignment) { state.alignment = it }
        },
        section("Debug overlays") {
            LabeledSwitch("Enable overlays", state.debugEnabled) { state.debugEnabled = it }
            if (state.debugEnabled) {
                LabeledSwitch("Path points", state.showPathPoints) { state.showPathPoints = it }
                LabeledSwitch("Path numbers", state.showPathNumbers) { state.showPathNumbers = it }
                LabeledSwitch("Bounding boxes", state.showBoundingBoxes) { state.showBoundingBoxes = it }
                LabeledSwitch("Centroids", state.showCentroids) { state.showCentroids = it }
                LabeledSwitch("Direction arrows", state.showDirection) { state.showDirection = it }
                LabeledSwitch("Start anchors", state.showStartPoints) { state.showStartPoints = it }
                LabeledSwitch("Path matching", state.showPathMatching) { state.showPathMatching = it }
                ShowcaseSlider(
                    label = "Marker arc fraction",
                    value = state.markerArcFraction,
                    onValueChange = { state.markerArcFraction = it },
                    valueRange = 0f..1f,
                    format = { "%.2f".format(it) },
                )
                Text(
                    "Reserved in 0.2.0: stored on MorphDebugOptions, not rendered yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun section(title: String, content: @Composable () -> Unit): Section = Section(title, content)

/** Labeled list of editable rows with per-row removal plus a shared add affordance. */
@Composable
private fun <T> EditableList(
    label: String,
    entries: List<T>,
    emptyLabel: String,
    onRemove: (T) -> Unit,
    rowLabel: (T) -> String = { it.toString() },
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Text(
                emptyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rowLabel(entry), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun MappingAddRow(pathNames: List<String>, onAdd: (PathMappingEdit) -> Unit) {
    val names = pathNames.ifEmpty { DefaultPathNames }
    var source by remember { mutableStateOf(names.first()) }
    var target by remember { mutableStateOf(names.last()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShowcaseDropdown("From", names, source) { source = it }
            ShowcaseDropdown("Into", names, target) { target = it }
        }
        TextButton(onClick = { onAdd(PathMappingEdit(source, target)) }, enabled = source != target) {
            Text("+ Add mapping")
        }
    }
}

@Composable
private fun AnchorAddRow(pathNames: List<String>, onAdd: (AnchorEntry) -> Unit) {
    val names = pathNames.ifEmpty { DefaultPathNames }
    var name by remember { mutableStateOf(names.first()) }
    var fraction by remember { mutableStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseDropdown("Path", names, name) { name = it }
            Slider(
                value = fraction,
                onValueChange = { fraction = it },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
        }
        TextButton(onClick = { onAdd(AnchorEntry(name, fraction)) }) {
            Text("+ Add anchor at %.2f".format(fraction))
        }
    }
}

@Composable
private fun OrderAddRow(pathNames: List<String>, onAdd: (String) -> Unit) {
    val names = pathNames.ifEmpty { DefaultPathNames }
    var name by remember { mutableStateOf(names.first()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShowcaseDropdown("Path", names, name) { name = it }
        TextButton(onClick = { onAdd(name) }) { Text("+ Add order step") }
    }
}
