package com.imorpher.vectormorph.core.compiler

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.PathNodeNormalizer
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.model.Transform2D
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Compiles an [ImageVector] into an immutable [PreparedVector].
 *
 * - walks the group tree and bakes static group transforms into absolute viewport coordinates
 *   (the viewport size is preserved separately, so icons never change visual scale)
 * - resolves path names (default names like "path" are disambiguated as "path[1]", "path[2]"…)
 * - captures inherited clip paths in absolute space
 * - precomputes features (bounds, centroid, area, arc lengths, winding) used by matching
 */
object VectorCompiler {

    /**
     * Compiles [vector] into an immutable [PreparedVector]: the group tree is flattened
     * (static transforms baked into viewport coordinates), path names disambiguated,
     * clip paths captured in absolute space, and per-path features precomputed. Compilation
     * is pure — the same ImageVector always produces an equal PreparedVector — so results
     * can be cached and shared.
     */
    fun compile(vector: ImageVector): PreparedVector {
        val paths = ArrayList<PreparedPath>(16)
        val nameCount = HashMap<String, Int>(16)
        walk(
            node = vector.root,
            matrix = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
            groupNames = emptyList(),
            inheritedClip = emptyList(),
            paths = paths,
            nameCount = nameCount,
            isRoot = true,
        )
        return PreparedVector(
            viewportWidth = vector.viewportWidth,
            viewportHeight = vector.viewportHeight,
            defaultWidth = vector.defaultWidth.value,
            defaultHeight = vector.defaultHeight.value,
            name = vector.name,
            paths = paths,
        )
    }

    // ------------------------------------------------------------------ tree walk

    /** Depth-first walk of the group tree, accumulating transforms, clip paths, and paths. */
    private fun walk(
        node: VectorNode,
        matrix: FloatArray,
        groupNames: List<String>,
        inheritedClip: List<ContourData>,
        paths: MutableList<PreparedPath>,
        nameCount: MutableMap<String, Int>,
        isRoot: Boolean = false,
    ) {
        when (node) {
            is VectorGroup -> {
                val localTransform = groupTransform(node)
                val childMatrix = if (isIdentity(localTransform)) matrix else multiply(matrix, localTransform)

                val childClip = if (node.clipPathData.isNotEmpty()) {
                    val localClip = PathNodeNormalizer.normalize(node.clipPathData)
                    inheritedClip + localClip.map { transformContour(it, childMatrix) }
                } else {
                    inheritedClip
                }

                // The ImageVector root is an implementation detail of ImageVector.Builder, not a
                // user-declared scope: opaque vectors put every path straight under it, and treating
                // it as a group would make each path report a group it was never grouped into.
                val childNames = if (isRoot) groupNames else groupNames + listOf(node.name)
                for (child in node) {
                    walk(child, childMatrix, childNames, childClip, paths, nameCount)
                }
            }
            is VectorPath -> {
                val contours = PathNodeNormalizer.normalize(node.pathData)
                    .map { transformContour(it, matrix) }
                paths.add(
                    PreparedPathFactory.build(
                        name = uniqueName(node.name, nameCount),
                        index = paths.size,
                        groupNames = groupNames,
                        contours = contours,
                        fill = BrushSpec.from(node.fill),
                        fillAlpha = node.fillAlpha,
                        stroke = BrushSpec.from(node.stroke),
                        strokeAlpha = node.strokeAlpha,
                        strokeWidth = node.strokeLineWidth,
                        strokeLineCap = node.strokeLineCap,
                        strokeLineJoin = node.strokeLineJoin,
                        strokeLineMiter = node.strokeLineMiter,
                        pathFillType = node.pathFillType,
                        clipContours = inheritedClip,
                        sourceTransform = decompose2D(matrix),
                    )
                )
            }
        }
    }

