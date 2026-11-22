package com.imorpher.vectormorph.core.compiler

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import com.imorpher.vectormorph.core.geometry.ArcLengthData
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.arcFractionNear
import com.imorpher.vectormorph.core.geometry.bounds
import com.imorpher.vectormorph.core.geometry.centroid
import com.imorpher.vectormorph.core.geometry.measureContour
import com.imorpher.vectormorph.core.geometry.signedArea
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.Transform2D
import kotlin.math.abs

/**
 * Builds one [PreparedPath] from already-normalized contours.
 *
 * [VectorCompiler] uses this for `ImageVector` paths; geometry adapters (Compose `Shape` and
 * `Path`) use it so every input family feeds the planner the same precomputed features: bounds,
 * centroid, area, arc lengths, winding signs, and reveal start fractions.
 */
internal object PreparedPathFactory {

    /**
     * Computes every planning feature for [contours] and returns an immutable [PreparedPath].
     * Pure: equal inputs produce equal features, so adapter results cache under the same
     * structural content keys as compiled vectors.
     */
    fun build(
        name: String,
        index: Int,
        groupNames: List<String>,
        contours: List<ContourData>,
        fill: BrushSpec?,
        fillAlpha: Float,
        stroke: BrushSpec?,
        strokeAlpha: Float,
        strokeWidth: Float,
        strokeLineCap: StrokeCap,
        strokeLineJoin: StrokeJoin,
        strokeLineMiter: Float,
        pathFillType: PathFillType,
        clipContours: List<ContourData>,
        sourceTransform: Transform2D,
    ): PreparedPath {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var sumArea = 0f
        var sumLen = 0f
        val contourLens = FloatArray(contours.size)
        val windings = IntArray(contours.size)
        val arcs = ArrayList<ArcLengthData>(contours.size)
        var cx = 0f
        var cy = 0f
        var cWeight = 0f

        for ((i, c) in contours.withIndex()) {
            val b = bounds(c)
            if (b[0] < minX) minX = b[0]
            if (b[1] < minY) minY = b[1]
            if (b[2] > maxX) maxX = b[2]
            if (b[3] > maxY) maxY = b[3]
            val area = signedArea(c)
            sumArea += abs(area)
            val arc = measureContour(c)
            contourLens[i] = arc.totalLength
            sumLen += arc.totalLength
            windings[i] = if (area > 1e-6f) 1 else if (area < -1e-6f) -1 else 0
            arcs.add(arc)
            val ctr = centroid(c)
            val w = abs(area).coerceAtLeast(1e-3f)
            cx += ctr[0] * w
            cy += ctr[1] * w
            cWeight += w
        }
        if (cWeight > 0f) {
            cx /= cWeight
            cy /= cWeight
        }

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

        return PreparedPath(
            name = name,
            index = index,
            groupNames = groupNames,
            contours = contours,
            fill = fill,
            fillAlpha = fillAlpha,
            stroke = stroke,
            strokeAlpha = strokeAlpha,
            strokeWidth = strokeWidth,
            strokeLineCap = strokeLineCap,
            strokeLineJoin = strokeLineJoin,
            strokeLineMiter = strokeLineMiter,
            pathFillType = pathFillType,
            clipContours = clipContours,
            sourceTransform = sourceTransform,
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
    }

    /** Bounds-relative anchor point a [PathStart] reveal searches the contour for. */
    private fun anchorFor(
        ps: PathStart,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        bw: Float,
        bh: Float,
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
}
