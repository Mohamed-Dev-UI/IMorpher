package com.imorpher.vectormorph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Fully manual animation driver: the caller owns progress and can seek deterministically. */
@Composable
fun VectorAnimator(
    animation: VectorAnimationDefinition,
    progress: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    width: Dp = animation.vector.defaultWidth,
    height: Dp = animation.vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    VectorAnimatorCore(
        animation = animation,
        progress = progress,
        tintFrom = null,
        tintTo = null,
        alignment = alignment,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/**
 * Fully manual animation driver with a color tint that transitions from [tintFrom] (progress 0)
 * to [tintTo] (progress 1). Interpolation happens in the configuration's color space. The tint
 * replaces every painted pixel while preserving alpha.
 */
@Composable
fun VectorAnimator(
    animation: VectorAnimationDefinition,
    progress: Float,
    tintFrom: Color,
    tintTo: Color = tintFrom,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    width: Dp = animation.vector.defaultWidth,
    height: Dp = animation.vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    VectorAnimatorCore(
        animation = animation,
        progress = progress,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/**
 * Fully manual animation driver with a gradient tint that transitions from [tintFrom]
 * (progress 0) to [tintTo] (progress 1). Built-in brushes of the same kind interpolate
 * smoothly; other combinations crossfade. Brush coordinates resolve against the icon's
 * layout bounds, like `Modifier.background(brush)`.
 */
@Composable
fun VectorAnimator(
    animation: VectorAnimationDefinition,
    progress: Float,
    tintFrom: Brush,
    tintTo: Brush = tintFrom,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    width: Dp = animation.vector.defaultWidth,
    height: Dp = animation.vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    VectorAnimatorCore(
        animation = animation,
        progress = progress,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

// ------------------------------------------------------------------ shared plumbing

/**
 * Untinted manual driver all public overloads funnel into; null tints disable tinting.
 * Named distinctly from the public [VectorAnimator] overloads: with `Any?` tint parameters
 * sharing the name, Kotlin's overload resolution would send the typed overloads' own
 * delegation calls back to themselves and recurse until the stack overflows.
 */
@Composable
private fun VectorAnimatorCore(
    animation: VectorAnimationDefinition,
    progress: Float,
    tintFrom: Any?,
    tintTo: Any?,
    alignment: Alignment,
    modifier: Modifier,
    contentDescription: String?,
    width: Dp,
    height: Dp,
) {
    val configuration = animation.configuration.copy(animation = animation.animation)
    val plan = rememberPlan(animation.vector, animation.vector, configuration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = configuration,
        animation = animation.animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
    )
}
