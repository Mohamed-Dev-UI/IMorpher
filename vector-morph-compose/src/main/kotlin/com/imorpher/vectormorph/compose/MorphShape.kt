package com.imorpher.vectormorph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.geometry.PathGeometry
import com.imorpher.vectormorph.core.model.MorphConfiguration

/** Resolved layout size used when a caller passes [Dp.Unspecified] for one axis. */
private val DefaultShapeSize = 24.dp

/**
 * Progress-driven **geometry morph** between two Compose [Shape]s with solid endpoint fills.
 *
 * This convenience overload paints a solid fill. For gradient fills, strokes, or independent
 * fill/stroke styling, use the [MorphPaint] overload.
 *
 * Both shapes are resolved with [Shape.createOutline] at the rendered layout size (in pixels),
 * layout direction, and density, then compiled into the same normalized contours, matching, and
 * interpolation pipeline as `ImageVector` morphs. Rectangle and rounded-rectangle outlines are
 * emitted exactly (lines plus quarter-ellipse cubics); every other shape resolves to a generic
 * path and is sampled adaptively. `CircleShape` is a fully rounded rectangle and therefore
 * resolves exactly.
 *
 * The shape is re-resolved (and the plan rebuilt) whenever [from], [to], [width], [height], the
 * layout direction, density, or fill color changes. Pass stable shape instances (`CircleShape`,
 * `RoundedCornerShape(...)`, or a remembered custom shape) so recomposition does not repeat that
 * work.
 *
 * Geometry is drawn in layout pixels: the plan's viewport is the resolved pixel size, and the
 * renderer maps it 1:1. Shapes carry no paint, so [fillFrom] (progress `0`) and [fillTo]
 * (progress `1`) supply a solid fill that interpolates in the configuration's color space.
 *
 * Progress is caller-owned and clamped to `0..1` by the renderer. At `0` [from] draws exactly; at
 * `1` [to] does. A shape whose outline yields no drawable contours produces no geometry for that
 * endpoint, so the planner's existing structural fallback selects a visual transition and reports
 * it in the plan's `report`; inspect [PathGeometry.contours] directly to preflight open/empty
 * outlines.
 */
@Composable
fun MorphShape(
    from: Shape,
    to: Shape,
    progress: Float,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    fillFrom: Color = Color.Black,
    fillTo: Color = fillFrom,
    contentDescription: String? = null,
    width: Dp = DefaultShapeSize,
    height: Dp = DefaultShapeSize,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val fromPaint = remember(fillFrom) { MorphPaint.solid(fillFrom) }
    val toPaint = remember(fillTo) { MorphPaint.solid(fillTo) }
    MorphShape(
        from = from,
        to = to,
        progress = progress,
        paintFrom = fromPaint,
        paintTo = toPaint,
        modifier = modifier,
        configuration = configuration,
        contentDescription = contentDescription,
        width = width,
        height = height,
        alignment = alignment,
        contentScale = contentScale,
    )
}

/**
 * Progress-driven **geometry morph** between two Compose [Shape]s with full endpoint paint.
 *
 * Geometry is resolved exactly as in the color-fill overload: both shapes are outlined at the
 * rendered pixel size, layout direction, and density, then normalized, matched, and interpolated.
 * [paintFrom] applies at progress `0`, [paintTo] at `1`; fills, strokes, alpha, and stroke width
 * interpolate in the configuration's color space and timing. A null fill or a null/zero-width
 * stroke disables that layer.
 *
 * Brushes use the plan's viewport, which for shapes is the resolved layout pixel size. For
 * example, a horizontal 96 px gradient spans `Offset(0f, 0f)` to `Offset(96f, 0f)`.
 * [MorphPaint.strokeWidth] is a dp width and keeps its physical thickness at any layout size.
 *
 * The plan is rebuilt when a shape, size, direction, density, or endpoint paint changes. Pass
 * stable shapes and brushes so recomposition can reuse the cached plan. Empty or unmeasurable
 * outlines still defer to the planner's reported structural fallback.
 */
