package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** Identifies one public library function on the home screen and in navigation. */
enum class TopicId { DIAGNOSTICS, MORPH_ICON, MORPH_VECTOR, VECTOR_ANIMATOR, DRAW_ICON }

/**
 * One showcase screen inside a topic. [id] keys navigation; [title]/[description] render in
 * the topic list and on the detail screen's header.
 */
data class ShowcaseItem(
    val id: String,
    val title: String,
    val description: String,
)

/** Home-screen card for one public function. */
data class TopicDescriptor(
    val id: TopicId,
    val title: String,
    val api: String,
    val description: String,
    val items: List<ShowcaseItem>,
    val previewIcon: ImageVector,
    /** The playground item's id, or null for topics without a state-driven playground. */
    val playgroundId: String?,
)

/** Registry consulted by home, topic list, and navigation dispatchers. */
object TopicRegistry {

    val topics: List<TopicDescriptor> = listOf(
        TopicDescriptor(
            id = TopicId.DIAGNOSTICS,
            title = "Diagnostics",
            api = "planMorph · MorphCaches · Codec",
            description = "Look inside the engine: planning reports, memory, persisted timelines, and custom callbacks.",
            previewIcon = IconCatalog.icon("SealOutline"),
            playgroundId = null,
            items = listOf(
                ShowcaseItem(
                    id = "diagnostics-plan-report",
                    title = "Plan report",
                    description = "Pair kinds, compatibility score, fallback flags, and validation straight from planMorph.",
                ),
                ShowcaseItem(
                    id = "diagnostics-caches",
                    title = "Plan caches",
                    description = "Cold-plan timing and the live plan cache: entries, estimated bytes, and trim.",
                ),
                ShowcaseItem(
                    id = "diagnostics-codec",
                    title = "Codec round-trip",
                    description = "Encode a timeline, decode the bytes, and replay what persisted.",
                ),
                ShowcaseItem(
                    id = "diagnostics-custom",
                    title = "Custom geometry",
                    description = "A CustomVectorAnimation callback shapes the stroke per frame.",
                ),
            ),
        ),
        TopicDescriptor(
            id = TopicId.MORPH_ICON,
            title = "MorphIcon",
            api = "MorphIcon(from, to, selected, …)",
            description = "Selected/unselected icon that owns its own progress and retargets smoothly when state changes quickly.",
            previewIcon = IconCatalog.icon("ArrowUp1"),
            playgroundId = "morph-icon-playground",
            items = listOf(
                ShowcaseItem(
                    id = "morph-icon-state",
                    title = "State-driven icon morph",
                    description = "MorphIcon owns progress and retargets smoothly when state changes.",
                ),
                ShowcaseItem(
                    id = "morph-icon-tint",
                    title = "Tint transition",
                    description = "Endpoint colors interpolate across the morph; tint replaces all paint.",
                ),
                ShowcaseItem(
                    id = "morph-icon-fill",
                    title = "Stroke → fill handover",
                    description = "How an outline hands itself over to the fill: MORPH, CROSSFADE, DRAW, AUTO.",
                ),
                ShowcaseItem(
                    id = "morph-icon-chain",
                    title = "Chained icon retargeting",
                    description = "MorphingIcon always morphs from the last settled icon, never from a mid-flight frame.",
                ),
                ShowcaseItem(
                    id = "morph-icon-motion",
                    title = "Motion preference",
                    description = "Full, reduced, crossfade-only, and instant motion preferences side by side.",
                ),
                ShowcaseItem(
                    id = "morph-icon-playground",
                    title = "Playground",
                    description = "Every MorphIcon parameter live: icons, tint, timing, configuration, stagger.",
                ),
            ),
        ),
        TopicDescriptor(
            id = TopicId.MORPH_VECTOR,
            title = "MorphVector",
            api = "MorphVector(from, to, progress, …)",
            description = "Caller-owned progress: drive it from a slider, gesture, pager, or scroll position.",
            previewIcon = IconCatalog.icon("ArrowDown2Outline"),
            playgroundId = "morph-vector-playground",
            items = listOf(
                ShowcaseItem(
                    id = "morph-vector-scrub",
                    title = "Manually scrub a morph",
                    description = "A slider stands in for the gesture that owns progress.",
                ),
                ShowcaseItem(
                    id = "morph-vector-missing",
                    title = "Unmatched paths",
                    description = "Fade, scale, collapse, and keep behaviors for paths that only exist on one side.",
                ),
                ShowcaseItem(
                    id = "morph-vector-viewport",
                    title = "Viewport normalization",
                    description = "A wide banner target meets a square source: one mode distorts each axis, the other letterboxes.",
                ),
                ShowcaseItem(
                    id = "morph-vector-playground",
                    title = "Playground",
                    description = "Scrub every MorphVector parameter live: icons, tint, progress, configuration.",
                ),
            ),
        ),
        TopicDescriptor(
            id = TopicId.VECTOR_ANIMATOR,
            title = "VectorAnimator",
            api = "VectorAnimator(definition, progress, …)",
            description = "Fully manual driver over a reusable definition: seek deterministic timelines.",
            previewIcon = IconCatalog.icon("AddCircleOutline"),
            playgroundId = "vector-animator-playground",
            items = listOf(
                ShowcaseItem(
                    id = "vector-animator-keyframes",
                    title = "Keyframe timeline",
                    description = "Vector keyframes with a caller-owned, scrubbable playhead.",
                ),
                ShowcaseItem(
                    id = "vector-animator-stagger",
                    title = "Staggered paths",
                    description = "stagger(delay, order) fans each path's clock around the chosen order.",
                ),
                ShowcaseItem(
                    id = "vector-animator-fill",
                    title = "Fill reveal modes",
                    description = "Every fill mode plus a travel direction over one chosen pair.",
                ),
                ShowcaseItem(
                    id = "vector-animator-gradient",
                    title = "Gradient endpoints",
                    description = "gradient() and strokeGradient() move fill and stroke paints between brushes.",
                ),
                ShowcaseItem(
                    id = "vector-animator-playground",
                    title = "Playground",
                    description = "Compose keyframes, stroke reveals, color tracks, and stagger from every control.",
                ),
            ),
        ),
        TopicDescriptor(
            id = TopicId.DRAW_ICON,
            title = "DrawIcon",
            api = "DrawIcon(vector, animation, …)",
            description = "One-shot pen reveal: strokes draw themselves along measured arc length.",
            previewIcon = IconCatalog.icon("AlgorithmOutline"),
            playgroundId = "draw-icon-playground",
            items = listOf(
                ShowcaseItem(
                    id = "draw-icon-pen",
                    title = "Draw an icon with a pen",
                    description = "Named paths reveal from their start anchors with replay control.",
                ),
                ShowcaseItem(
                    id = "draw-icon-follow-fill",
                    title = "Draw then fill",
                    description = "reveal(followWithFill) draws the stroke and sweeps the fill in behind it.",
                ),
                ShowcaseItem(
                    id = "draw-icon-clip",
                    title = "Clip reveal",
                    description = "clipReveal opens a named group's inherited mask along a directional edge.",
                ),
                ShowcaseItem(
                    id = "draw-icon-playground",
                    title = "Playground",
                    description = "Every draw parameter live: icon, anchor, direction, mode, timing, tint.",
                ),
            ),
        ),
    )

    fun topic(id: TopicId): TopicDescriptor = topics.first { it.id == id }

    fun item(topicId: TopicId, itemId: String): ShowcaseItem =
        topic(topicId).items.first { it.id == itemId }
}
