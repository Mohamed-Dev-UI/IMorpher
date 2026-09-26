package com.mohamed.dev.ui.imorpher.showcase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The square preview tile an icon animates inside. */
@Composable
fun PreviewTile(size: Int = 82, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(
            modifier = Modifier.size(size.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Card header for a showcase entry: number, title, and description. */
@Composable
fun ShowcaseCardHeader(number: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Monospace signature line shown at a showcase card's bottom edge. */
@Composable
fun ApiSignature(api: String) {
    Text(
        api,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        color = MaterialTheme.colorScheme.primary,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}

/** Standard container for one curated showcase. */
@Composable
fun ShowcaseCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** Groups a topic's showcases under one heading. */
@Composable
fun TopicHeader(title: String, api: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(api, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Row helper placing a preview tile beside explanatory content. */
@Composable
fun ShowcaseRow(preview: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        preview()
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/** Renders the shared playground controls compactly under one heading. */
@Composable
fun PlaygroundControls(
    state: ShowcaseState,
    secondIcon: Boolean = true,
    durationSlider: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconPicker(slot = if (!secondIcon) "Icon:" else "From icon:", selectedName = state.fromIconName) { state.fromIconName = it }
        // Single-vector APIs (DrawIcon, VectorAnimator) have no "to" icon; a picker for
        // one would suggest a parameter that does nothing.
        if (secondIcon) {
            IconPicker(slot = "To icon:", selectedName = state.toIconName) { state.toIconName = it }
        }
        TintSelector(state)
        // VectorAnimator is a manual progress driver; a duration spec would be a no-op.
        if (durationSlider) {
            LabeledSlider("Duration", state.durationMillis, 100f..1500f, "ms") { state.durationMillis = it }
        }
        extra()
    }
}

/** Ranked pair suggestions backed by the engine's plan compatibility score. */
@Composable
fun PairSuggestionsSection(state: ShowcaseState, onApply: (PairSuggestion) -> Unit) {
    val scope = rememberCoroutineScope()
    val ranking = remember { PairSuggestions.ranking(scope) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Suggested pairs (engine-ranked)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            ranking.isRunning -> Text(
                "Scoring pairs…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val best = ranking.suggestions.take(4)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    best.forEach { suggestion ->
                        SuggestionChip(
            label = "${suggestion.fromName} → ${suggestion.toName} · ${(suggestion.score * 100).toInt()}%",
                            selected = suggestion.fromName == state.fromIconName && suggestion.toName == state.toIconName,
                            onClick = { onApply(suggestion) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** Copies [snippet] to the clipboard and confirms with a toast. */
@Composable
fun CopySnippetButton(snippet: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("vectormorph", snippet))
        Toast.makeText(context, "Snippet copied", Toast.LENGTH_SHORT).show()
    }) {
        Text("Copy Kotlin snippet")
    }
}
