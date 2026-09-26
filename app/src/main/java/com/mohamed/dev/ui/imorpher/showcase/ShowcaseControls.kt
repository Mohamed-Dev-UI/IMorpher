package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Compact labeled slider used by every playground. */
@Composable
fun ShowcaseSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    format: (Float) -> String = { "${(it * 100).roundToInt()}%" },
) {
    Column {
        Text(
            text = "$label · ${format(value)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

/** Compact labeled dropdown for enum parameters. */
@Composable
fun <T> ShowcaseDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: ${optionLabel(selected)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A row of small swatch buttons for one of the three playground colors. */
@Composable
fun ColorSwatchRow(
    label: String,
    swatches: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp),
        )
        swatches.forEach { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

/** Fixed-range slider helper for durations and angles. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(
            "$label · ${value.roundToInt()}$suffix",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

/** Tint selector shared by all playgrounds: none, solid color, or gradient. */
@Composable
fun TintSelector(
    state: ShowcaseState
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShowcaseDropdown(
                label = "Tint",
                options = ShowcaseOptions.tintModes,
                selected = state.tintMode,
                optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } },
                onSelect = { state.tintMode = it },
            )
        }
        when (state.tintMode) {
            TintMode.NONE -> Unit
            TintMode.SOLID -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Both tint endpoints must be editable: tintFrom alone leaves the
                // destination color fixed at the default.
                ColorSwatchRow("From →", ShowcaseColors.swatches, state.primary) {
                    state.primary = it
                }
                ColorSwatchRow("To →", ShowcaseColors.swatches, state.secondary) {
                    state.secondary = it
                }
            }

            TintMode.GRADIENT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorSwatchRow("Start", ShowcaseColors.swatches, state.primary) {
                    state.primary = it
                }
                ColorSwatchRow("End", ShowcaseColors.swatches, state.secondary) {
                    state.secondary = it
                }
                // toBrush pairs accent with secondary; without a picker for accent the
                // third gradient color was unreachable from the UI.
                ColorSwatchRow("Accent", ShowcaseColors.swatches, state.accent) {
                    state.accent = it
                }
                Text(
                    "Gradient tint interpolates between both endpoint brushes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Colors the playground uses for icon paint and tint endpoints. */
@Composable
fun ColorPickers(state: ShowcaseState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ColorSwatchRow("Primary", ShowcaseColors.swatches, state.primary) { state.primary = it }
        ColorSwatchRow("Secondary", ShowcaseColors.swatches, state.secondary) {
            state.secondary = it
        }
    }
}

/** Labeled switch helper. */
@Composable
fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The playground icon picker: one compact tile showing the current selection; tapping it
 * opens a dialog with the filterable grid of every IMorpherIcons entry. This keeps the
 * playgrounds compact — the full grid is only laid out while actively choosing.
 */
@Composable
fun IconPicker(slot: String, selectedName: String, onSelect: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            slot,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 1.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { showPicker = true },
        ) {
            Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = resolveIcon(selectedName),
                    contentDescription = selectedName,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Text(selectedName, style = MaterialTheme.typography.labelMedium)
    }
    if (showPicker) {
        IconPickerDialog(
            slot = slot,
            selectedName = selectedName,
            onSelect = {
                onSelect(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun IconPickerDialog(
    slot: String,
    selectedName: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kindFilter by remember { mutableStateOf<IconKind?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick $slot icon") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShowcaseDropdown(
                    label = "Filter",
                    options = listOf(null, IconKind.OUTLINE, IconKind.SOLID, IconKind.PLAIN),
                    selected = kindFilter,
                    optionLabel = {
                        it?.name?.lowercase()?.replaceFirstChar { c -> c.titlecase() } ?: "All"
                    },
                    onSelect = { kindFilter = it },
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 1.dp,
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                    ) {
                        items(IconCatalog.byKind(kindFilter), key = { it.name }) { entry ->
                            IconTile(
                                entry = entry,
                                isSelected = entry.name == selectedName,
                                onClick = { onSelect(entry.name) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun IconTile(entry: IconEntry, isSelected: Boolean, onClick: () -> Unit) {
    val border = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(border)
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resolveIcon(entry.name),
            contentDescription = entry.name,
            tint = Color.Unspecified,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Resolves a catalog icon for preview tiles without touching playground state. */
internal fun resolveIcon(name: String): ImageVector = IconCatalog.icon(name)
