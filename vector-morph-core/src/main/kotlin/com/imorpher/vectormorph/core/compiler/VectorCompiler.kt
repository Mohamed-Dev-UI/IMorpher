package com.imorpher.vectormorph.core.compiler

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.imorpher.vectormorph.core.geometry.ArcLengthData
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.PathNodeNormalizer
import com.imorpher.vectormorph.core.geometry.arcFractionNear
import com.imorpher.vectormorph.core.geometry.bounds
import com.imorpher.vectormorph.core.geometry.centroid
import com.imorpher.vectormorph.core.geometry.measureContour
import com.imorpher.vectormorph.core.geometry.signedArea
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.PathStart
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
                val resolvedName = uniqueName(node.name, nameCount)
                val clip = inheritedClip

                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var sumArea = 0f
                var sumLen = 0f
                val contourLens = FloatArray(contours.size)
                val windings = IntArray(contours.size)
                val arcs = ArrayList<ArcLengthData>(contours.size)
                var cx = 0f; var cy = 0f; var cWeight = 0f

                for ((i, c) in contours.withIndex()) {
                    val b = bounds(c)
                    if (b[0] < minX) minX = b[0]
                    if (b[1] < minY) minY = b[1]
                    if (b[2] > maxX) maxX = b[2]
                    if (b[3] > maxY) maxY = b[3]
                    val area = signedArea(c)
                    sumArea += kotlin.math.abs(area)
                    val arc = measureContour(c)
                    contourLens[i] = arc.totalLength
                    sumLen += arc.totalLength
                    windings[i] = if (area > 1e-6f) 1 else if (area < -1e-6f) -1 else 0
                    arcs.add(arc)
                    val ctr = centroid(c)
                    val w = kotlin.math.abs(area).coerceAtLeast(1e-3f)
                    cx += ctr[0] * w; cy += ctr[1] * w; cWeight += w
                }
                if (cWeight > 0f) { cx /= cWeight; cy /= cWeight }

                // Precompute reveal start anchors: per contour, the arc fraction of each PathStart.
                val startFractions = ArrayList<FloatArray>(contours.size)
                if (contours.isNotEmpty()) {
                    val bw = (maxX - minX).coerceAtLeast(1e-4f)
                    val bh = (maxY - minY).coerceAtLeast(1e-4f)
                    for ((i, c) in contours.withIndex()) {
                        val arc = arcs[i]
                        val fr = FloatArray(PathStart.entries.size)
                        for (ps in PathStart.entries) {
                            fr[ps.ordinal] = when (ps) {
                                PathStart.START -> 0f
                                PathStart.END -> 1f
                                PathStart.CENTER -> 0.5f
                                PathStart.CUSTOM -> 0f // resolved per-path from configuration
                                else -> {
                                    val (ax, ay) = anchorFor(ps, minX, minY, maxX, maxY, bw, bh)
                                    arcFractionNear(c, arc, ax, ay)
                                }
                            }
                        }
                        startFractions.add(fr)
                    }
                }

                paths.add(
                    PreparedPath(
                        name = resolvedName,
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
                        blendMode = BlendMode.SrcOver,
                        pathFillType = node.pathFillType,
                        clipContours = clip,
                        sourceTransform = decompose2D(matrix),
                        bounds = if (contours.isEmpty()) floatArrayOf(0f, 0f, 0f, 0f)
                        else floatArrayOf(minX, minY, maxX, maxY),
                        centroidX = cx,
                        centroidY = cy,
                        area = sumArea,
                        totalLength = sumLen,
                        contourLengths = contourLens,
                        windingSigns = windings,
                        arcLengths = arcs,
                        startFractions = startFractions,
                    )
                )
            }
        }
    }

    private fun uniqueName(raw: String, nameCount: MutableMap<String, Int>): String {
        val count = nameCount.getOrPut(raw) { 0 }
        nameCount[raw] = count + 1
        return if (count == 0) raw else "$raw[$count]"
    }

    /** The anchor point on the path bounds for a [PathStart] enum (reveal start search target). */
    private fun anchorFor(
        ps: PathStart,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        bw: Float, bh: Float,
    ): Pair<Float, Float> = when (ps) {
        PathStart.TOP -> Pair(minX + bw / 2f, minY)
        PathStart.RIGHT -> Pair(maxX, minY + bh / 2f)
        PathStart.BOTTOM -> Pair(minX + bw / 2f, maxY)
        PathStart.LEFT -> Pair(minX, minY + bh / 2f)
        PathStart.TOP_LEFT -> Pair(minX, minY)
        PathStart.TOP_RIGHT -> Pair(maxX, minY)
        PathStart.BOTTOM_LEFT -> Pair(minX, maxY)
        PathStart.BOTTOM_RIGHT -> Pair(maxX, maxY)
        else -> Pair((minX + maxX) / 2f, (minY + maxY) / 2f)
    }

    // ------------------------------------------------------------------ matrix math
    // Compose `Matrix` is column-major: m[col * 4 + row]. For 2D:
    //   x' = m[0]*x + m[4]*y + m[12]
    //   y' = m[1]*x + m[5]*y + m[13]

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
    fun defaultStrokeCap(): StrokeCap = StrokeCap.Butt
    fun defaultStrokeJoin(): StrokeJoin = StrokeJoin.Miter
}
