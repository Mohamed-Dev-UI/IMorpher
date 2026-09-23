package com.mohamed.dev.ui.imorpher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import com.imorpher.vectormorph.compose.DrawIcon
import com.imorpher.vectormorph.compose.MorphIcon
import com.imorpher.vectormorph.compose.MorphVector
import com.imorpher.vectormorph.compose.VectorAnimationDefinition
import com.imorpher.vectormorph.compose.VectorAnimator
import com.imorpher.vectormorph.core.animation.VectorAnimation as vectorAnimation
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.mohamed.dev.ui.imorpher.ui.theme.IMorpherTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IMorpherTheme {
                MorphShowcaseScreen()
            }
        }
    }
}

@Composable
private fun MorphShowcaseScreen() {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 58.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ImageVector Morph", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Interactive examples for the Compose vector animation engine.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
                Text("No animated-vector XML resources", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }

            StateMorphShowcase()
            ManualMorphShowcase()
            DrawRevealShowcase()
            TimelineShowcase()
            ReducedMotionShowcase()

            Text(
                "The icons below are built as ImageVector values in Kotlin. Tap a control or move a slider to explore each API.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StateMorphShowcase() {
    var selected by remember { mutableStateOf(false) }
    ShowcaseCard(
        number = "01",
        title = "State-driven icon morph",
        subtitle = "MorphIcon owns progress and retargets smoothly when state changes.",
        api = "MorphIcon(from, to, selected)",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile {
                MorphIcon(
                    from = ShowcaseVectors.HomeOutline,
                    to = ShowcaseVectors.HomeFilled,
                    selected = selected,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    configuration = MorphConfiguration(pathMatching = PathMatchingStrategy.BY_NAME),
                    contentDescription = if (selected) "Home selected" else "Home not selected",
                    width = 52.dp,
                    height = 52.dp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Home", fontWeight = FontWeight.SemiBold)
                Text(if (selected) "Selected" else "Not selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
        }
    }
}

@Composable
private fun ManualMorphShowcase() {
    var progress by remember { mutableStateOf(0.25f) }
    ShowcaseCard(
        number = "02",
        title = "Manually scrub a morph",
        subtitle = "Use MorphVector when a gesture, pager, or scroll position owns progress.",
        api = "MorphVector(from, to, progress)",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile {
                MorphVector(
                    from = ShowcaseVectors.Play,
                    to = ShowcaseVectors.Pause,
                    progress = progress,
                    configuration = MorphConfiguration(missingPath = MissingPathBehavior.SCALE),
                    contentDescription = "Play to pause morph",
                    width = 52.dp,
                    height = 52.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Play  →  Pause", fontWeight = FontWeight.SemiBold)
                Slider(value = progress, onValueChange = { progress = it })
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DrawRevealShowcase() {
    var replayKey by remember { mutableIntStateOf(0) }
    ShowcaseCard(
        number = "03",
        title = "Draw an icon with a pen",
        subtitle = "Named paths get their own reveal interval, anchor, and direction.",
        api = "DrawIcon + path(…).strokeReveal(…)",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile {
                key(replayKey) {
                    DrawIcon(
                        vector = ShowcaseVectors.Spark,
                        animation = remember { SparkDrawAnimation },
                        animationSpec = tween(1100),
                        contentDescription = "Spark drawing animation",
                        modifier = Modifier.size(54.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Top-right to bottom-left", fontWeight = FontWeight.SemiBold)
                Text("Arc-length timing keeps the pen speed even.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { replayKey++ }) { Text("Replay") }
        }
    }
}

@Composable
private fun TimelineShowcase() {
    val coroutineScope = rememberCoroutineScope()
    val playhead = remember { Animatable(0f) }
    val progress = playhead.value
    val definition = remember { VectorAnimationDefinition(ShowcaseVectors.LayeredSpark, LayeredTimelineAnimation) }

    ShowcaseCard(
        number = "04",
        title = "Keyframes, groups, gradients, transforms",
        subtitle = "One reusable definition combines vector keyframes with named path and group tracks.",
        api = "VectorAnimator(definition, progress)",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile {
                VectorAnimator(
                    animation = definition,
                    progress = progress,
                    contentDescription = "Animated gradient spark",
                    width = 60.dp,
                    height = 60.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Scrub or play the timeline", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = progress,
                    onValueChange = { value -> coroutineScope.launch { playhead.snapTo(value) } },
                )
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                coroutineScope.launch {
                    playhead.snapTo(0f)
                    playhead.animateTo(1f, animationSpec = tween(1500))
                }
            }) { Text("Play timeline") }
            Button(onClick = { coroutineScope.launch { playhead.snapTo(0f) } }) { Text("Reset") }
        }
        Text(
            "The ray group staggers and rotates; the center fades, scales, and changes from a solid fill to a gradient.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReducedMotionShowcase() {
    var selected by remember { mutableStateOf(false) }
    ShowcaseCard(
        number = "05",
        title = "Reduced-motion behavior",
        subtitle = "The caller keeps touch and semantics; only visual motion changes.",
        api = "MorphIcon(motionPreference = …)",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile {
                MorphIcon(
                    from = ShowcaseVectors.Play,
                    to = ShowcaseVectors.Pause,
                    selected = selected,
                    motionPreference = MotionPreference.REDUCED,
                    contentDescription = "Reduced motion playback toggle",
                    width = 48.dp,
                    height = 48.dp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Short, gentle transition", fontWeight = FontWeight.SemiBold)
                Text("Toggle to preview the reduced setting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = selected, onCheckedChange = { selected = it })
        }
    }
}

@Composable
private fun ShowcaseCard(
    number: String,
    title: String,
    subtitle: String,
    api: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(number, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
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
    }
}

@Composable
private fun IconTile(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.size(82.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

private val SparkDrawAnimation = vectorAnimation {
    allPaths {
        strokeReveal(
            start = PathStart.TOP_RIGHT,
            direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            interval = 0f..0.75f,
            mode = DrawMode.FORWARD,
        )
    }
    path("core") {
        alpha(from = 0.15f, to = 1f, interval = 0.45f..1f)
        fill(mode = FillMode.RADIAL, interval = 0.5f..1f)
    }
    stagger(delay = 0.045f, order = DrawOrderStrategy.TOP_RIGHT_TO_BOTTOM_LEFT)
}

private val LayeredTimelineAnimation = vectorAnimation {
    at(0f) { alpha = 0.25f; scale = 0.72f; rotation = -28f }
    at(0.28f) { alpha = 1f; scale = 1.08f; rotation = 12f }
    at(1f) { alpha = 1f; scale = 1f; rotation = 0f }

    group("rays") {
        strokeReveal(
            start = PathStart.CENTER,
            direction = DrawDirection.CENTER_OUT,
            interval = 0f..0.7f,
        )
        strokeWidth(from = 0.7f, to = 1.7f, interval = 0f..0.8f)
        rotation(from = -12f, to = 12f, interval = 0.15f..0.8f)
    }
    path("core") {
        fill(mode = FillMode.RADIAL, interval = 0.25f..0.72f)
        gradient(
            from = BrushSpec.Solid(Color(0xFF536DFE)),
            to = BrushSpec.Linear(
                colors = listOf(Color(0xFF536DFE), Color(0xFF00BCD4), Color(0xFFFFC107)),
                stops = listOf(0f, 0.55f, 1f),
                startX = 3f,
                startY = 3f,
                endX = 21f,
                endY = 21f,
            ),
            interval = 0.35f..1f,
        )
    }
    stagger(delay = 0.055f, order = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT)
}

private object ShowcaseVectors {
    private val ink = Color(0xFF536DFE)

    val HomeOutline = ImageVectorBuilder("home-outline").apply {
        path(
            name = "home",
            data = listOf(
                move(3f, 10f), line(12f, 3f), line(21f, 10f), line(19f, 10f),
                line(19f, 21f), line(5f, 21f), line(5f, 10f), close,
            ),
            stroke = ink,
            strokeWidth = 1.8f,
        )
    }.build()

    val HomeFilled = ImageVectorBuilder("home-filled").apply {
        path(
            name = "home",
            data = listOf(
                move(2f, 10f), line(12f, 2.5f), line(22f, 10f), line(20f, 10f),
                line(20f, 21.5f), line(4f, 21.5f), line(4f, 10f), close,
            ),
            fill = ink,
        )
    }.build()

    val Play = ImageVectorBuilder("play").apply {
        path(
            name = "play",
            data = listOf(move(7f, 3f), line(21f, 12f), line(7f, 21f), close),
            fill = ink,
        )
    }.build()

    val Pause = ImageVectorBuilder("pause").apply {
        path(name = "bar-left", data = rect(5f, 3f, 10f, 21f), fill = ink)
        path(name = "bar-right", data = rect(14f, 3f, 19f, 21f), fill = ink)
    }.build()

    val Spark = ImageVectorBuilder("spark").apply {
        addRays()
        addCore()
    }.build()

    val LayeredSpark = ImageVectorBuilder("layered-spark").apply {
        addGroup("rays")
        addRays()
        clearGroup()
        addCore()
    }.build()

}

private class ImageVectorBuilder(name: String) {
    private val builder = androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    fun path(
        name: String,
        data: List<androidx.compose.ui.graphics.vector.PathNode>,
        fill: Color? = null,
        stroke: Color? = null,
        strokeWidth: Float = 1f,
    ) {
        builder.addPath(
            pathData = data,
            fill = fill?.let(::SolidColor),
            stroke = stroke?.let(::SolidColor),
            strokeLineWidth = strokeWidth,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            name = name,
        )
    }

    fun addRays() {
        val ink = Color(0xFF536DFE)
        val segments = listOf(
            listOf(move(12f, 1.8f), line(12f, 6f)),
            listOf(move(12f, 18f), line(12f, 22.2f)),
            listOf(move(1.8f, 12f), line(6f, 12f)),
            listOf(move(18f, 12f), line(22.2f, 12f)),
            listOf(move(4.5f, 4.5f), line(7.5f, 7.5f)),
            listOf(move(16.5f, 16.5f), line(19.5f, 19.5f)),
            listOf(move(19.5f, 4.5f), line(16.5f, 7.5f)),
            listOf(move(7.5f, 16.5f), line(4.5f, 19.5f)),
        )
        segments.forEachIndexed { index, nodes ->
            path(name = "ray-$index", data = nodes, stroke = ink, strokeWidth = 1.6f)
        }
    }

    fun addCore() {
        path(
            name = "core",
            data = listOf(
                move(12f, 7.5f),
                curve(14.485f, 7.5f, 16.5f, 9.515f, 16.5f, 12f),
                curve(16.5f, 14.485f, 14.485f, 16.5f, 12f, 16.5f),
                curve(9.515f, 16.5f, 7.5f, 14.485f, 7.5f, 12f),
                curve(7.5f, 9.515f, 9.515f, 7.5f, 12f, 7.5f),
                close,
            ),
            fill = Color(0xFF536DFE),
        )
    }

    fun addGroup(name: String) {
        builder.addGroup(
            name = name,
            rotate = 0f,
            pivotX = 12f,
            pivotY = 12f,
            scaleX = 1f,
            scaleY = 1f,
            translationX = 0f,
            translationY = 0f,
        )
    }

    fun clearGroup() = builder.clearGroup()
    fun build() = builder.build()
}

private fun move(x: Float, y: Float) = androidx.compose.ui.graphics.vector.PathNode.MoveTo(x, y)
private fun line(x: Float, y: Float) = androidx.compose.ui.graphics.vector.PathNode.LineTo(x, y)
private fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
    androidx.compose.ui.graphics.vector.PathNode.CurveTo(x1, y1, x2, y2, x3, y3)
private val close = androidx.compose.ui.graphics.vector.PathNode.Close
private fun rect(left: Float, top: Float, right: Float, bottom: Float) = listOf(
    move(left, top), line(right, top), line(right, bottom), line(left, bottom), close,
)
