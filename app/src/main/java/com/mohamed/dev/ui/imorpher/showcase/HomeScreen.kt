package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.DrawIcon
import com.imorpher.vectormorph.compose.MorphIcon
import com.imorpher.vectormorph.compose.MorphVector
import com.imorpher.vectormorph.compose.VectorAnimationDefinition
import com.imorpher.vectormorph.compose.VectorAnimator
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Destinations used by the showcase navigation stack. */
sealed interface Destination {
    data object Home : Destination
    data class Topic(val topicId: TopicId) : Destination
    data class Showcase(val topicId: TopicId, val itemId: String) : Destination
}

/** Home screen: one card per public function, each with a live animated preview. */
@Composable
fun HomeScreen(
    onTopicSelected: (TopicId) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 58.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "ImageVector Morph",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Pick a function to explore its showcases.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(TopicRegistry.topics, key = { it.id }) { topic ->
            TopicCard(topic = topic, onClick = { onTopicSelected(topic.id) })
        }
    }
}

@Composable
private fun TopicCard(topic: TopicDescriptor, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
            ) {
                Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    when (topic.id) {
                        TopicId.MORPH_ICON -> MorphIconHomePreview()
                        TopicId.MORPH_VECTOR -> MorphVectorHomePreview()
                        TopicId.VECTOR_ANIMATOR -> AnimatorHomePreview()
                        TopicId.DRAW_ICON -> DrawIconHomePreview()
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(topic.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    topic.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${topic.items.size - 1} showcases · 1 playground",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    topic.api,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Toggle-driven morph preview for the MorphIcon card. */
@Composable
private fun MorphIconHomePreview() {
    var selected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            selected = !selected
        }
    }
    MorphIcon(
        from = IconCatalog.icon("ArrowUp1"),
        to = IconCatalog.icon("ArrowUp2Solid"),
        selected = selected,
        tintFrom = ShowcaseColors.indigo,
        tintTo = ShowcaseColors.cyan,
        contentDescription = null,
        width = 52.dp,
        height = 52.dp,
    )
}

/** Continuously scrubbed morph preview for the MorphVector card. */
@Composable
private fun MorphVectorHomePreview() {
    val playhead = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            playhead.snapTo(0f)
            playhead.animateTo(1f, tween(1400))
            delay(500)
        }
    }
    MorphVector(
        from = IconCatalog.icon("ArrowDown2Outline"),
        to = IconCatalog.icon("ArrowDown2Solid"),
        progress = playhead.value,
        tintFrom = ShowcaseColors.indigo,
        tintTo = ShowcaseColors.cyan,
        contentDescription = null,
        width = 52.dp,
        height = 52.dp,
    )
}

/** Keyframe timeline preview for the VectorAnimator card. */
@Composable
private fun AnimatorHomePreview() {
    val playhead = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            playhead.snapTo(0f)
            playhead.animateTo(1f, tween(1800))
            delay(600)
        }
    }
    val animation = remember {
        VectorAnimationSpec {
            at(0f) { scale = 0.7f; rotation = -24f; alpha = 0.4f }
            at(1f) { scale = 1f; rotation = 0f; alpha = 1f }
        }
    }
    VectorAnimator(
        animation = VectorAnimationDefinition(IconCatalog.icon("AddCircleOutline"), animation),
        progress = playhead.value,
        tintFrom = ShowcaseColors.indigo,
        tintTo = ShowcaseColors.cyan,
        contentDescription = null,
        width = 56.dp,
        height = 56.dp,
    )
}

/** Repeating pen-reveal preview for the DrawIcon card. */
@Composable
private fun DrawIconHomePreview() {
    var replayKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2800.milliseconds)
            replayKey++
        }
    }
    val animation = remember {
        VectorAnimationSpec {
            allPaths { strokeReveal(interval = 0f..1f) }
        }
    }
    key(replayKey) {
        DrawIcon(
            vector = IconCatalog.icon("AlgorithmOutline"),
            animation = animation,
            animationSpec = tween(2400),
            tintFrom = ShowcaseColors.indigo,
            tintTo = ShowcaseColors.cyan,
            contentDescription = null,
            width = 56.dp,
            height = 56.dp,
        )
    }
}
