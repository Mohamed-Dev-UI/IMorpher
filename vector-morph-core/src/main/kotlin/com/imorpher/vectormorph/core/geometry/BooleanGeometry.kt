package com.imorpher.vectormorph.core.geometry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation

/** Optional boolean operations for clipping, masks, and custom vector effects. */
object BooleanGeometry {
    fun union(first: Path, second: Path): Path = Path.combine(PathOperation.Union, first, second)
    fun intersection(first: Path, second: Path): Path = Path.combine(PathOperation.Intersect, first, second)
    fun difference(first: Path, second: Path): Path = Path.combine(PathOperation.Difference, first, second)
    fun subtract(first: Path, second: Path): Path = difference(first, second)

    /** Builds a Compose path without modifying the supplied immutable contour data. */
    fun path(contours: Iterable<ContourData>): Path = Path().also { out ->
        contours.forEach { contour ->
            val first = contour.segments.firstOrNull() ?: return@forEach
            out.moveTo(first.x0, first.y0)
            contour.segments.forEach { out.cubicTo(it.x1, it.y1, it.x2, it.y2, it.x3, it.y3) }
            if (contour.closed) out.close()
        }
    }

    /** Convenience constructor for custom masks and boolean operands. */
    fun rectangle(left: Float, top: Float, right: Float, bottom: Float): Path = Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    fun circle(center: Offset, radius: Float): Path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(
            center.x - radius, center.y - radius,
            center.x + radius, center.y + radius,
        ))
    }
}
