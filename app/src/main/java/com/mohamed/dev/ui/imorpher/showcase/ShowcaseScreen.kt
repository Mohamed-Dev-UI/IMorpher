package com.mohamed.dev.ui.imorpher.showcase

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.imorpher.showcase.topics.DrawIconPlayground
import com.mohamed.dev.ui.imorpher.showcase.topics.KeyframeTimelineShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.ManualScrubShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.MissingPathShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.MorphIconPlayground
import com.mohamed.dev.ui.imorpher.showcase.topics.MorphVectorPlayground
import com.mohamed.dev.ui.imorpher.showcase.topics.MotionPreferenceShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.PenRevealShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.StaggerShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.StateMorphShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.TintTransitionShowcase
import com.mohamed.dev.ui.imorpher.showcase.topics.VectorAnimatorPlayground
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Debounce window for playground persistence writes. */
private const val persistenceDebounceMillis = 250L

/**
 * Root of the showcase app: home lists the available functions, a topic screen lists that
 * function's showcases, and each showcase renders full-screen. State is shared across all
 * screens and persisted across restarts.
 */
@Composable
fun ShowcaseScreen() {
    val context = LocalContext.current
    val state = remember { ShowcaseState().also { ShowcaseStatePersistence.load(context, it) } }
    var backStack by remember { mutableStateOf(listOf<Destination>(Destination.Home)) }
    val destination = backStack.last()

    BackHandler(enabled = backStack.size > 1) { backStack = backStack.dropLast(1) }

    // Persist playground tweaks, debounced: dragging a slider mutates state dozens of times
    // per second, and serializing + committing on every change would thrash the disk.
    // snapshotFlow conflates emissions, so this saves at most once per debounce window.
    LaunchedEffect(state) {
        snapshotFlow {
            listOf(
                state.fromIconName, state.toIconName, state.tintMode, state.durationMillis,
                state.primary, state.secondary, state.accent, state.motionPreference,
                state.pathMatching, state.missingPath, state.fallback, state.timing,
                state.colorSpace, state.keepRenderOrder, state.drawMode, state.drawDirection,
                state.pathStart, state.fillMode, state.staggerDelay, state.staggerOrder,
                state.startScale, state.endScale, state.startRotation, state.endRotation,
                state.strokeWidth, state.revealEnabled, state.colorEnabled,
            )
        }.collect {
            delay(persistenceDebounceMillis.milliseconds)
            ShowcaseStatePersistence.save(context, state)
        }
    }

    Scaffold { innerPadding ->
        when (destination) {
            Destination.Home -> HomeScreen(
                onTopicSelected = { topicId -> backStack = backStack + Destination.Topic(topicId) },
                modifier = Modifier.padding(innerPadding),
            )

            is Destination.Topic -> TopicScreen(
                topic = TopicRegistry.topic(destination.topicId),
                onShowcaseSelected = { itemId ->
                    backStack = backStack + Destination.Showcase(destination.topicId, itemId)
                },
                onBack = { backStack = backStack.dropLast(1) },
                modifier = Modifier.padding(innerPadding),
            )

            is Destination.Showcase -> ShowcaseDetailScreen(
                topic = TopicRegistry.topic(destination.topicId),
                item = TopicRegistry.item(destination.topicId, destination.itemId),
                state = state,
                onBack = { backStack = backStack.dropLast(1) },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** Lists one function's showcases; tapping one opens it full-screen. */
@Composable
private fun TopicScreen(
    topic: TopicDescriptor,
    onShowcaseSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("← All functions") }
                Text(topic.title, style = typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    topic.description,
                    style = typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(topic.items, key = { it.id }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowcaseSelected(item.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        item.title,
                        style = typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        item.description,
                        style = typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.id == topic.playgroundId) {
                        Text(
                            "Fully customizable",
                            style = typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** One showcase, full-screen, with a back affordance and a reset action at the end. */
@Composable
private fun ShowcaseDetailScreen(
    topic: TopicDescriptor,
    item: ShowcaseItem,
    state: ShowcaseState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onBack) { Text("← ${topic.title}") }
            Text(item.title, style = typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                item.description,
                style = typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ShowcaseContent(topicId = topic.id, itemId = item.id, state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                // Reset in place: recreating the activity would also drop the user
                // back to the home screen and lose the navigation stack.
                ShowcaseStatePersistence.clear(context)
                state.resetToDefaults()
            }) { Text("Reset playgrounds") }
        }
    }
}

/** Dispatches a showcase item id to its screen composable. */
@Composable
internal fun ShowcaseContent(topicId: TopicId, itemId: String, state: ShowcaseState) {
    check(ShowcaseContentDispatched(itemId)) { "Unknown showcase item id: $itemId" }
    when (itemId) {
        "morph-icon-state" -> StateMorphShowcase()
        "morph-icon-tint" -> TintTransitionShowcase()
        "morph-icon-motion" -> MotionPreferenceShowcase()
        "morph-icon-playground" -> MorphIconPlayground(state)
        "morph-vector-scrub" -> ManualScrubShowcase()
        "morph-vector-missing" -> MissingPathShowcase()
        "morph-vector-playground" -> MorphVectorPlayground(state)
        "vector-animator-keyframes" -> KeyframeTimelineShowcase()
        "vector-animator-stagger" -> StaggerShowcase()
        "vector-animator-playground" -> VectorAnimatorPlayground(state)
        "draw-icon-pen" -> PenRevealShowcase()
        "draw-icon-playground" -> DrawIconPlayground(state)
    }
}

/** Exhaustive id check so registry and dispatcher cannot drift apart silently. */
private fun ShowcaseContentDispatched(itemId: String): Boolean = itemId in setOf(
    "morph-icon-state", "morph-icon-tint", "morph-icon-motion", "morph-icon-playground",
    "morph-vector-scrub", "morph-vector-missing", "morph-vector-playground",
    "vector-animator-keyframes", "vector-animator-stagger", "vector-animator-playground",
    "draw-icon-pen", "draw-icon-playground",
)