    /** Disambiguates repeated path names ("path", "path[1]", "path[2]"…) in declaration order. */
    private fun uniqueName(raw: String, nameCount: MutableMap<String, Int>): String {
        val count = nameCount.getOrPut(raw) { 0 }
        nameCount[raw] = count + 1
        return if (count == 0) raw else "$raw[$count]"
    }

    // ------------------------------------------------------------------ matrix math
    // Compose `Matrix` is column-major: m[col * 4 + row]. For 2D:
    //   x' = m[0]*x + m[4]*y + m[12]
    //   y' = m[1]*x + m[5]*y + m[13]

    /**
     * Transforms a 2D point by the column-major matrix [m] (the layout of the matrices
     * built by [groupTransform] and [multiply]).
     */
    fun transformPoint(m: FloatArray, x: Float, y: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[12],
        m[1] * x + m[5] * y + m[13],
    )

    /** Compose's public VectorGroup fields expanded to its equivalent 2D affine matrix. */
    private fun groupTransform(group: VectorGroup): FloatArray {
        val radians = Math.toRadians(group.rotation.toDouble())
        val c = kotlin.math.cos(radians).toFloat()
        val s = kotlin.math.sin(radians).toFloat()
        val sx = group.scaleX
        val sy = group.scaleY
        val px = group.pivotX
        val py = group.pivotY
        return floatArrayOf(
            c * sx, s * sx, 0f, 0f,
            -s * sy, c * sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            group.translationX + px - c * sx * px + s * sy * py,
            group.translationY + py - s * sx * px - c * sy * py,
            0f, 1f,
        )
    }

    /** parent ∘ child (column-major 4x4 multiply, applied child-last). */
    fun multiply(parent: FloatArray, child: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += parent[k * 4 + r] * child[c * 4 + k]
                }
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    /**
     * Applies the column-major matrix [m] to every control point of [contour]. Returns the
     * original instance unchanged when [m] is the identity, so callers can compare by identity.
     */
    fun transformContour(contour: ContourData, m: FloatArray): ContourData {
        if (isIdentity(m)) return contour
        val segs = contour.segments.map { seg ->
            val a = transformPoint(m, seg.x0, seg.y0)
            val b = transformPoint(m, seg.x1, seg.y1)
            val c = transformPoint(m, seg.x2, seg.y2)
            val d = transformPoint(m, seg.x3, seg.y3)
            com.imorpher.vectormorph.core.geometry.Cubic(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])
        }
        return ContourData(segs, contour.closed)
    }

    /** True when [m] transforms 2D points identically (no scale, rotation, or translation). */
    private fun isIdentity(m: FloatArray): Boolean =
        m[0] == 1f && m[1] == 0f && m[4] == 0f && m[5] == 1f && m[12] == 0f && m[13] == 0f

    /** Extracts translation/scale/rotation from a composed affine matrix (for diagnostics + pivots). */
    fun decompose2D(m: FloatArray): Transform2D {
        if (isIdentity(m)) return Transform2D.Identity
        val a = m[0]; val b = m[1]
        val c = m[4]; val d = m[5]
        val det = a * d - b * c
        val scaleX = hypot(a, b)
        val scaleYRaw = hypot(c, d)
        val scaleY = if (det < 0f) -scaleYRaw else scaleYRaw
        val rotation = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
        return Transform2D(
            translationX = m[12],
            translationY = m[13],
            scaleX = scaleX,
            scaleY = scaleY,
            rotationDeg = rotation,
            pivotX = 0f,
            pivotY = 0f,
        )
    }

    // ------------------------------------------------------------------ small helpers

    /** Fills style defaults used by synthetic paths (tests, fade-in stubs). */
    fun defaultFillType(): PathFillType = PathFillType.NonZero
    /** Stroke cap used by synthetic paths (tests, fade-in stubs). */
    fun defaultStrokeCap(): StrokeCap = StrokeCap.Butt

    /** Stroke join used by synthetic paths (tests, fade-in stubs). */
    fun defaultStrokeJoin(): StrokeJoin = StrokeJoin.Miter
}
