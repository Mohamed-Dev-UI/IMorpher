package com.imorpher.vectormorph.core.animation

import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.PreparedPath

/** Per-path input to an application supplied animation callback. */
data class CustomVectorPathState(
    val pairIndex: Int,
    val from: PreparedPath?,
    val to: PreparedPath?,
    val progress: Float,
    val morphProgress: Float,
    val drawProgress: Float,
    val fillProgress: Float,
)

/** Optional path-level replacements returned by [CustomVectorAnimation]. */
data class CustomVectorPathOverrides(
    /** Replaces geometry for fill and stroke. Coordinates use the vector viewport. */
    val contours: List<ContourData>? = null,
    /** Geometry actually visible in the stroke pass; required for DrawMode.CUSTOM. */
    val strokeRevealContours: List<ContourData>? = null,
    /** Geometry actually visible in the fill pass; required for DrawDirection.CUSTOM. */
    val fillContours: List<ContourData>? = null,
    /** Multipliers over the default path alpha values. */
    val alpha: Float? = null,
    val fillAlpha: Float? = null,
    val strokeAlpha: Float? = null,
    /** Paint overrides; null retains the interpolated source/target paint. */
    val fill: BrushSpec? = null,
    val stroke: BrushSpec? = null,
    val strokeWidth: Float? = null,
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val rotationDegrees: Float? = null,
    val translationX: Float? = null,
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
    fun transform(path: CustomVectorPathState): CustomVectorPathOverrides?
}
