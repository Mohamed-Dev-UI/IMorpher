package com.imorpher.vectormorph.core.model

/**
 * Public configuration enums for the vector morph engine.
 * Kept in one file: they are small, leaf-level types referenced across the API surface.
 */

/** How a path reveals itself when "drawing" (progressive stroke rendering). */
enum class DrawMode {
    /** Start → end along the (normalized) path direction. */
    FORWARD,
    /** End → start. */
    REVERSE,
    /** Grows from the arc midpoint outwards to both ends simultaneously. */
    CENTER_OUT,
    /** Grows from both ends inwards towards the arc midpoint. */
    OUTSIDE_IN,
    /** Revealed through an expanding circular clip centred on the path centroid. */
    RADIAL,
    /** Supplied by a custom [CustomVectorAnimation]. */
    CUSTOM,
}

/** Overall travel direction of a reveal, used by both stroke draws and fill sweeps. */
enum class DrawDirection {
    /** Chooses the top-left geometric anchor when no explicit [PathStart] is supplied. */
    AUTO,
    TOP_LEFT_TO_BOTTOM_RIGHT,
    TOP_RIGHT_TO_BOTTOM_LEFT,
    BOTTOM_LEFT_TO_TOP_RIGHT,
    BOTTOM_RIGHT_TO_TOP_LEFT,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TOP_TO_BOTTOM,
    BOTTOM_TO_TOP,
    CENTER_OUT,
    OUTSIDE_IN,
    CLOCKWISE,
    COUNTER_CLOCKWISE,
    CUSTOM,
}

/** Where a path's drawing/reveal begins. */
enum class PathStart {
    /** The contour's own first point (after start-point normalization). */
    START,
    END,
    CENTER,
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    /** A custom normalized arc-length offset, see [MorphConfiguration.customStartOffsets]. */
    CUSTOM,
}

/** How closed contours align their starting points before morphing. */
enum class StartPointStrategy {
    /**
     * Rotates the target contour's start point so the correspondence between source and target
     * minimizes total point-to-point distance. Prevents "rotational deformation" when two
     * otherwise identical shapes start their paths at different vertices.
     */
    AUTO,
    /** Target aligns to the source's start point. */
    MATCH_SOURCE,
    /** Source aligns to the target's start point. */
    MATCH_TARGET,
    /** Both aligned to the same geometric feature (e.g. dominant extreme point) before matching. */
    BEST_GEOMETRIC_MATCH,
    /** Uses [MorphConfiguration.customStartOffsets] (normalized arc fraction per path). */
    CUSTOM,
}

/** How contour winding (clockwise vs counter-clockwise) is normalized before morphing. */
enum class PathDirectionStrategy {
    /** Reverse the target contour when its signed winding opposes the source. */
    AUTO,
    /** Never reverse; morphs may twist if windings oppose. */
    PRESERVE,
    /** Force both contours clockwise. */
    CLOCKWISE,
    /** Force both contours counter-clockwise. */
    COUNTER_CLOCKWISE,
}

/** How source paths are paired with target paths. */
enum class PathMatchingStrategy {
    /** Pair by declaration order (requires equal path counts). */
    BY_INDEX,
    /** Pair by path name. */
    BY_NAME,
    /** Pair by an explicit id assigned at compile time (path index in the vector). */
    BY_ID,
    /** Pair by geometric similarity (centroid, area, length, contour structure). */
    BY_GEOMETRY,
    /** Pair by filled area similarity. */
    BY_AREA,
    /** Pair by centroid proximity. */
    BY_CENTROID,
    /** Index when counts match & geometry corroborates, otherwise geometry matching. */
    AUTO,
    /** Uses [MorphConfiguration.customMappings] exclusively. */
    CUSTOM,
}

