package com.imorpher.vectormorph.compose

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.geometry.PathGeometry
import com.imorpher.vectormorph.core.geometry.PathGeometryResult
import com.imorpher.vectormorph.core.model.PreparedVector
import kotlin.math.min

/**
 * Paint for one endpoint of [MorphShape] or [MorphPath].
 *
 * A fill and a stroke can be configured independently. Both endpoint paints are compiled into
 * the same prepared-vector pipeline as `ImageVector` paths, so built-in brushes interpolate
 * through the configuration's color space; a null fill disables filling, and a null stroke or a
 * zero [strokeWidth] disables stroking.
 *
 * Brush coordinates are expressed in the endpoint's **viewport**, matching the geometry:
 * layout pixels for [MorphShape] and authored path units for [MorphPath]. [strokeWidth] is a
 * layout `Dp` width, converted to viewport units at the current size, so a stroke keeps the same
 * physical thickness as the component resizes.
 *
 * Built-in `SolidColor`, linear, radial, and sweep brushes interpolate. A solid endpoint is
 * promoted to the other endpoint's gradient kind where possible; incompatible gradient kinds
 * and custom `Brush` subclasses crossfade, exactly as they do for icons.
 *
 * @property fill brush painted behind the endpoint geometry; null leaves it unfilled.
 * @property fillAlpha additional fill opacity, interpolated between endpoints.
 * @property stroke brush painted along the endpoint outline; null disables the stroke.
 * @property strokeAlpha additional stroke opacity, interpolated between endpoints.
 * @property strokeWidth physical stroke width; `0.dp` disables the stroke.
 * @property strokeLineCap cap style used while rendering the stroke.
 * @property strokeLineJoin join style used while rendering the stroke.
 * @property strokeLineMiter miter limit used while rendering the stroke.
 */
data class MorphPaint(
    val fill: Brush? = SolidColor(Color.Black),
    val fillAlpha: Float = 1f,
    val stroke: Brush? = null,
    val strokeAlpha: Float = 1f,
    val strokeWidth: Dp = 0.dp,
    val strokeLineCap: StrokeCap = StrokeCap.Butt,
    val strokeLineJoin: StrokeJoin = StrokeJoin.Miter,
    val strokeLineMiter: Float = 4f,
) {
    companion object {
        /** A solid fill with no stroke; the common one-line endpoint paint. */
        fun solid(color: Color): MorphPaint = MorphPaint(fill = SolidColor(color))
    }
}

/**
 * Compiles [geometry] with this paint. [layoutWidthPx]/[layoutHeightPx] are the rendered canvas
 * dimensions used to translate [MorphPaint.strokeWidth] from dp into viewport units.
 */
internal fun MorphPaint.preparedVector(
    geometry: PathGeometryResult,
    viewportWidth: Float,
    viewportHeight: Float,
    layoutWidthPx: Float,
    layoutHeightPx: Float,
    density: Density,
    name: String,
    pathFillType: PathFillType = PathFillType.NonZero,
): PreparedVector {
    val vw = viewportWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
    val vh = viewportHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
    return PathGeometry.preparedVector(
        geometry = geometry,
        viewportWidth = vw,
        viewportHeight = vh,
        name = name,
        fill = fill,
        fillAlpha = fillAlpha,
        stroke = stroke,
        strokeAlpha = strokeAlpha,
        strokeWidth = strokeWidthInViewportUnits(vw, vh, layoutWidthPx, layoutHeightPx, density),
        strokeLineCap = strokeLineCap,
        strokeLineJoin = strokeLineJoin,
        strokeLineMiter = strokeLineMiter,
        pathFillType = pathFillType,
    )
}

/**
 * Converts the dp width to viewport units. The renderer fits the viewport uniformly into the
 * layout, so dividing the pixel width by that uniform scale preserves the requested physical
 * thickness at every rendered size.
 */
private fun MorphPaint.strokeWidthInViewportUnits(
    viewportWidth: Float,
    viewportHeight: Float,
    layoutWidthPx: Float,
    layoutHeightPx: Float,
    density: Density,
): Float {
    if (stroke == null) return 0f
    if (!strokeWidth.value.isFinite() || strokeWidth.value <= 0f) return 0f
    val widthPx = with(density) { strokeWidth.toPx() }
    if (!widthPx.isFinite() || widthPx <= 0f) return 0f
    val uniform = min(layoutWidthPx / viewportWidth, layoutHeightPx / viewportHeight)
    return if (uniform.isFinite() && uniform > 0f) widthPx / uniform else widthPx
}
