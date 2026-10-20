package com.imorpher.vectormorph.core.animation

import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.PreparedPath

/** Per-path input to an application supplied animation callback. */
data class CustomVectorPathState(
    /** Index into [com.imorpher.vectormorph.core.plan.MorphPlan.pairs]. */
    val pairIndex: Int,
    /** Source-side path, or null when the path only exists on the target side. */
    val from: PreparedPath?,
    /** Target-side path, or null when the path only exists on the source side. */
    val to: PreparedPath?,
    /** Global animation progress (0..1) — the driving animation's own clock. */
    val progress: Float,
    /** Geometry morph fraction for this pair (0 = fully source, 1 = fully target). */
    val morphProgress: Float,
    /** Stroke reveal progress for this pair (1 = fully drawn). */
    val drawProgress: Float,
    /** Fill reveal progress for this pair (1 = fully filled). */
    val fillProgress: Float,
)

/** Optional path-level replacements returned by [CustomVectorAnimation]. */
data class CustomVectorPathOverrides(
    /**
     * Replaces geometry for both the fill and the stroke pass. Coordinates use the vector
     * viewport; contours fill together (one path) so fill-rule holes keep working.
     */
    val contours: List<ContourData>? = null,
    /** Geometry actually visible in the stroke pass; required for DrawMode.CUSTOM. */
    val strokeRevealContours: List<ContourData>? = null,
    /** Geometry actually visible in the fill pass; required for DrawDirection.CUSTOM. */
    val fillContours: List<ContourData>? = null,
    /** Multiplier over the path's overall alpha. */
    val alpha: Float? = null,

    /** Multiplier over the path's fill alpha. */
    val fillAlpha: Float? = null,

    /** Multiplier over the path's stroke alpha. */
    val strokeAlpha: Float? = null,
    /** Paint overrides; null retains the interpolated source/target paint. */
    val fill: BrushSpec? = null,
    val stroke: BrushSpec? = null,
    /** Stroke width override in viewport units. */
    val strokeWidth: Float? = null,
    /** Uniform X scale override (fractional, 1 = unchanged). */
    val scaleX: Float? = null,
    /** Separate Y scale override; null follows [scaleX]. */
    val scaleY: Float? = null,
    /** Clockwise rotation override in degrees. */
    val rotationDegrees: Float? = null,
    /** X translation override in viewport units. */
    val translationX: Float? = null,
    /** Y translation override in viewport units. */
    val translationY: Float? = null,
    /** Fractional pivot within the effective bounds. */
    val pivotX: Float? = null,
    val pivotY: Float? = null,
)

/**
 * Pure callback evaluated at render time for one path pair. Keep callback work small and
 * deterministic; expensive geometry preparation belongs outside the callback and can be captured
 * by the implementation. Return null to leave the normal renderer in control.
 */
fun interface CustomVectorAnimation {
    /**
     * Called once per frame for each path pair.
     *
     * @param path The pair's identity, endpoints, and current progress values.
     * @return Overrides to apply this frame, or null to leave the normal renderer in control.
     */
    fun transform(path: CustomVectorPathState): CustomVectorPathOverrides?
}