/** What happens to paths that only exist on one side of the morph. */
enum class MissingPathBehavior {
    /** Fade alpha in/out. */
    FADE,
    /** Draw the path in with the stroke reveal / draw animation. */
    DRAW,
    /** Scale from/to 0 at its centroid. */
    SCALE,
    /** Scale to 0 with an ease-out "pop" (slight overshoot). */
    COLLAPSE,
    /** Morph from the geometrically nearest path that exists on the other side. */
    MORPH_FROM_NEAREST,
    /** Keep rendering the existing side while the other fades in on top. */
    KEEP,
    /** Supplied by a [CustomVectorAnimation]. */
    CUSTOM,
}

/** How draw/reveal time maps onto geometry. */
enum class TimingMode {
    /** Distance along the path — a short line finishes early, a long curve late (constant speed). */
    BY_PATH_LENGTH,
    /** Linear in command/segment index. */
    BY_COMMAND,
    /** Whole paths complete one after another. */
    BY_PATH,
    /** Everything at the same normalized rate. */
    UNIFORM,
}

/** Fill reveal mode. */
enum class FillMode {
    /** Plain alpha fade. */
    FADE,
    /** Expanding circle from the centroid. */
    RADIAL,
    /** Hard-edged linear sweep across the bounds. */
    LINEAR,
    /** Directional sweep between two corners/edges of the bounds. */
    DIRECTIONAL,
    /** Angular sweep around the centroid. */
    SWEEP,
    /** The fill boundary follows the actual path traversal (thick-stroke dash trick). */
    PATH_TRAVERSAL,
}

/** Color interpolation space for color and gradient animation. */
enum class ColorInterpolationSpace {
    SRGB,
    LINEAR_SRGB,
    OKLAB,
    OKLCH,
}

/** Strategy for stroke↔fill transitions (e.g. outlined icon → filled icon). */
enum class StrokeFillStrategy {
    /** Shrink stroke to nothing while the fill grows in. */
    MORPH,
    /** Straight alpha crossfade. */
    CROSSFADE,
    /** Stroke draws itself first, then the fill sweeps in. */
    DRAW,
    /** Analyze the pair and pick the most visually stable strategy. */
    AUTO,
}

/** What to do when a clean geometric morph is impossible. */
enum class FallbackStrategy {
    /** Pick the best available: geometry for compatible pairs, crossfade otherwise. */
    AUTO,
    /** Always attempt geometry morph even when the report warns about artifacts. */
    BEST_EFFORT,
    /** Pure alpha crossfade between the two vectors. */
    CROSSFADE,
    /** Crossfade combined with a subtle scale pulse (0.94 → 1). */
    SCALE_CROSSFADE,
    /** Reveal the target with the draw animation over the fading source. */
    DRAW_REVEAL,
    /** Throw at planning time instead of silently degrading. */
    THROW,
}

/** Ordering of path drawing/reveals. */
enum class DrawOrderStrategy {
    BY_INDEX,
    BY_NAME,
    BY_POSITION,
    TOP_LEFT_TO_BOTTOM_RIGHT,
    TOP_RIGHT_TO_BOTTOM_LEFT,
    BOTTOM_LEFT_TO_TOP_RIGHT,
    BOTTOM_RIGHT_TO_TOP_LEFT,
    CLOCKWISE,
    COUNTER_CLOCKWISE,
    /** Geometry-based top-left to bottom-right order for stagger timing. */
    AUTO,
    CUSTOM,
}

/** Accessibility motion preference, resolved from system settings or configuration. */
enum class MotionPreference {
    /** Full animation. */
    FULL,
    /** Shortened, gentler animation (~30% duration). */
    REDUCED,
    /** Replace geometry morphs with a simple crossfade. */
    CROSSFADE_ONLY,
    /** No animation: jump to the end state. */
    INSTANT,
}

/** Per-path transform origin. */
data class TransformOrigin(val x: Float, val y: Float) {
    companion object {
        val Center = TransformOrigin(0.5f, 0.5f)
        val TopLeft = TransformOrigin(0f, 0f)
        val TopRight = TransformOrigin(1f, 0f)
        val BottomLeft = TransformOrigin(0f, 1f)
        val BottomRight = TransformOrigin(1f, 1f)
        /** Fractional coordinates relative to the path/group/vector bounds. */
        fun Custom(x: Float, y: Float) = TransformOrigin(x, y)
    }
}
