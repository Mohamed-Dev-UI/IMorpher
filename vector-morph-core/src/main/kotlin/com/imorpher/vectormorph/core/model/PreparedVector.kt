package com.imorpher.vectormorph.core.model

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

    /**
     * Deterministic structural hash (FNV-1a, 64-bit lowercase hex) of everything planning reads.
     *
     * Two vectors compiled from equal `ImageVector` content always share one key, so
     * equal-but-distinct instances hit the same [com.imorpher.vectormorph.core.plan.MorphPlanCache]
     * entry. Geometry is hashed coordinate-by-coordinate because [ContourData] uses identity
     * equality; brushes are hashed structurally through their data-class equals.
     */
    val contentKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var hash = FNV_OFFSET_BASIS

        fun addBits(bits: Int) {
            hash = (hash xor bits.toLong()) * FNV_PRIME
        }

        fun addLong(value: Long) {
            addBits(value.toInt())
            addBits((value ushr 32).toInt())
        }

        fun addFloat(value: Float) = addBits(value.toRawBits())

        fun addString(value: String) {
            for (c in value) addBits(c.code)
            addBits(SEPARATOR)
        }

        fun addContour(contour: ContourData) {
            addBits(if (contour.closed) 1 else 0)
            addBits(contour.segments.size)
            for (segment in contour.segments) {
                addFloat(segment.x0); addFloat(segment.y0)
                addFloat(segment.x1); addFloat(segment.y1)
                addFloat(segment.x2); addFloat(segment.y2)
                addFloat(segment.x3); addFloat(segment.y3)
            }
        }

        addFloat(viewportWidth); addFloat(viewportHeight)
        addBits(paths.size)
        for (path in paths) {
            addString(path.name)
            addBits(path.index)
            addBits(path.groupNames.size)
            path.groupNames.forEach(::addString)
            addBits(path.contours.size)
            path.contours.forEach(::addContour)
            addBits(path.fill.hashCode())
            addBits(path.stroke.hashCode())
            addFloat(path.fillAlpha)
            addFloat(path.strokeAlpha)
            addFloat(path.strokeWidth)
            addString(path.strokeLineCap.toString())
            addString(path.strokeLineJoin.toString())
            addFloat(path.strokeLineMiter)
            addString(path.pathFillType.toString())
            addBits(path.clipContours.size)
            path.clipContours.forEach(::addContour)
            addFloat(path.sourceTransform.translationX)
            addFloat(path.sourceTransform.translationY)
            addFloat(path.sourceTransform.scaleX)
            addFloat(path.sourceTransform.scaleY)
            addFloat(path.sourceTransform.rotationDeg)
            addFloat(path.sourceTransform.pivotX)
            addFloat(path.sourceTransform.pivotY)
        }
        java.lang.Long.toHexString(hash)
    }

    private companion object {
        /** FNV-1a 64-bit offset basis and prime. */
        const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
        const val FNV_PRIME: Long = 1099511628211L // 0x100000001b3

        /** Field separator: keeps multi-part keys from colliding across boundaries. */
        const val SEPARATOR = 0x1f
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
    /** Clip contours inherited from enclosing groups, in absolute coordinates (may be empty). */
    val clipContours: List<ContourData>,
    /** Static per-path transform overlay extracted from source groups (rare; usually identity). */
    val sourceTransform: Transform2D,
    // ---- precomputed features ----
    /** Path bounds as (minX, minY, maxX, maxY) in viewport coordinates. */
    val bounds: FloatArray,
    /** Area-weighted centroid across all contours (weights guard against zero-area shapes). */
    val centroidX: Float,
    val centroidY: Float,
    /** Total |signed area| summed over contours — the fill footprint used for matching. */
    val area: Float,
    /** Total polyline length summed over contours — drives constant-speed timing. */
    val totalLength: Float,
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
