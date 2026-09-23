package com.imorpher.vectormorph.core.model

import com.imorpher.vectormorph.core.animation.VectorAnimation

/**
 * Everything a morph needs to know, beyond the two vectors themselves.
 *
 * A data class so `remember`-style caching can key on it safely: two equal configurations
 * always produce equal plans. Diagnostics callbacks are deliberately kept *out* of this class
 * so they never poison cache keys.
 */
data class MorphConfiguration(
    val pathMatching: PathMatchingStrategy = PathMatchingStrategy.AUTO,
    val startPoint: StartPointStrategy = StartPointStrategy.AUTO,
    val pathDirection: PathDirectionStrategy = PathDirectionStrategy.AUTO,
    val drawOrder: DrawOrderStrategy = DrawOrderStrategy.BY_INDEX,
    /** Explicit order (path names) used when [drawOrder] == CUSTOM. */
    val customDrawOrder: List<String> = emptyList(),
    val missingPath: MissingPathBehavior = MissingPathBehavior.FADE,
    val fallback: FallbackStrategy = FallbackStrategy.AUTO,
    val timing: TimingMode = TimingMode.BY_PATH_LENGTH,
    val colorSpace: ColorInterpolationSpace = ColorInterpolationSpace.SRGB,
    val strokeFill: StrokeFillStrategy = StrokeFillStrategy.AUTO,
    /** Optional timeline. When null, a plain geometry/color morph drives everything. */
    val animation: VectorAnimation? = null,
    /** Stagger applied to vector-level draw/reveal intervals, in normalized timeline units. */
    val staggerDelay: Float = 0f,
    val staggerOrder: DrawOrderStrategy = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
    /** Explicit source→target path name mappings (strategy CUSTOM, or overrides under AUTO). */
    val customMappings: List<PathMapping> = emptyList(),
    /** Per-path normalized arc-fraction (0..1) start overrides, strategy CUSTOM. */
    val customStartOffsets: Map<String, Float> = emptyMap(),
    /** Fill reveal defaults for paths animated through fillProgress without an explicit mode. */
    val defaultFillMode: FillMode = FillMode.DIRECTIONAL,
    val defaultFillDirection: DrawDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
    /** Debug overlay options; null disables debug rendering. */
    val debug: MorphDebugOptions? = null,
    /** Render paths strictly in declaration order even when a draw order strategy reorders reveals. */
    val keepRenderOrder: Boolean = true,
) {
    companion object {
        /** Plain, well-behaved default: geometry morph with auto strategies. */
        val Default = MorphConfiguration()
    }
}

/** Explicit declaration: "source path named [source] morphs into target path named [target]". */
data class PathMapping(
    val source: String,
    val target: String,
)

/** Optional debug visualization toggles rendered on top of the animated vector. */
data class MorphDebugOptions(
    val showPathPoints: Boolean = false,
    val showPathNumbers: Boolean = false,
    val showBoundingBoxes: Boolean = false,
    val showCentroids: Boolean = false,
    val showDirection: Boolean = false,
    val showStartPoints: Boolean = false,
    val showPathMatching: Boolean = false,
    /** 0..1 position along each contour where "current point" markers are drawn. */
    val markerArcFraction: Float = 0f,
)
