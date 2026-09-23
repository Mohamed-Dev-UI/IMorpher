package com.imorpher.vectormorph.core.geometry

import androidx.compose.ui.graphics.vector.PathNode
import kotlin.math.abs

/**
 * Converts `PathNode` path data (the raw command list inside an `ImageVector`) into normalized
 * cubic `ContourData`s.
 *
 * Conversions performed (all geometrically exact):
 *  - LineTo / HorizontalTo / VerticalTo → cubic with control points at thirds
 *  - QuadTo / ReflectiveQuadTo → cubic (degree elevation)
 *  - CurveTo family → kept, reflective control points resolved
 *  - ArcTo → cubic approximation (≤ 4 segments, W3C algorithm)
 *  - Close → contour marked closed; an implicit closing line segment is appended when the
 *    final point does not coincide with the start, so reveal/dash math wraps seamlessly
 *
 * Multiple `MoveTo` commands split into separate contours. Redundant commands (e.g. `Close`
 * with no prior geometry, zero-length lines) are dropped rather than duplicated.
 */
object PathNodeNormalizer {

    fun normalize(pathData: List<PathNode>): List<ContourData> {
        if (pathData.isEmpty()) return emptyList()

        val contours = ArrayList<ContourData>(4)
        var segments = ArrayList<Cubic>(16)

        var cx = 0f; var cy = 0f        // current point
        var subStartX = 0f; var subStartY = 0f // start of current sub-path
        var lastCubicX = 0f; var lastCubicY = 0f
        var lastQuadX = 0f; var lastQuadY = 0f
        var hasLastCubic = false; var hasLastQuad = false

        fun flush(closed: Boolean) {
            if (segments.isNotEmpty()) {
                contours.add(ContourData(segments, closed))
            } else if (closed && contours.isNotEmpty()) {
                // degenerate close — ignore
            }
            segments = ArrayList(16)
        }

        fun ensureBegun() {
            // MoveTo handled explicitly; nothing to do here
        }

        for (node in pathData) {
            when (node) {
                is PathNode.MoveTo -> {
                    flush(closed = false)
                    cx = node.x; cy = node.y
                    subStartX = cx; subStartY = cy
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.RelativeMoveTo -> {
                    flush(closed = false)
                    cx += node.dx; cy += node.dy
                    subStartX = cx; subStartY = cy
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.Close -> {
                    if (segments.isNotEmpty()) {
                        // append implicit closing segment if the current point differs from start
                        if (abs(cx - subStartX) > 1e-5f || abs(cy - subStartY) > 1e-5f) {
                            segments.add(Cubic.line(cx, cy, subStartX, subStartY))
                        }
                        flush(closed = true)
                    }
                    cx = subStartX; cy = subStartY
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.LineTo -> {
                    ensureBegun()
                    addLine(segments, cx, cy, node.x, node.y)
                    cx = node.x; cy = node.y
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.RelativeLineTo -> {
                    addLine(segments, cx, cy, cx + node.dx, cy + node.dy)
                    cx += node.dx; cy += node.dy
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.HorizontalTo -> {
                    addLine(segments, cx, cy, node.x, cy)
                    cx = node.x
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.RelativeHorizontalTo -> {
                    addLine(segments, cx, cy, cx + node.dx, cy)
                    cx += node.dx
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.VerticalTo -> {
                    addLine(segments, cx, cy, cx, node.y)
                    cy = node.y
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.RelativeVerticalTo -> {
                    addLine(segments, cx, cy, cx, cy + node.dy)
                    cy += node.dy
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.CurveTo -> {
                    segments.add(Cubic(cx, cy, node.x1, node.y1, node.x2, node.y2, node.x3, node.y3))
                    lastCubicX = node.x2; lastCubicY = node.y2
                    hasLastCubic = true
                    cx = node.x3; cy = node.y3
                    hasLastQuad = false
                }
                is PathNode.RelativeCurveTo -> {
                    val x1 = cx + node.dx1; val y1 = cy + node.dy1
                    val x2 = cx + node.dx2; val y2 = cy + node.dy2
                    val x3 = cx + node.dx3; val y3 = cy + node.dy3
                    segments.add(Cubic(cx, cy, x1, y1, x2, y2, x3, y3))
                    lastCubicX = x2; lastCubicY = y2
                    hasLastCubic = true
                    cx = x3; cy = y3
                    hasLastQuad = false
                }
                is PathNode.ReflectiveCurveTo -> {
                    val (rx, ry) = if (hasLastCubic) 2f * cx - lastCubicX to 2f * cy - lastCubicY else cx to cy
                    segments.add(Cubic(cx, cy, rx, ry, node.x1, node.y1, node.x2, node.y2))
                    lastCubicX = node.x1; lastCubicY = node.y1
                    hasLastCubic = true
                    cx = node.x2; cy = node.y2
                    hasLastQuad = false
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    val (rx, ry) = if (hasLastCubic) 2f * cx - lastCubicX to 2f * cy - lastCubicY else cx to cy
                    val x2 = cx + node.dx1; val y2 = cy + node.dy1
                    val x3 = cx + node.dx2; val y3 = cy + node.dy2
                    segments.add(Cubic(cx, cy, rx, ry, x2, y2, x3, y3))
                    lastCubicX = x2; lastCubicY = y2
                    hasLastCubic = true
                    cx = x3; cy = y3
                    hasLastQuad = false
                }
                is PathNode.QuadTo -> {
                    segments.add(Cubic.fromQuad(node.x1, node.y1, cx, cy, node.x2, node.y2))
                    lastQuadX = node.x1; lastQuadY = node.y1
                    hasLastQuad = true; hasLastCubic = false
                    cx = node.x2; cy = node.y2
                }
                is PathNode.RelativeQuadTo -> {
                    val qx = cx + node.dx1; val qy = cy + node.dy1
                    val ex = cx + node.dx2; val ey = cy + node.dy2
                    segments.add(Cubic.fromQuad(qx, qy, cx, cy, ex, ey))
                    lastQuadX = qx; lastQuadY = qy
                    hasLastQuad = true; hasLastCubic = false
                    cx = ex; cy = ey
                }
                is PathNode.ReflectiveQuadTo -> {
                    val (qx, qy) = if (hasLastQuad) 2f * cx - lastQuadX to 2f * cy - lastQuadY else cx to cy
                    segments.add(Cubic.fromQuad(qx, qy, cx, cy, node.x, node.y))
                    lastQuadX = qx; lastQuadY = qy
                    hasLastQuad = true; hasLastCubic = false
                    cx = node.x; cy = node.y
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val (qx, qy) = if (hasLastQuad) 2f * cx - lastQuadX to 2f * cy - lastQuadY else cx to cy
                    val ex = cx + node.dx; val ey = cy + node.dy
                    segments.add(Cubic.fromQuad(qx, qy, cx, cy, ex, ey))
                    lastQuadX = qx; lastQuadY = qy
                    hasLastQuad = true; hasLastCubic = false
                    cx = ex; cy = ey
                }
                is PathNode.ArcTo -> {
                    val segs = ArcToCubic.convert(
                        node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                        node.isMoreThanHalf, node.isPositiveArc,
                        cx, cy, node.arcStartX, node.arcStartY,
                    )
                    segments.addAll(segs)
                    cx = node.arcStartX; cy = node.arcStartY
                    hasLastCubic = false; hasLastQuad = false
                }
                is PathNode.RelativeArcTo -> {
                    val segs = ArcToCubic.convert(
                        node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                        node.isMoreThanHalf, node.isPositiveArc,
                        cx, cy, cx + node.arcStartDx, cy + node.arcStartDy,
                    )
                    segments.addAll(segs)
                    cx += node.arcStartDx; cy += node.arcStartDy
                    hasLastCubic = false; hasLastQuad = false
                }
            }
        }
        flush(closed = false)

        return contours
    }

    private fun addLine(segments: ArrayList<Cubic>, x0: Float, y0: Float, x1: Float, y1: Float) {
        // drop zero-length lines
        if (abs(x1 - x0) < 1e-6f && abs(y1 - y0) < 1e-6f) return
        segments.add(Cubic.line(x0, y0, x1, y1))
    }
}
