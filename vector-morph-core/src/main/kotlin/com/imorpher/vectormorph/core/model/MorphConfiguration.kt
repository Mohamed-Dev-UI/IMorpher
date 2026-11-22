package com.imorpher.vectormorph.core.model

import com.imorpher.vectormorph.core.animation.VectorAnimation

/**
 * Everything a morph needs to know, beyond the two vectors themselves.
 *
 * A data class so `remember`-style caching can key on it safely: two equal configurations
 * always produce equal plans. Diagnostics callbacks are deliberately kept *out* of this class
 * so they never poison cache keys.
 *
 * Precedence rules documented here and enforced since 0.3.0 (see docs/CONFIGURATION.md for
 * the reference table):
 * - **Ordering split.** [drawOrder] controls render z-order only when [keepRenderOrder] is
 *   false; stagger *timing* and `TimingMode.BY_PATH` ranks use [staggerOrder].
 *   Strategies reordering reveals never touch paint order while `keepRenderOrder = true`.
 * - **Stagger override rule.** the config's order supplies the timeline order only when
 *   [staggerDelay] > 0 or [staggerOrder] differs from the default. A positive config delay
 *   overrides the timeline delay; otherwise its delay is inherited. Zero config delay does
 *   not cancel a timeline delay.
 * - **[missingPath] under crossfade fallbacks.** whenever `fallback = CROSSFADE` /
 *   `SCALE_CROSSFADE` crossfades every pair, [missingPath] is ignored — the plan records a
 *   WARNING issue (see `MorphValidator.validateConfiguration` / `MorphPlan.report`).
 * - **Dead-wire warnings.** fields that only apply under a specific strategy or knob lane
 *   ([customDrawAnchors] needs a `PathStart.CUSTOM` draw track; [customDrawOrder] needs
 *   `drawOrder = CUSTOM` with `keepRenderOrder = false`, or an active path timeline with a
 *   resolved `staggerOrder = CUSTOM`; [customMappings] only apply under `AUTO`/`CUSTOM` matching;
 *   [simplificationTolerance] needs [segmentBudget]) are audited statically by
 *   `MorphValidator.validateConfiguration`.
 *
 * Planning/rendering split: only the fields `MorphPlanner` consumes participate in
 * `planningKey()` (`vector-morph-core/.../model/PlanningKey.kt`); render-only fields
 * (draw order, stagger, timing, color space, fill defaults, debug options, etc.) never
 * invalidate a cached plan.
 */
