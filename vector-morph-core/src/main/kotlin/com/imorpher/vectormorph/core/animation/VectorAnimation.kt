package com.imorpher.vectormorph.core.animation

import androidx.compose.ui.graphics.Brush
import com.imorpher.vectormorph.core.gradients.BrushSpec

/**
 * Scalar properties that can be animated per target (vector, group or path).
 * Values live in [EvaluatedFrame] float arrays indexed by [ordinal].
 */
enum class PropKey {
    /** Multiplies the path's overall alpha. Default 1. */
    ALPHA,
    /** Stroke draw-on progress 0..1. Default 1 (fully drawn). */
    DRAW_PROGRESS,
    /** Fill reveal progress 0..1. Default 1 (fully filled). */
    FILL_PROGRESS,
    /** Progressive reveal of inherited group clip paths. */
    CLIP_PROGRESS,
    /** Geometry morph fraction for this path pair. Default: global animation progress. */
    MORPH_PROGRESS,
    /** Scale factor (uniform X/Y unless SCALE_Y is separately animated). Default 1. */
    SCALE_X,
    /** Separate Y scale; -1 means "follow SCALE_X". */
    SCALE_Y,
    /** Degrees, clockwise on screen. Default 0, interpolated shortest-path. */
    ROTATION_DEG,
    TRANSLATION_X,
    TRANSLATION_Y,
    /** Transform origin, fractional within the target's bounds. Default (0.5, 0.5). */
    PIVOT_X,
    PIVOT_Y,
    /** Stroke width override in viewport units. NaN = use the path's own width. */
    STROKE_WIDTH,
    /** Multiplier over the path's own fill alpha. Default 1. */
    FILL_ALPHA,
    /** Multiplier over the path's own stroke alpha. Default 1. */
    STROKE_ALPHA;

    companion object {
        val COUNT = entries.size
    }
}

/** Which part of the vector a track applies to. */
sealed class AnimationTarget {
    /** The whole rendered vector (applies one transform/alpha over everything). */
    data object Vector : AnimationTarget()

    /** Every path individually (e.g. staggered reveals with per-path draw configs). */
    data object AllPaths : AnimationTarget()

    /** Path by declared name (with optional occurrence when names repeat). */
    data class Path(val name: String, val occurrence: Int = 0) : AnimationTarget()

    /** Path by source index (declaration order). */
    data class PathIndex(val index: Int) : AnimationTarget()

    /** All paths under the named group (any depth). */
    data class Group(val name: String) : AnimationTarget()
}

/**
 * Whether an animated brush affects a path's fill or its stroke paint. A FILL track is
 * deliberately ignored on stroke-only paths (it would invent a fill over the implicit
 * close); use a STROKE track to recolor them.
 */
enum class BrushChannel { FILL, STROKE }

/**
 * A resolved animation: an immutable list of tracks. Built with [VectorAnimationBuilder]
 * (the `VectorAnimation { ... }` DSL). Cheap to build, evaluated per frame by
 * [TimelineEvaluator].
 */
