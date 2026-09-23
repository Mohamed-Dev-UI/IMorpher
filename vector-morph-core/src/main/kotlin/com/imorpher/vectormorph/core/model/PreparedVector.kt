package com.imorpher.vectormorph.core.model

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.gradients.BrushSpec

/**
 * Immutable, pre-processed vector: the output of [com.imorpher.vectormorph.core.compiler.VectorCompiler].
 *
 * The hierarchy of the source `ImageVector` is flattened into an ordered list of [PreparedPath]s.
 * Static group transforms are baked into absolute viewport coordinates (the viewport itself is
 * kept separate so the visual scale of the icon never changes), while group ancestry is retained
 * by name so animations can still target nested groups.
 */
class PreparedVector(
    val viewportWidth: Float,
    val viewportHeight: Float,
    /** Intrinsic dp size of the source vector (informational; rendering uses layout size). */
    val defaultWidth: Float,
    val defaultHeight: Float,
    val name: String,
    val paths: List<PreparedPath>,
) {
    val boundsDiagonal: Float by lazy {
        val w = viewportWidth
        val h = viewportHeight
        kotlin.math.sqrt(w * w + h * h)
    }
}

/** A single renderable path with resolved styles and precomputed geometric features. */
class PreparedPath(
    /** Declared path name in the ImageVector (defaults are disambiguated, see VectorCompiler). */
    val name: String,
    /** Index within the source vector (declaration order). */
    val index: Int,
    /** Names of enclosing groups, outermost first. */
    val groupNames: List<String>,
    /** Normalized cubic contours in absolute viewport coordinates. */
    val contours: List<ContourData>,
    val fill: BrushSpec?,
    val fillAlpha: Float,
    val stroke: BrushSpec?,
    val strokeAlpha: Float,
    val strokeWidth: Float,
    val strokeLineCap: StrokeCap,
    val strokeLineJoin: StrokeJoin,
    val strokeLineMiter: Float,
    val pathFillType: androidx.compose.ui.graphics.PathFillType,
    val blendMode: BlendMode,
    /** Clip contours inherited from enclosing groups, in absolute coordinates (may be empty). */
    val clipContours: List<ContourData>,
    /** Static per-path transform overlay extracted from source groups (rare; usually identity). */
    val sourceTransform: Transform2D,
    // ---- precomputed features ----
    val bounds: FloatArray,          // minX, minY, maxX, maxY
    val centroidX: Float,
    val centroidY: Float,
    val area: Float,                 // |signed area| summed over contours
    val totalLength: Float,          // summed over contours
    val contourLengths: FloatArray,
    /** +1 = clockwise on screen (Y-down), -1 = counter-clockwise, 0 = degenerate. Per contour. */
    val windingSigns: IntArray,
    /** Per-contour arc-length data (aligned with [contours]). */
    val arcLengths: List<com.imorpher.vectormorph.core.geometry.ArcLengthData>,
    /**
     * Per contour, the normalized arc fraction (0..1) of each [com.imorpher.vectormorph.core.model.PathStart]
     * anchor (indexed by ordinal) — precomputed so reveal start points cost nothing at frame time.
     */
    val startFractions: List<FloatArray>,
) {
    val isEmpty: Boolean get() = contours.isEmpty()

    val fillTypeDescription: String get() = "path#$index($name)"
}

/**
 * A 2D affine transform used for group-level animation overlays
 * (translation, rotation, scale around a pivot).
 */
data class Transform2D(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Degrees, clockwise in Y-down screen space. */
    val rotationDeg: Float = 0f,
    /** Pivot in absolute viewport coordinates. */
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = translationX == 0f && translationY == 0f &&
            scaleX == 1f && scaleY == 1f && rotationDeg == 0f

    fun scaled(f: Float): Transform2D = copy(
        translationX = translationX * f, translationY = translationY * f,
        scaleX = 1f + (scaleX - 1f) * f, scaleY = 1f + (scaleY - 1f) * f,
        rotationDeg = rotationDeg * f,
    )

    companion object {
        val Identity = Transform2D()
    }
}