@Composable
fun MorphShape(
    from: Shape,
    to: Shape,
    progress: Float,
    paintFrom: MorphPaint,
    paintTo: MorphPaint = paintFrom,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    width: Dp = DefaultShapeSize,
    height: Dp = DefaultShapeSize,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val resolvedWidth = width.takeOrElse { DefaultShapeSize }
    val resolvedHeight = height.takeOrElse { DefaultShapeSize }
    val widthPx = with(density) { resolvedWidth.toPx() }
    val heightPx = with(density) { resolvedHeight.toPx() }
    val densityKey = density.density to density.fontScale

    val fromGeometry = remember(from, widthPx, heightPx, layoutDirection, densityKey) {
        PathGeometry.contours(from, Size(widthPx, heightPx), layoutDirection, density)
    }
    val toGeometry = remember(to, widthPx, heightPx, layoutDirection, densityKey) {
        PathGeometry.contours(to, Size(widthPx, heightPx), layoutDirection, density)
    }
    val fromVector = remember(fromGeometry, paintFrom, widthPx, heightPx, densityKey) {
        paintFrom.preparedVector(
            geometry = fromGeometry,
            viewportWidth = widthPx,
            viewportHeight = heightPx,
            layoutWidthPx = widthPx,
            layoutHeightPx = heightPx,
            density = density,
            name = "from",
        )
    }
    val toVector = remember(toGeometry, paintTo, widthPx, heightPx, densityKey) {
        paintTo.preparedVector(
            geometry = toGeometry,
            viewportWidth = widthPx,
            viewportHeight = heightPx,
            layoutWidthPx = widthPx,
            layoutHeightPx = heightPx,
            density = density,
            name = "to",
        )
    }
    val plan = rememberPlan(fromVector, toVector, configuration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = configuration,
        animation = configuration.animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = resolvedWidth,
        height = resolvedHeight,
        tintFrom = null,
        tintTo = null,
        alignment = alignment,
        contentScale = contentScale,
    )
}

/**
 * Progress-driven **geometry morph** between two explicit Compose [Path]s with solid endpoint
 * fills.
 *
 * This convenience overload paints a solid fill. For gradient fills, strokes, or independent
 * fill/stroke styling, use the [MorphPaint] overload.
 *
 * The paths are sampled into normalized cubic contours at [PathGeometry.DEFAULT_TOLERANCE],
 * preserving contour direction, holes, and open/closed state. Unlike [MorphShape], path
 * coordinates are not tied to the layout: [viewportWidth] and [viewportHeight] describe the
 * coordinate space the paths were authored in, and the renderer fits that viewport into the
 * layout while preserving aspect ratio. A `24 x 24` icon-space path therefore morphs at any
 * rendered size.
 *
 * Paths are keyed by identity, matching Compose's `Path` contract. Remember a path instance rather
 * than rebuilding it every frame; rebuilding triggers re-sampling, and mutating a path in place is
 * not observed — rebuild it instead. [pathFillType] applies to both endpoints, and
 * [fillFrom]/[fillTo] are solid endpoint fills interpolated in the configuration's color space.
 * The plan is rebuilt when a path, viewport, fill color, fill type, or configuration changes.
 *
 * Empty or unmeasurable paths contribute no geometry, so the planner's structural fallback selects
 * a visual transition and reports it. Use [PathGeometry.contours] to inspect open contour counts
 * and warnings before composing.
 */
