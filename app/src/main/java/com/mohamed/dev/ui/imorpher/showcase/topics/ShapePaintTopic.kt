package com.mohamed.dev.ui.imorpher.showcase.topics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.MorphPaint
import com.imorpher.vectormorph.compose.MorphPath
import com.imorpher.vectormorph.compose.MorphShape
import com.mohamed.dev.ui.imorpher.showcase.ApiSignature
import com.mohamed.dev.ui.imorpher.showcase.ColorSwatchRow
import com.mohamed.dev.ui.imorpher.showcase.LabeledSlider
import com.mohamed.dev.ui.imorpher.showcase.LabeledSwitch
import com.mohamed.dev.ui.imorpher.showcase.PreviewTile
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCard
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseCardHeader
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseColors
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseDropdown
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseSlider
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which geometry adapter the paint playground is driving. */
private enum class PaintSubject(val label: String) {
    SHAPE("Compose Shape"),
    PATH("Compose Path"),
}

/** Brush family used for the fill of both endpoints. */
private enum class PaintFill(val label: String) {
    LINEAR("Linear"),
    RADIAL("Radial"),
    SWEEP("Sweep"),
    SOLID("Solid"),
}

/** Stroke caps exposed without leaking Compose's value-class constants into state. */
private enum class PaintCap(val label: String) {
    BUTT("Butt"),
    ROUND("Round"),
    SQUARE("Square");

    fun toCompose(): StrokeCap = when (this) {
        BUTT -> StrokeCap.Butt
        ROUND -> StrokeCap.Round
        SQUARE -> StrokeCap.Square
    }
}

/** Stroke joins exposed without leaking Compose's value-class constants into state. */
private enum class PaintJoin(val label: String) {
    MITER("Miter"),
    ROUND("Round"),
    BEVEL("Bevel");

    fun toCompose(): StrokeJoin = when (this) {
        MITER -> StrokeJoin.Miter
        ROUND -> StrokeJoin.Round
        BEVEL -> StrokeJoin.Bevel
    }
}

/** Stable Compose Shape endpoints: a cut-corner octagon morphs into a true circle. */
private val ShapeFrom = CutCornerShape(percent = 18)
private val ShapeTo = CircleShape

/** Authored path viewport; both demo paths live in this coordinate space. */
private const val PathViewport = 100f

/**
 * The shape/path paint playground.
 *
 * Geometry is deliberately stable (`remember`ed paths and top-level shapes): the endpoint
 * paths are sampled once, and moving a paint slider only rebuilds the paint plan. The brush
 * viewport is the rendered size for shapes and [PathViewport] square for paths, matching the
 * two adapters' documented coordinate contracts.
 */