data class MorphConfiguration(
    val pathMatching: PathMatchingStrategy = PathMatchingStrategy.AUTO,
    val startPoint: StartPointStrategy = StartPointStrategy.AUTO,
    val pathDirection: PathDirectionStrategy = PathDirectionStrategy.AUTO,
    /**
     * Render z-order strategy, consulted only when [keepRenderOrder] is false. Timeline
     * stagger offsets and `TimingMode.BY_PATH` ranks use [staggerOrder] instead.
     */
    val drawOrder: DrawOrderStrategy = DrawOrderStrategy.BY_INDEX,
    /**
     * Explicit path-name order consumed by CUSTOM render ordering or a CUSTOM timeline
     * order when its path tracks use stagger delay or `TimingMode.BY_PATH` ranks.
     */
    val customDrawOrder: List<String> = emptyList(),
    val missingPath: MissingPathBehavior = MissingPathBehavior.FADE,
    val fallback: FallbackStrategy = FallbackStrategy.AUTO,
    val timing: TimingMode = TimingMode.BY_PATH_LENGTH,
    val colorSpace: ColorInterpolationSpace = ColorInterpolationSpace.SRGB,
    val strokeFill: StrokeFillStrategy = StrokeFillStrategy.AUTO,
    /** Optional timeline. When null, a plain geometry/color morph drives everything. */
    val animation: VectorAnimation? = null,
    /** Positive normalized per-path timeline delay; overrides the embedded animation's delay. */
    val staggerDelay: Float = 0f,
    val staggerOrder: DrawOrderStrategy = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
    /** Explicit source→target path name mappings (strategy CUSTOM, or overrides under AUTO). */
    val customMappings: List<PathMapping> = emptyList(),
    /**
     * Per-path normalized arc-fraction (0..1) start overrides, strategy CUSTOM — morph
     * alignment only ([StartPointStrategy.CUSTOM] correspondences).
     *
     * Retained as a legacy fallback for `PathStart.CUSTOM` draw anchoring when
     * [customDrawAnchors] has no entry; prefer [customDrawAnchors] so alignment data never
     * moves the pen.
     */
    @Deprecated(
        message = "Use customDrawAnchors for pen anchors; alignment offsets belong in " +
            "StartPointStrategy.CUSTOM correspondences only.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith("customDrawAnchors"),
    )
    val customStartOffsets: Map<String, Float> = emptyMap(),
    /**
     * Normalized arc fraction (0..1) per path name for `PathStart.CUSTOM` draw anchoring:
     * where the stroke pen begins on that path. When this map has no entry, the renderer
     * falls back to [customStartOffsets] (legacy behavior) for that path.
     */
    val customDrawAnchors: Map<String, Float> = emptyMap(),
    /** Fill reveal defaults for paths animated through fillProgress without an explicit mode. */
    val defaultFillMode: FillMode = FillMode.DIRECTIONAL,
    val defaultFillDirection: DrawDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
    /** Debug overlay options; null disables debug rendering. */
    val debug: MorphDebugOptions? = null,
    /** Render paths strictly in declaration order even when a draw order strategy reorders reveals. */
    val keepRenderOrder: Boolean = true,
    /**
     * How `DrawMode.FORWARD` stroke reveals travel on open contours: [DirectionTraversal.HONOR_DIRECTION]
     * (the default since 0.2.0) traverses toward the draw direction's axis; [DirectionTraversal.LEGACY]
     * keeps the historical contour-order traversal. Explicit `DrawMode.REVERSE`/`CENTER_OUT`/
     * `OUTSIDE_IN` modes always win over either policy, and closed contours keep their
     * winding-reversal behavior unchanged.
     */
    val directionTraversal: DirectionTraversal = DirectionTraversal.HONOR_DIRECTION,
    /**
     * Target ceiling for cubic segments per contour pair. Null keeps the natural
     * `max(source, target)` count; values below 2 are raised to the planner minimum of 2.
     * Reduction can stop above this target when another merge would exceed
     * [simplificationTolerance], preserving silhouette quality at the expense of the budget.
     */
    val segmentBudget: Int? = null,
    /**
     * Maximum sampled deviation (in viewport units) tolerated while [segmentBudget] reduces a
     * contour. It has no effect when [segmentBudget] is null. Invalid values (negative or
     * non-finite) are treated as zero during planning and reported by
     * `MorphValidator.validateConfiguration`. When no merge stays within tolerance, each side
     * may remain above the target budget; the sides are re-equalized at the largest surviving
     * count so interpolation still sees equal segment counts.
     */
    val simplificationTolerance: Float = 0.25f,
    /**
     * How the target viewport is mapped into the source viewport. [ViewportNormalization.STRETCH]
     * (default) keeps the historical per-axis scaling; [ViewportNormalization.FIT] preserves the
     * target's aspect ratio and centres it instead.
     */
    val viewportNormalization: ViewportNormalization = ViewportNormalization.STRETCH,
    /**
     * Modern home for per-path normalized arc-fraction (0..1) start overrides under
     * [StartPointStrategy.CUSTOM] — the morph-alignment data whose legacy home was the
     * deprecated [customStartOffsets]. For a path name, this map wins and
     * [customStartOffsets] keeps serving as its legacy fallback, so callers migrating off
     * the deprecated map keep their 0.1.x pixels until every entry moves here.
     *
     * [customDrawAnchors] never moves geometry: the anchor map and this alignment map
     * describe different concepts and must not cross. Populate both maps for the same
     * path with different values and the validator reports `custom-start-alignment-conflict`
     * so alignment never silently has two owners.
     */
    val customStartAlignments: Map<String, Float> = emptyMap(),
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

/** Optional debug visualization toggles rendered on top of the animated vector.
 *
 * The resolution-warning banner draws whenever [MorphConfiguration.debug] is on and
 * warnings are recorded — with ANY option enabled, not just [showStartPoints]/
 * [showDirection] (which gate only the resolved anchor dot and the travel arrow).
 */
data class MorphDebugOptions(
    val showPathPoints: Boolean = false,
    val showPathNumbers: Boolean = false,
    val showBoundingBoxes: Boolean = false,
    val showCentroids: Boolean = false,
    val showDirection: Boolean = false,
    val showStartPoints: Boolean = false,
    val showPathMatching: Boolean = false,
    /**
     * 0..1 arc fraction along each contour where the debug overlay's "current point"
     * marker rings are drawn; `0` (the default) and `1` draw nothing.
     */
    val markerArcFraction: Float = 0f,
)
