package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.imorpher.vectormorph.compose.MorphContent
import com.imorpher.vectormorph.compose.MorphingContent
import com.imorpher.vectormorph.compose.MorphPainter
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.transition.ContentEffect
import com.imorpher.vectormorph.core.transition.ContentEndpoint
import com.imorpher.vectormorph.core.transition.ContentTransition
import com.imorpher.vectormorph.core.transition.WipeDirection
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.PreviewTile
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCard
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCardHeader
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseRow
import com.mohamed.dev.ui.imorpher.showcase.TopicHeader

/**
 * Content-endpoint transition topic: progress-driven slots, the state-driven wrapper, and
 * opaque-painter endpoints (docs/CONTENT-TRANSITIONS.md).
 */

/** Home-screen card animation: a wipe sweeping between two colored panels. */
@Composable
fun ContentTransitionHomePreview() {
    PreviewTile {
        MorphPainter(
            from = ColorPainter(Color(0xFF536DFE)),
            to = ColorPainter(Color(0xFF00C853)),
            progress = 0.5f,
            transition = ContentTransition.Wipe(direction = WipeDirection.TOP_TO_BOTTOM, feather = 0.15f),
            contentDescription = "Content transition preview",
            width = 52.dp,
            height = 52.dp,
        )
    }
}

/** All three transitions driven by a caller-owned slider. */
@Composable
fun ContentTransitionModesShowcase() {
    var progress by remember { mutableFloatStateOf(0f) }
    var mode by remember { mutableStateOf("Wipe") }

    val transition: ContentTransition = when (mode) {
        "Crossfade" -> ContentTransition.Crossfade()
        "FadeOver" -> ContentTransition.FadeOver()
        else -> ContentTransition.Wipe(
            direction = WipeDirection.LEFT_TO_RIGHT,
            feather = 0.15f,
            effects = listOf(ContentEffect.Scale(ContentEndpoint.TO, from = 0.85f, to = 1f)),
        )
    }

    ShowcaseCard {
        ShowcaseCardHeader(
            number = "01",
            title = "Progress-driven transition",
            description = "Endpoints without geometry composite over one caller-owned progress value.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile(size = 96) {
                    MorphContent(
                        progress = progress,
                        transition = transition,
                        from = { SlotPanel("FROM", Color(0xFF536DFE)) },
                        to = { SlotPanel("TO", Color(0xFF00C853)) },
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = progress,
                    onValueChange = { progress = it },
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("Wipe", "Crossfade", "FadeOver").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = mode == label,
                            onClick = { mode = label },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label) }
                    }
                }
            }
        }
        ApiSignature("MorphContent(progress, from, to, transition)")
    }
}

/** State-driven chaining with the MorphingIcon contract applied to slots. */
@Composable
fun ContentRetargetShowcase() {
    var active by remember { mutableStateOf("alpha") }

    ShowcaseCard {
        ShowcaseCardHeader(
            number = "02",
            title = "State-driven chaining",
            description = "MorphingContent morphs from the last settled slot content; quick taps retarget cleanly.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile(size = 96) {
                    MorphingContent(
                        target = active,
                        animationSpec = tween(350),
                        to = {
                            when (active) {
                                "alpha" -> SlotPanel("A", Color(0xFF536DFE), description = "content-alpha")
                                else -> SlotPanel("B", Color(0xFF00C853), description = "content-beta")
                            }
                        },
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Target: $active", fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("alpha", "beta").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = active == label,
                            onClick = { active = label },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) { Text(label) }
                    }
                }
            }
        }
        ApiSignature("MorphingContent(target, to, animationSpec)")
    }
}

/** Opaque painter endpoints: soup any Painters without geometry. */
@Composable
fun ContentPainterShowcase() {
    var progress by remember { mutableFloatStateOf(0f) }

    ShowcaseCard {
        ShowcaseCardHeader(
            number = "03",
            title = "Painter endpoints",
            description = "MorphPainter composites any two Painters — colors, bitmaps, whatever the host owns.",
        )
        ShowcaseRow(
            preview = {
                PreviewTile(size = 96) {
                    MorphPainter(
                        from = ColorPainter(Color(0xFFFF6E40)),
                        to = ColorPainter(Color(0xFF448AFF)),
                        progress = progress,
                        transition = ContentTransition.Crossfade(effects = listOf(ContentEffect.Fade(ContentEndpoint.FROM, from = 1f, to = 0f))),
                        contentDescription = "Painter transition",
                        width = 52.dp,
                        height = 52.dp,
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(value = progress, onValueChange = { progress = it })
            }
        }
        ApiSignature("MorphPainter(from, to, progress, transition)")
    }
}

/** One filled slot panel with optional content description for tests. */
@Composable
private fun SlotPanel(
    label: String,
    color: Color,
    description: String? = null,
) {
    val modifier = if (description == null) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxSize().semantics { contentDescription = description }
    }
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(4.dp),
        )
    }
}