@Composable
fun MorphPath(
    from: Path,
    to: Path,
    progress: Float,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    fillFrom: Color = Color.Black,
    fillTo: Color = fillFrom,
    pathFillType: PathFillType = PathFillType.NonZero,
    contentDescription: String? = null,
    viewportWidth: Float = 24f,
    viewportHeight: Float = 24f,
    width: Dp = DefaultShapeSize,
    height: Dp = DefaultShapeSize,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val fromPaint = remember(fillFrom) { MorphPaint.solid(fillFrom) }
    val toPaint = remember(fillTo) { MorphPaint.solid(fillTo) }
    MorphPath(
        from = from,
        to = to,
        progress = progress,
        paintFrom = fromPaint,
        paintTo = toPaint,
        modifier = modifier,
        configuration = configuration,
        pathFillType = pathFillType,
        contentDescription = contentDescription,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        width = width,
        height = height,
        alignment = alignment,
        contentScale = contentScale,
    )
}

/**
 * Progress-driven **geometry morph** between two explicit Compose [Path]s with full endpoint
 * paint.
 *
 * Paths are sampled into normalized cubic contours at [PathGeometry.DEFAULT_TOLERANCE], keeping
 * contour direction, holes, and open/closed state. Coordinates are in the declared
 * [viewportWidth] x [viewportHeight] space, not layout pixels: the renderer fits that viewport
 * uniformly into the layout, so one authored path morphs cleanly at any size.
 *
 * [paintFrom] applies at progress `0`, [paintTo] at `1`. Brushes use the authored viewport
 * coordinates, and [MorphPaint.strokeWidth] is a dp width converted to those coordinates at the
 * rendered size. A null fill or a null/zero-width stroke disables that layer. Built-in brushes
 * interpolate; incompatible kinds crossfade through the planner's existing paint fallback.
 *
 * Paths are identity-keyed. Mutating a path in place is not observed, so build a new `Path` when
 * its geometry changes. The plan is rebuilt when a path, viewport, fill type, layout size, or
 * endpoint paint changes. Empty paths still defer to the planner's reported structural fallback.
 */
@Composable
fun MorphPath(
    from: Path,
    to: Path,
    progress: Float,
    paintFrom: MorphPaint,
    paintTo: MorphPaint = paintFrom,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    pathFillType: PathFillType = PathFillType.NonZero,
    contentDescription: String? = null,
    viewportWidth: Float = 24f,
    viewportHeight: Float = 24f,
    width: Dp = DefaultShapeSize,
    height: Dp = DefaultShapeSize,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val density = LocalDensity.current
    val resolvedWidth = width.takeOrElse { DefaultShapeSize }
    val resolvedHeight = height.takeOrElse { DefaultShapeSize }
    val widthPx = with(density) { resolvedWidth.toPx() }
    val heightPx = with(density) { resolvedHeight.toPx() }
    val densityKey = density.density to density.fontScale

    val fromGeometry = remember(from) { PathGeometry.contours(from) }
    val toGeometry = remember(to) { PathGeometry.contours(to) }
    val fromVector = remember(
        fromGeometry, paintFrom, viewportWidth, viewportHeight,
        widthPx, heightPx, densityKey, pathFillType,
    ) {
        paintFrom.preparedVector(
            geometry = fromGeometry,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            layoutWidthPx = widthPx,
            layoutHeightPx = heightPx,
            density = density,
            name = "from",
            pathFillType = pathFillType,
        )
    }
    val toVector = remember(
        toGeometry, paintTo, viewportWidth, viewportHeight,
        widthPx, heightPx, densityKey, pathFillType,
    ) {
        paintTo.preparedVector(
            geometry = toGeometry,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            layoutWidthPx = widthPx,
            layoutHeightPx = heightPx,
            density = density,
            name = "to",
            pathFillType = pathFillType,
        )
    }
    val plan = rememberPlan(fromVector, toVector, configuration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = configuration,
        animation = configuration.animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = resolvedWidth,
        height = resolvedHeight,
        tintFrom = null,
        tintTo = null,
        alignment = alignment,
        contentScale = contentScale,
    )
}

/** Uses [block] only when this `Dp` is specified; avoids NaN sizes from [Dp.Unspecified]. */
private inline fun Dp.takeOrElse(block: () -> Dp): Dp =
    if (!value.isNaN()) this else block()