@Composable
fun ShapePaintPlayground() {
    var subject by remember { mutableStateOf(PaintSubject.SHAPE) }
    var progress by remember { mutableFloatStateOf(0.45f) }
    var sizeDp by remember { mutableFloatStateOf(132f) }

    var fillStyle by remember { mutableStateOf(PaintFill.LINEAR) }
    var fillEnabled by remember { mutableStateOf(true) }
    var fillAlpha by remember { mutableFloatStateOf(1f) }
    var fromFillStart by remember { mutableStateOf(ShowcaseColors.indigo) }
    var fromFillEnd by remember { mutableStateOf(ShowcaseColors.cyan) }
    var toFillStart by remember { mutableStateOf(ShowcaseColors.cyan) }
    var toFillEnd by remember { mutableStateOf(ShowcaseColors.amber) }

    var strokeEnabled by remember { mutableStateOf(true) }
    var strokeAlpha by remember { mutableFloatStateOf(1f) }
    var strokeWidth by remember { mutableFloatStateOf(3f) }
    var strokeFrom by remember { mutableStateOf(ShowcaseColors.amber) }
    var strokeTo by remember { mutableStateOf(ShowcaseColors.coral) }
    var strokeCap by remember { mutableStateOf(PaintCap.ROUND) }
    var strokeJoin by remember { mutableStateOf(PaintJoin.ROUND) }

    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.dp.toPx() }
    val brushViewport = if (subject == PaintSubject.SHAPE) sizePx else PathViewport

    val fromFill = remember(fillStyle, fromFillStart, fromFillEnd, brushViewport) {
        fillBrush(fillStyle, fromFillStart, fromFillEnd, brushViewport)
    }
    val toFill = remember(fillStyle, toFillStart, toFillEnd, brushViewport) {
        fillBrush(fillStyle, toFillStart, toFillEnd, brushViewport)
    }
    val paintFrom = remember(
        fillEnabled, fillAlpha, fromFill,
        strokeEnabled, strokeAlpha, strokeWidth, strokeFrom, strokeCap, strokeJoin,
    ) {
        MorphPaint(
            fill = if (fillEnabled) fromFill else null,
            fillAlpha = fillAlpha,
            stroke = if (strokeEnabled) SolidColor(strokeFrom) else null,
            strokeAlpha = strokeAlpha,
            strokeWidth = if (strokeEnabled) strokeWidth.dp else 0.dp,
            strokeLineCap = strokeCap.toCompose(),
            strokeLineJoin = strokeJoin.toCompose(),
        )
    }
    val paintTo = remember(
        fillEnabled, fillAlpha, toFill,
        strokeEnabled, strokeAlpha, strokeWidth, strokeTo, strokeCap, strokeJoin,
    ) {
        MorphPaint(
            fill = if (fillEnabled) toFill else null,
            fillAlpha = fillAlpha,
            stroke = if (strokeEnabled) SolidColor(strokeTo) else null,
            strokeAlpha = strokeAlpha,
            strokeWidth = if (strokeEnabled) strokeWidth.dp else 0.dp,
            strokeLineCap = strokeCap.toCompose(),
            strokeLineJoin = strokeJoin.toCompose(),
        )
    }

    val star = remember { buildStarPath() }
    val blob = remember { buildBlobPath() }

    ShowcaseCard {
        ShowcaseCardHeader(
            number = "GP",
            title = "Shape & path paint playground",
            description = "Morph a Compose Shape or authored Path with independent endpoint gradients and strokes.",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            PreviewTile(size = 172) {
                when (subject) {
                    PaintSubject.SHAPE -> MorphShape(
                        from = ShapeFrom,
                        to = ShapeTo,
                        progress = progress,
                        paintFrom = paintFrom,
                        paintTo = paintTo,
                        width = sizeDp.dp,
                        height = sizeDp.dp,
                        contentDescription = "Shape paint morph",
                    )

                    PaintSubject.PATH -> MorphPath(
                        from = star,
                        to = blob,
                        progress = progress,
                        paintFrom = paintFrom,
                        paintTo = paintTo,
                        viewportWidth = PathViewport,
                        viewportHeight = PathViewport,
                        width = sizeDp.dp,
                        height = sizeDp.dp,
                        contentDescription = "Path paint morph",
                    )
                }
            }
        }

        ShowcaseDropdown(
            label = "Geometry",
            options = PaintSubject.entries.toList(),
            selected = subject,
            optionLabel = { it.label },
            onSelect = { subject = it },
        )
        LabeledSlider(
            label = "Progress",
            value = progress,
            range = 0f..1f,
            suffix = "%",
            onValueChange = { progress = (it * 100f).roundToInt() / 100f },
        )
        LabeledSwitch("Fill", checked = fillEnabled, onCheckedChange = { fillEnabled = it })
        if (fillEnabled) {
            ShowcaseDropdown(
                label = "Fill",
                options = PaintFill.entries.toList(),
                selected = fillStyle,
                optionLabel = { it.label },
                onSelect = { fillStyle = it },
            )
            PaintSectionTitle("From fill")
            ColorSwatchRow("Start", ShowcaseColors.swatches, fromFillStart) { fromFillStart = it }
            if (fillStyle != PaintFill.SOLID) {
                ColorSwatchRow("End", ShowcaseColors.swatches, fromFillEnd) { fromFillEnd = it }
            }

            PaintSectionTitle("To fill")
            ColorSwatchRow("Start", ShowcaseColors.swatches, toFillStart) { toFillStart = it }
            if (fillStyle != PaintFill.SOLID) {
                ColorSwatchRow("End", ShowcaseColors.swatches, toFillEnd) { toFillEnd = it }
            }

            ShowcaseSlider(
                label = "Fill opacity",
                value = fillAlpha,
                onValueChange = { fillAlpha = it },
                format = { "${(it * 100).roundToInt()}%" },
            )
        }

        LabeledSwitch("Stroke", checked = strokeEnabled, onCheckedChange = { strokeEnabled = it })
        if (strokeEnabled) {
            PaintSectionTitle("Stroke paint")
            ColorSwatchRow("From", ShowcaseColors.swatches, strokeFrom) { strokeFrom = it }
            ColorSwatchRow("To", ShowcaseColors.swatches, strokeTo) { strokeTo = it }
            ShowcaseSlider(
                label = "Stroke width",
                value = strokeWidth,
                onValueChange = { strokeWidth = it },
                valueRange = 1f..12f,
                format = { "${it.roundToInt()} dp" },
            )
            ShowcaseDropdown(
                label = "Cap",
                options = PaintCap.entries.toList(),
                selected = strokeCap,
                optionLabel = { it.label },
                onSelect = { strokeCap = it },
            )
            ShowcaseDropdown(
                label = "Join",
                options = PaintJoin.entries.toList(),
                selected = strokeJoin,
                optionLabel = { it.label },
                onSelect = { strokeJoin = it },
            )
            ShowcaseSlider(
                label = "Stroke opacity",
                value = strokeAlpha,
                onValueChange = { strokeAlpha = it },
                format = { "${(it * 100).roundToInt()}%" },
            )
        }

        ShowcaseSlider(
            label = "Size",
            value = sizeDp,
            onValueChange = { sizeDp = it },
            valueRange = 96f..168f,
            format = { "${it.roundToInt()} dp" },
        )
        Text(
            "Shape brushes are in layout pixels; path brushes use the 100 × 100 authored viewport. " +
                "Stroke width stays physical in both modes.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ApiSignature(
            "MorphShape/MorphPath(…, paintFrom = MorphPaint(fill = Brush…, stroke = SolidColor(…)))"
        )
    }
}

