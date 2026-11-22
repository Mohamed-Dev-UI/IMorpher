package com.imorpher.vectormorph.core.geometry

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.imorpher.vectormorph.core.compiler.PreparedPathFactory
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.model.Transform2D
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * The geometry extracted from a Compose `Shape`/`Path`, plus extraction diagnostics.
 *
 * [contours] are normalized cubic contours in the coordinate space the adapter was asked to
 * resolve (layout pixels for a [Shape], authored units for a [Path]). [openContourCount] is the
 * number of contours that did not close on themselves; they remain morphable but keep their open
 * ends. [warnings] explains empty or unmeasurable geometry so callers can select a visual
 * transition or fix their input.
 */
class PathGeometryResult(
    val contours: List<ContourData>,
    val openContourCount: Int,
    val warnings: List<String>,
) {
    /** True when nothing drawable was extracted; the planner will select a visual transition. */
    val isEmpty: Boolean get() = contours.isEmpty()
}

/**
 * Geometry adapter for Compose [Shape]s and explicit [Path]s.
 *
 * `ImageVector` remains the reference input: shapes and paths become normalized cubic contours
 * and are then planned by the same matcher/planner. Two sources are supported:
 *
 * - **Shapes** are resolved with [Shape.createOutline] at a known size, layout direction, and
 *   density. Rectangle and rounded-rectangle outlines (including fully rounded squares) are
 *   emitted as exact lines and quarter-ellipse cubics (no flattening). Every other shape resolves
 *   to [Outline.Generic], whose path is sampled.
 * - **Explicit paths** are sampled adaptively: each contour is sampled along its measured length
 *   and simplified with Douglas–Peucker at [DEFAULT_TOLERANCE] viewport units. The result keeps
 *   contour direction and open/closed state, so holes and fill rules keep working.
 *
 * Coordinates are never normalized here. A shape resolves in layout pixels; a path stays in the
 * units it was authored in. Callers pick a viewport through [preparedVector], and the renderer
 * fits that viewport into the layout while preserving aspect ratio. Re-resolve geometry whenever
 * the size, layout direction, or density changes; [MorphShape] does this automatically.
 *
 * Geometry that cannot be measured (an empty path or a zero-sized shape) returns an empty result
 * with a warning. [preparedVector] then exposes an empty vector, and the planner's existing
 * structural fallback selects a visual transition instead of inventing correspondence.
 */
object PathGeometry {

    /** Flattening tolerance in viewport units used when a path must be sampled. */
    const val DEFAULT_TOLERANCE: Float = 0.25f

    /** Sampling cap per contour; keeps preparation bounded for very long paths. */
    const val DEFAULT_MAX_SEGMENTS_PER_CONTOUR: Int = 512

    /** Four-segment cubic approximation constant for a quarter ellipse. */
    private const val KAPPA = 0.5522848f

    private const val MIN_CONTOUR_LENGTH = 1e-3f
    private const val MIN_POINT_DISTANCE = 1e-5f

    /**
     * Extracts normalized contours from an explicit [path] by sampling its measured length.
     * Closure comes from the platform measure (not endpoint coincidence) and contour directions
     * are preserved, so fill-rule holes survive.
     */
    fun contours(
        path: Path,
        tolerance: Float = DEFAULT_TOLERANCE,
        maxSegmentsPerContour: Int = DEFAULT_MAX_SEGMENTS_PER_CONTOUR,
    ): PathGeometryResult = samplePath(
        path = path,
        tolerance = tolerance.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_TOLERANCE,
        maxSegmentsPerContour = maxSegmentsPerContour.coerceAtLeast(8),
    )

    /**
     * Converts a Compose [Outline] to contours. Rectangle and rounded-rectangle outlines are
     * exact; [Outline.Generic] is sampled from its path.
     */
    fun contours(
        outline: Outline,
        tolerance: Float = DEFAULT_TOLERANCE,
        maxSegmentsPerContour: Int = DEFAULT_MAX_SEGMENTS_PER_CONTOUR,
    ): PathGeometryResult {
        val safeTolerance = tolerance.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_TOLERANCE
        return when (outline) {
            is Outline.Rectangle -> exact(rectangleContours(outline.rect))
            is Outline.Rounded -> exact(roundedRectangleContours(outline.roundRect))
            is Outline.Generic -> contours(outline.path, safeTolerance, maxSegmentsPerContour)
        }
    }