class VectorAnimation internal constructor(
    internal val tracks: List<Track>,
    internal val brushTracks: List<BrushTrack>,
    /** True when the DSL never referenced geometry properties (pure appearance timeline). */
    internal val appearanceOnly: Boolean,
    /** Normalized delay between ordered path animations. */
    val staggerDelay: Float = 0f,
    /** Order used to assign stagger offsets. */
    val staggerOrder: com.imorpher.vectormorph.core.model.DrawOrderStrategy =
        com.imorpher.vectormorph.core.model.DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
    /** Optional per-pair custom rendering callback. */
    val customizer: CustomVectorAnimation? = null,
) {
    /** One interval of a scalar track: [from]→[to] eased across the normalized timeline. */
    internal class Segment(
        /** Normalized timeline position where this segment takes over. */
        val start: Float,
        /** Normalized timeline position where this segment hands over (inclusive). */
        val end: Float,
        /** Value at [start]. */
        val from: Float,
        /** Value at [end]. */
        val to: Float,
        /** Easing applied to the local progress between [start] and [end]. */
        val easing: androidx.compose.animation.core.Easing,
        /** Reveal/fill configuration (mode, anchor, direction) when the property is a reveal. */
        val draw: DrawConfig? = null,
    )

    /** One interval of a brush track: [from]→[to] paint interpolation. */
    internal class BrushSegment(
        /** Normalized timeline position where this segment takes over. */
        val start: Float,
        /** Normalized timeline position where this segment hands over. */
        val end: Float,
        /** Paint at [start]. */
        val from: BrushSpec,
        /** Paint at [end]. */
        val to: BrushSpec,
        /** Easing applied to the local progress between [start] and [end]. */
        val easing: androidx.compose.animation.core.Easing,
    )

    /** All [Segment]s of one (target, property) combination, evaluated independently. */
    internal class Track(
        /** Which part of the vector this track drives. */
        val target: AnimationTarget,
        /** Which scalar property this track drives. */
        val prop: PropKey,
        /** Intervals in timeline order; may tile sequentially or overlap. */
        val segments: List<Segment>,
    )

    /** All [BrushSegment]s of one (target, [channel]) combination. */
    internal class BrushTrack(
        /** Which part of the vector this track drives. */
        val target: AnimationTarget,
        /** Intervals in timeline order. */
        val segments: List<BrushSegment>,
        /** Whether the interpolated paint drives fills or strokes. */
        val channel: BrushChannel = BrushChannel.FILL,
    )

    /** Extra parameters attached to draw/reveal tracks. */
    class DrawConfig(
        val mode: com.imorpher.vectormorph.core.model.DrawMode = com.imorpher.vectormorph.core.model.DrawMode.FORWARD,
        val start: com.imorpher.vectormorph.core.model.PathStart = com.imorpher.vectormorph.core.model.PathStart.AUTO,
        val direction: com.imorpher.vectormorph.core.model.DrawDirection = com.imorpher.vectormorph.core.model.DrawDirection.AUTO,
        /** Fill reveal to pair with the reveal (mode + direction); null = no fill coupling. */
        val fillMode: com.imorpher.vectormorph.core.model.FillMode? = null,
        val fillDirection: com.imorpher.vectormorph.core.model.DrawDirection? = null,
    )

    /** Returns the draw configuration associated with the last matching reveal track. */
    fun drawConfigFor(
        fromName: String?,
        toName: String?,
        fromIndex: Int,
        toIndex: Int,
        property: PropKey = PropKey.DRAW_PROGRESS,
        fromGroups: List<String> = emptyList(),
        toGroups: List<String> = emptyList(),
    ): DrawConfig? = tracks.asReversed().firstNotNullOfOrNull { track ->
        val draw = track.segments.asReversed().firstNotNullOfOrNull { it.draw }
            ?: return@firstNotNullOfOrNull null
        if (track.prop != property) return@firstNotNullOfOrNull null
        val matches = when (val target = track.target) {
            AnimationTarget.Vector -> false
            AnimationTarget.AllPaths -> true
            is AnimationTarget.Path -> matchesPathName(fromName, target.name, target.occurrence) ||
                matchesPathName(toName, target.name, target.occurrence)
            is AnimationTarget.PathIndex -> target.index == fromIndex || target.index == toIndex
            is AnimationTarget.Group -> target.name in fromGroups || target.name in toGroups
        }
        if (matches) draw else null
    }

    /**
     * True when [actual] (a compiled, disambiguated path name) matches [requested] at
     * [occurrence] — repeated names compile to "name[1]", "name[2]"… per declaration order.
     */
    private fun matchesPathName(actual: String?, requested: String, occurrence: Int): Boolean {
        if (actual == null) return false
        val expected = if (occurrence == 0) requested else "$requested[$occurrence]"
        return actual == expected
    }
}

/** Scratch evaluation buffers, reused across frames (no per-frame allocation of the arrays). */
class PathFrameValues {
    /** Reused scalar overrides, indexed by [PropKey]. NaN means use the vector's own value. */
    val props = FloatArray(PropKey.COUNT) { Float.NaN }
    /** Active reveal/fill configuration attached to the property's currently active segment. */
    val drawConfigs: Array<VectorAnimation.DrawConfig?> = arrayOfNulls(PropKey.COUNT)
    /** Shared named-group bounds in viewport coordinates, set for group transform tracks. */
    val groupBounds = FloatArray(4) { Float.NaN }
    /** Transient custom callback result, replaced on each renderer pass. */
    var customOverrides: CustomVectorPathOverrides? = null
    /** Preallocated independent state for each enclosing named group. */
    val groupProps: MutableMap<String, PathFrameValues> = LinkedHashMap()
    /** Brush override mode. */
    var brushMode: Int = BRUSH_NONE
    /** Primary brush used by an animation color/gradient track. */
    var primaryBrush: Brush? = null
    /** Secondary brush used while crossfading incompatible gradients. */
    var secondaryBrush: Brush? = null
    /** From-to brush interpolation fraction when [brushMode] is a crossfade. */
    var brushProgress: Float = 0.5f
    /** Stroke brush override mode ([BRUSH_NONE] when no stroke track applies). */
    var strokeBrushMode: Int = BRUSH_NONE

    /** Primary stroke brush when [strokeBrushMode] is active. */
    var primaryStrokeBrush: Brush? = null

    /** Secondary stroke brush of a stroke crossfade. */
    var secondaryStrokeBrush: Brush? = null

    /** From→to fraction of a stroke crossfade. */
    var strokeBrushProgress: Float = 0.5f

    /**
     * Copies every per-frame visual (scalar overrides, draw configs, group bounds, brush
     * state) from [other]. Used by the renderer's merged-frame optimization; group tracks
     * are merged separately since they compose rather than replace.
     */
    fun copyVisualsFrom(other: PathFrameValues) {
        other.props.copyInto(props)
        other.drawConfigs.copyInto(drawConfigs)
        other.groupBounds.copyInto(groupBounds)
        customOverrides = other.customOverrides
        brushMode = other.brushMode; primaryBrush = other.primaryBrush
        secondaryBrush = other.secondaryBrush; brushProgress = other.brushProgress
        strokeBrushMode = other.strokeBrushMode; primaryStrokeBrush = other.primaryStrokeBrush
        secondaryStrokeBrush = other.secondaryStrokeBrush; strokeBrushProgress = other.strokeBrushProgress
    }

    companion object {
        const val BRUSH_NONE = 0
        const val BRUSH_INTERPOLATED = 1
        const val BRUSH_CROSSFADE = 2
    }
}
