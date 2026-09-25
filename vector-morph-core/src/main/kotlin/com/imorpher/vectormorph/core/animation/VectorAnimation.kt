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

/** Whether an animated brush affects a path's fill or its stroke paint. */
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
    internal class Segment(
        val start: Float,
        val end: Float,
        val from: Float,
        val to: Float,
        val easing: androidx.compose.animation.core.Easing,
        val draw: DrawConfig? = null,
    )

    internal class BrushSegment(
        val start: Float,
        val end: Float,
        val from: BrushSpec,
        val to: BrushSpec,
        val easing: androidx.compose.animation.core.Easing,
    )

    internal class Track(
        val target: AnimationTarget,
        val prop: PropKey,
        val segments: List<Segment>,
    )

    internal class BrushTrack(
        val target: AnimationTarget,
        val segments: List<BrushSegment>,
        val channel: BrushChannel = BrushChannel.FILL,
    )

    /** Extra parameters attached to draw/reveal tracks. */
    class DrawConfig(
        val mode: com.imorpher.vectormorph.core.model.DrawMode = com.imorpher.vectormorph.core.model.DrawMode.FORWARD,
        val start: com.imorpher.vectormorph.core.model.PathStart = com.imorpher.vectormorph.core.model.PathStart.START,
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
    var strokeBrushMode: Int = BRUSH_NONE
    var primaryStrokeBrush: Brush? = null
    var secondaryStrokeBrush: Brush? = null
    var strokeBrushProgress: Float = 0.5f

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
