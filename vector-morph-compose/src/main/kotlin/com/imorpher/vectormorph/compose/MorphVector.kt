package com.imorpher.vectormorph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.imorpher.vectormorph.core.model.MorphConfiguration

// ------------------------------------------------------------------ morphs, untinted

/**
 * Morph between two Compose [ImageVector]s at a caller-owned progress value.
 * Progress 0 draws [from], progress 1 draws [to].
 */
@Composable
fun MorphVector(
    from: ImageVector,
    to: ImageVector,
    progress: Float,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val plan = rememberPlan(from, to, configuration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = configuration,
        animation = configuration.animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = null,
        tintTo = null,
        alignment = alignment,
    )
}

// ------------------------------------------------------------------ color tints

/**
 * Morph between two Compose [ImageVector]s at a caller-owned progress value, tinting the icon
 * with a color that transitions from [tintFrom] (drawn at progress 0) to [tintTo] (drawn at
 * progress 1). Interpolation happens in the configuration's color space.
 *
 * The tint replaces every painted pixel — fills, strokes, and animated gradients — while
 * preserving each pixel's alpha, so fades, stroke reveals, and antialiased edges stay intact.
 * This is the standard way to re-color icons that ship with their own paints.
 */
@Composable
fun MorphVector(
    from: ImageVector,
    to: ImageVector,
    progress: Float,
    tintFrom: Color,
    tintTo: Color = tintFrom,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val plan = rememberPlan(from, to, configuration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = configuration,
        animation = configuration.animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
    )
}

// ------------------------------------------------------------------ gradient/brush tints

/**
 * Morph between two Compose [ImageVector]s at a caller-owned progress value, tinting the icon
 * with a gradient that transitions from [tintFrom] to [tintTo] across the morph. Built-in
 * linear, radial, and sweep brushes of the same kind interpolate smoothly (geometry, colors,
 * and stops); other combinations crossfade. When one endpoint is null, the other fades in
 * from (or out to) transparency.
 *
 * The tint replaces every painted pixel — fills, strokes, and animated gradients — while
 * preserving each pixel's alpha, so fades, stroke reveals, and antialiased edges stay intact.
 * Brush coordinates resolve against the icon's layout bounds, like `Modifier.background(brush)`.
 */
@Composable
fun MorphVector(
    from: ImageVector,
    to: ImageVector,
    progress: Float,
    tintFrom: Brush,
    tintTo: Brush = tintFrom,
    modifier: Modifier = Modifier,
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val plan = rememberPlan(from, to, configuration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = configuration,
        animation = configuration.animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
    )
}