    /**
     * Resolves [shape] at [size] in the given [layoutDirection] and [density], then converts its
     * outline. Shapes that depend on size or density (corner radii in dp, percentage corners,
     * custom shapes) therefore re-resolve when either changes.
     */
    fun contours(
        shape: Shape,
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
        tolerance: Float = DEFAULT_TOLERANCE,
        maxSegmentsPerContour: Int = DEFAULT_MAX_SEGMENTS_PER_CONTOUR,
    ): PathGeometryResult {
        if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
            return PathGeometryResult(
                contours = emptyList(),
                openContourCount = 0,
                warnings = listOf("shape has no usable layout size (${size.width} x ${size.height})"),
            )
        }
        val outline = try {
            shape.createOutline(size, layoutDirection, density)
        } catch (exception: RuntimeException) {
            return PathGeometryResult(
                contours = emptyList(),
                openContourCount = 0,
                warnings = listOf(
                    "shape.createOutline failed: ${exception.message ?: exception.javaClass.simpleName}",
                ),
            )
        }
        return contours(outline, tolerance, maxSegmentsPerContour)
    }

    /**
     * Compiles extracted [geometry] into a single-path [PreparedVector] for the planner.
     *
     * [viewportWidth] and [viewportHeight] are the coordinate-space size the callers authored the
     * geometry in — layout pixels after resolving a shape, or the path's own units. The renderer
     * fits that viewport into the layout without changing aspect ratio. Empty geometry produces a
     * vector with no paths so the planner reports and renders its structural fallback.
     *
     * [fill] and [stroke] are optional Compose brushes converted through [BrushSpec]; built-in
     * solid/linear/radial/sweep brushes interpolate, unknown brushes trigger the documented
     * crossfade fallback for the pair.
     */
    fun preparedVector(
        geometry: PathGeometryResult,
        viewportWidth: Float,
        viewportHeight: Float,
        name: String = "path",
        fill: Brush? = null,
        fillAlpha: Float = 1f,
        stroke: Brush? = null,
        strokeAlpha: Float = 1f,
        strokeWidth: Float = 0f,
        strokeLineCap: StrokeCap = StrokeCap.Butt,
        strokeLineJoin: StrokeJoin = StrokeJoin.Miter,
        strokeLineMiter: Float = 4f,
        pathFillType: PathFillType = PathFillType.NonZero,
    ): PreparedVector {
        val vw = viewportWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
        val vh = viewportHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (geometry.contours.isEmpty()) {
            return PreparedVector(
                viewportWidth = vw,
                viewportHeight = vh,
                defaultWidth = vw,
                defaultHeight = vh,
                name = name,
                paths = emptyList(),
            )
        }
        val path = PreparedPathFactory.build(
            name = name,
            index = 0,
            groupNames = emptyList(),
            contours = geometry.contours,
            fill = BrushSpec.from(fill),
            fillAlpha = fillAlpha,
            stroke = BrushSpec.from(stroke),
            strokeAlpha = strokeAlpha,
            strokeWidth = strokeWidth,
            strokeLineCap = strokeLineCap,
            strokeLineJoin = strokeLineJoin,
            strokeLineMiter = strokeLineMiter,
            pathFillType = pathFillType,
            clipContours = emptyList(),
            sourceTransform = Transform2D.Identity,
        )
        return PreparedVector(
            viewportWidth = vw,
            viewportHeight = vh,
            defaultWidth = vw,
            defaultHeight = vh,
            name = name,
            paths = listOf(path),
        )
    }

    // ------------------------------------------------------------------ exact outlines

    private fun exact(contours: List<ContourData>): PathGeometryResult =
        if (contours.isEmpty()) {
            PathGeometryResult(emptyList(), 0, listOf("outline has no positive area"))
        } else {
            PathGeometryResult(contours, 0, emptyList())
        }

    /** Axis-aligned rectangle as four line cubics, clockwise in screen coordinates. */
    private fun rectangleContours(rect: Rect): List<ContourData> {
        if (!(rect.width > 0f) || !(rect.height > 0f)) return emptyList()
        val closed = ContourData(
            segments = listOf(
                Cubic.line(rect.left, rect.top, rect.right, rect.top),
                Cubic.line(rect.right, rect.top, rect.right, rect.bottom),
                Cubic.line(rect.right, rect.bottom, rect.left, rect.bottom),
                Cubic.line(rect.left, rect.bottom, rect.left, rect.top),
            ),
            closed = true,
        )
        return listOf(closed)
    }

    /**
     * Rounded rectangle as alternating edges and quarter-ellipse corner cubics. Every corner
     * radius is clamped to half the rectangle's width/height; a zero-radius corner becomes a
     * straight line, and an all-zero rectangle takes the plain rectangle path.
     */
    private fun roundedRectangleContours(roundRect: RoundRect): List<ContourData> {
        val left = roundRect.left
        val top = roundRect.top
        val right = roundRect.right
        val bottom = roundRect.bottom
        val width = right - left
        val height = bottom - top
        if (!(width > 0f) || !(height > 0f)) return emptyList()

        fun clamp(radius: CornerRadius): CornerRadius = CornerRadius(
            radius.x.coerceIn(0f, width / 2f),
            radius.y.coerceIn(0f, height / 2f),
        )

        val tl = clamp(roundRect.topLeftCornerRadius)
        val tr = clamp(roundRect.topRightCornerRadius)
        val br = clamp(roundRect.bottomRightCornerRadius)
        val bl = clamp(roundRect.bottomLeftCornerRadius)
        val segments = ArrayList<Cubic>(8)
        val start = Offset(left + tl.x, top)
        var current = start

        fun lineTo(x: Float, y: Float) {
            if (hypot(x - current.x, y - current.y) > MIN_POINT_DISTANCE) {
                segments.add(Cubic.line(current.x, current.y, x, y))
                current = Offset(x, y)
            }
        }

        fun arcTo(endX: Float, endY: Float, arc: Cubic?) {
            if (arc != null) {
                segments.add(arc)
            } else if (hypot(endX - current.x, endY - current.y) > MIN_POINT_DISTANCE) {
                segments.add(Cubic.line(current.x, current.y, endX, endY))
            }
            current = Offset(endX, endY)
        }

        // Top edge, top-right corner.
        lineTo(right - tr.x, top)
        arcTo(
            right,
            top + tr.y,
            if (tr.x > 0f && tr.y > 0f) {
                topRightArc(right - tr.x, top + tr.y, tr.x, tr.y)
            } else null,
        )
        // Right edge, bottom-right corner.
        lineTo(right, bottom - br.y)
        arcTo(
            right - br.x,
            bottom,
            if (br.x > 0f && br.y > 0f) {
                bottomRightArc(right - br.x, bottom - br.y, br.x, br.y)
            } else null,
        )
        // Bottom edge, bottom-left corner.
        lineTo(left + bl.x, bottom)
        arcTo(
            left,
            bottom - bl.y,
            if (bl.x > 0f && bl.y > 0f) {
                bottomLeftArc(left + bl.x, bottom - bl.y, bl.x, bl.y)
            } else null,
        )
        // Left edge, top-left corner.
        lineTo(left, top + tl.y)
        arcTo(
            start.x,
            start.y,
            if (tl.x > 0f && tl.y > 0f) {
                topLeftArc(left + tl.x, top + tl.y, tl.x, tl.y)
            } else null,
        )
        if (segments.isEmpty()) return emptyList()
        return listOf(ContourData(segments, closed = true))
    }

    // ------------------------------------------------------------------ sampling

    private fun samplePath(path: Path, tolerance: Float, maxSegmentsPerContour: Int): PathGeometryResult {
        val contours = ArrayList<ContourData>(4)
        val warnings = ArrayList<String>(2)
        var openContours = 0
        try {
            // Android's PathMeasure iterates sub-paths; Compose's common PathMeasure does not.
            val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
            do {
                val length = measure.length
                if (length.isFinite() && length > MIN_CONTOUR_LENGTH) {
                    val step = max(tolerance, length / maxSegmentsPerContour.toFloat())
                    val steps = ceil(length / step).toInt().coerceIn(8, maxSegmentsPerContour)
                    val points = ArrayList<Offset>(steps + 1)
                    val position = FloatArray(2)
                    for (i in 0..steps) {
                        if (!measure.getPosTan(length * i / steps, position, null)) continue
                        val point = Offset(position[0], position[1])
                        if (!point.x.isFinite() || !point.y.isFinite()) continue
                        if (points.isEmpty() || distance(points.last(), point) > MIN_POINT_DISTANCE) {
                            points.add(point)
                        }
                    }
                    if (points.size >= 2) {
                        val closed = measure.isClosed
                        val reduced = simplify(points, tolerance)
                        if (reduced.size >= 2) {
                            contours.add(contourFrom(reduced, closed))
                            if (!closed) openContours++
                        }
                    }
                }
            } while (measure.nextContour())
        } catch (exception: RuntimeException) {
            warnings.add("path could not be measured: ${exception.message ?: exception.javaClass.simpleName}")
        }
        if (contours.isEmpty()) {
            warnings.add("path contains no measurable contours")
        }
        return PathGeometryResult(contours.toList(), openContours, warnings.toList())
    }

    /** Builds a line-cubic contour from simplified sample points, closing it when detected. */
    private fun contourFrom(points: List<Offset>, closed: Boolean): ContourData {
        val segments = ArrayList<Cubic>(points.size)
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (distance(a, b) > MIN_POINT_DISTANCE) {
                segments.add(Cubic.line(a.x, a.y, b.x, b.y))
            }
        }
        if (closed && points.size > 2) {
            val first = points.first()
            val last = points.last()
            if (distance(first, last) > MIN_POINT_DISTANCE) {
                segments.add(Cubic.line(last.x, last.y, first.x, first.y))
            }
        }
        return ContourData(segments, closed = closed)
    }

    /**
     * Douglas–Peucker simplification over sampled points. Keeps endpoints and every point whose
     * distance from the retained chord exceeds [tolerance], which preserves corners while
     * collapsing long straight runs and gentle curves.
     */
    private fun simplify(points: List<Offset>, tolerance: Float): List<Offset> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val pending = ArrayDeque<IntArray>(8)
        pending.addLast(intArrayOf(0, points.lastIndex))
        while (pending.isNotEmpty()) {
            val range = pending.removeLast()
            val start = range[0]
            val end = range[1]
            if (end - start < 2) continue
            var farthest = -1
            var farthestDistance = 0f
            for (i in start + 1 until end) {
                val d = distanceToSegment(points[i], points[start], points[end])
                if (d > farthestDistance) {
                    farthestDistance = d
                    farthest = i
                }
            }
            if (farthest > 0 && farthestDistance > tolerance) {
                keep[farthest] = true
                pending.addLast(intArrayOf(start, farthest))
                pending.addLast(intArrayOf(farthest, end))
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= MIN_POINT_DISTANCE) return distance(point, start)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
    }

    private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

    // ------------------------------------------------------------------ quarter-ellipse cubics

    private fun topRightArc(cx: Float, cy: Float, rx: Float, ry: Float): Cubic = Cubic(
        cx, cy - ry,
        cx + rx * KAPPA, cy - ry,
        cx + rx, cy - ry * KAPPA,
        cx + rx, cy,
    )

    private fun bottomRightArc(cx: Float, cy: Float, rx: Float, ry: Float): Cubic = Cubic(
        cx + rx, cy,
        cx + rx, cy + ry * KAPPA,
        cx + rx * KAPPA, cy + ry,
        cx, cy + ry,
    )

    private fun bottomLeftArc(cx: Float, cy: Float, rx: Float, ry: Float): Cubic = Cubic(
        cx, cy + ry,
        cx - rx * KAPPA, cy + ry,
        cx - rx, cy + ry * KAPPA,
        cx - rx, cy,
    )

    private fun topLeftArc(cx: Float, cy: Float, rx: Float, ry: Float): Cubic = Cubic(
        cx - rx, cy,
        cx - rx, cy - ry * KAPPA,
        cx - rx * KAPPA, cy - ry,
        cx, cy - ry,
    )
}