@Composable
private fun PaintSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Builds a fill brush in the current endpoint viewport. Explicit bounds keep the same brush
 * geometry in both preview modes instead of relying on size-dependent infinite defaults.
 */
private fun fillBrush(
    style: PaintFill,
    start: Color,
    end: Color,
    viewport: Float,
): Brush {
    val colors = listOf(start, end)
    return when (style) {
        PaintFill.LINEAR -> Brush.linearGradient(
            colors = colors,
            start = Offset.Zero,
            end = Offset(viewport, viewport),
        )

        PaintFill.RADIAL -> Brush.radialGradient(
            colors = colors,
            center = Offset(viewport / 2f, viewport / 2f),
            radius = viewport * 0.62f,
        )

        PaintFill.SWEEP -> Brush.sweepGradient(
            colors = colors,
            center = Offset(viewport / 2f, viewport / 2f),
        )

        PaintFill.SOLID -> SolidColor(start)
    }
}

/** Five-point star in the 100 × 100 authored path viewport. */
private fun buildStarPath(): Path = Path().apply {
    val outer = 46f
    val inner = 20f
    repeat(10) { index ->
        val angle = Math.toRadians(-90.0 + index * 36.0)
        val radius = if (index % 2 == 0) outer else inner
        val x = 50f + (cos(angle) * radius).toFloat()
        val y = 50f + (sin(angle) * radius).toFloat()
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A soft squircle blob in the same 100 × 100 viewport, as a four-cubic counterpoint. */
private fun buildBlobPath(): Path = Path().apply {
    moveTo(50f, 7f)
    cubicTo(74f, 7f, 93f, 26f, 93f, 50f)
    cubicTo(93f, 74f, 74f, 93f, 50f, 93f)
    cubicTo(26f, 93f, 7f, 74f, 7f, 50f)
    cubicTo(7f, 26f, 26f, 7f, 50f, 7f)
    close()
}
