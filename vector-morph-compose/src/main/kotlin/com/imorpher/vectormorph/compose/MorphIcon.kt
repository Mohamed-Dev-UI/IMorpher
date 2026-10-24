package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MotionPreference
import kotlinx.coroutines.flow.first

/**
 * High-level selected/unselected icon. The animation remains interruptible when [selected]
 * changes while a previous transition is still running.
 */
@Composable
fun MorphIcon(
    from: ImageVector,
    to: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    animation: VectorAnimationSpec? = configuration.animation,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    onSettled: ((Boolean) -> Unit)? = null,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val effectiveConfiguration = configuration.copy(animation = animation)
    val resolvedMotion = motionPreference ?: rememberMotionPreference()
    val progressState = rememberVectorAnimation(
        targetProgress = if (selected) 1f else 0f,
        animationSpec = animationSpec,
        motionPreference = resolvedMotion,
    )
    val progress by progressState
    observeSettled(selected, progressState, onSettled)
    val renderConfiguration = if (resolvedMotion == MotionPreference.CROSSFADE_ONLY) {
        effectiveConfiguration.copy(fallback = FallbackStrategy.CROSSFADE)
    } else effectiveConfiguration
    val plan = rememberPlan(from, to, renderConfiguration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = renderConfiguration,
        animation = animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = null,
        tintTo = null,
        alignment = alignment,
    )
}

/**
 * High-level selected/unselected icon whose tint transitions from [tintFrom] (unselected) to
 * [tintTo] (selected). Interpolation happens in the configuration's color space. The animation
 * remains interruptible when [selected] changes while a previous transition is still running.
 *
 * The tint replaces every painted pixel — fills, strokes, and animated gradients — while
 * preserving each pixel's alpha, so fades, stroke reveals, and antialiased edges stay intact.
 */
@Composable
fun MorphIcon(
    from: ImageVector,
    to: ImageVector,
    selected: Boolean,
    tintFrom: Color,
    tintTo: Color = tintFrom,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    animation: VectorAnimationSpec? = configuration.animation,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    onSettled: ((Boolean) -> Unit)? = null,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val effectiveConfiguration = configuration.copy(animation = animation)
    val resolvedMotion = motionPreference ?: rememberMotionPreference()
    val progressState = rememberVectorAnimation(
        targetProgress = if (selected) 1f else 0f,
        animationSpec = animationSpec,
        motionPreference = resolvedMotion,
    )
    val progress by progressState
    observeSettled(selected, progressState, onSettled)
    val renderConfiguration = if (resolvedMotion == MotionPreference.CROSSFADE_ONLY) {
        effectiveConfiguration.copy(fallback = FallbackStrategy.CROSSFADE)
    } else effectiveConfiguration
    val plan = rememberPlan(from, to, renderConfiguration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = renderConfiguration,
        animation = animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
    )
}

/**
 * High-level selected/unselected icon whose gradient tint transitions from [tintFrom]
 * (unselected) to [tintTo] (selected). Built-in linear, radial, and sweep brushes of the same
 * kind interpolate smoothly; other combinations crossfade. The animation remains interruptible
 * when [selected] changes while a previous transition is still running.
 *
 * The tint replaces every painted pixel — fills, strokes, and animated gradients — while
 * preserving each pixel's alpha, so fades, stroke reveals, and antialiased edges stay intact.
 * Brush coordinates resolve against the icon's layout bounds, like `Modifier.background(brush)`.
 */
@Composable
fun MorphIcon(
    from: ImageVector,
    to: ImageVector,
    selected: Boolean,
    tintFrom: Brush,
    tintTo: Brush = tintFrom,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    animation: VectorAnimationSpec? = configuration.animation,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    onSettled: ((Boolean) -> Unit)? = null,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val effectiveConfiguration = configuration.copy(animation = animation)
    val resolvedMotion = motionPreference ?: rememberMotionPreference()
    val progressState = rememberVectorAnimation(
        targetProgress = if (selected) 1f else 0f,
        animationSpec = animationSpec,
        motionPreference = resolvedMotion,
    )
    val progress by progressState
    observeSettled(selected, progressState, onSettled)
    val renderConfiguration = if (resolvedMotion == MotionPreference.CROSSFADE_ONLY) {
        effectiveConfiguration.copy(fallback = FallbackStrategy.CROSSFADE)
    } else effectiveConfiguration
    val plan = rememberPlan(from, to, renderConfiguration)
    VectorPlanCanvas(
        plan = plan,
        progress = progress,
        configuration = renderConfiguration,
        animation = animation,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
        tintFrom = tintFrom,
        tintTo = tintTo,
        alignment = alignment,
    )
}

/**
 * Fires [onSettled] once each time [progress] comes to rest at the current selection.
 *
 * A selection change restarts the effect, so an interrupted transition never reports a settled
 * state it did not reach; the callback runs exactly once per completed transition.
 */
@Composable
private fun observeSettled(
    selected: Boolean,
    progress: State<Float>,
    onSettled: ((Boolean) -> Unit)?,
) {
    val callback = rememberUpdatedState(onSettled)
    val target = if (selected) 1f else 0f
    // `animationSpec` and `motionPreference` are intentionally not keys: callers commonly pass a
    // fresh `tween(...)` instance per composition, which would restart the observer every frame.
    LaunchedEffect(selected) {
        snapshotFlow { progress.value }.first { it == target }
        callback.value?.invoke(selected)
    }
}
