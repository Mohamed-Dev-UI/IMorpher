package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.MorphPlanCache
import com.imorpher.vectormorph.core.plan.MorphPlanner
import com.imorpher.vectormorph.compose.renderer.MorphRenderer
import com.imorpher.vectormorph.core.model.FallbackStrategy
import java.util.WeakHashMap

/** A reusable single-vector animation definition. */
data class VectorAnimationDefinition(
    val vector: ImageVector,
    val animation: VectorAnimationSpec,
    val configuration: MorphConfiguration = MorphConfiguration.Default,
)

/**
 * Animates a normalized progress value. Compose retargets from the currently displayed value,
 * so rapid state changes reverse or redirect smoothly without resetting to either endpoint.
 */
@Composable
fun rememberVectorAnimation(
    targetProgress: Float = 1f,
    animationSpec: AnimationSpec<Float> = tween(400),
    motionPreference: MotionPreference = MotionPreference.FULL,
): State<Float> {
    val bounded = targetProgress.coerceIn(0f, 1f)
    val spec = when (motionPreference) {
        MotionPreference.REDUCED -> tween<Float>(durationMillis = 120)
        else -> animationSpec
    }
    val animated = animateFloatAsState(
        targetValue = bounded,
        animationSpec = spec,
        label = "vectorAnimationProgress",
    )
    val instant = rememberUpdatedState(bounded)
    return if (motionPreference == MotionPreference.INSTANT) instant else animated
}

/** Variant keyed to a reusable [VectorAnimationDefinition]. */
@Composable
fun rememberVectorAnimation(
    animation: VectorAnimationDefinition,
    targetProgress: Float = 1f,
    animationSpec: AnimationSpec<Float> = tween(400),
    motionPreference: MotionPreference = MotionPreference.FULL,
): State<Float> = rememberVectorAnimation(targetProgress, animationSpec, motionPreference)

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
    )
}

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
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
) {
    val effectiveConfiguration = configuration.copy(animation = animation)
    val progress by rememberVectorAnimation(
        targetProgress = if (selected) 1f else 0f,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    val renderConfiguration = if (motionPreference == MotionPreference.CROSSFADE_ONLY) {
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
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
) {
    val effectiveConfiguration = configuration.copy(animation = animation)
    val progress by rememberVectorAnimation(
        targetProgress = if (selected) 1f else 0f,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    val renderConfiguration = if (motionPreference == MotionPreference.CROSSFADE_ONLY) {
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
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = to.defaultWidth,
    height: Dp = to.defaultHeight,
) {
    val effectiveConfiguration = configuration.copy(animation = animation)
    val progress by rememberVectorAnimation(
        targetProgress = if (selected) 1f else 0f,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    val renderConfiguration = if (motionPreference == MotionPreference.CROSSFADE_ONLY) {
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
    )
}

// ------------------------------------------------------------------ single-vector animations

/** Plays a single vector animation automatically from 0 to 1, or follows [progress] if set. */
@Composable
fun VectorAnimation(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    animationSpec: AnimationSpec<Float> = tween(400),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
) {
    val automaticProgress by rememberAutoPlayProgress(
        vector = vector,
        animation = animation,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
        enabled = progress == null,
    )
    VectorAnimator(
        animation = VectorAnimationDefinition(vector, animation, configuration),
        progress = progress ?: automaticProgress,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/**
 * Plays a single vector animation while its tint transitions from [tintFrom] (progress 0) to
 * [tintTo] (progress 1). Interpolation happens in the configuration's color space. The tint
 * replaces every painted pixel while preserving alpha, so reveals and fades stay intact.
 */
@Composable
fun VectorAnimation(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    tintFrom: Color,
    tintTo: Color = tintFrom,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    animationSpec: AnimationSpec<Float> = tween(400),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
) {
    val automaticProgress by rememberAutoPlayProgress(
        vector = vector,
        animation = animation,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
        enabled = progress == null,
    )
    VectorAnimator(
        animation = VectorAnimationDefinition(vector, animation, configuration),
        progress = progress ?: automaticProgress,
        tintFrom = tintFrom,
        tintTo = tintTo,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/**
 * Plays a single vector animation while its gradient tint transitions from [tintFrom]
 * (progress 0) to [tintTo] (progress 1). Built-in brushes of the same kind interpolate
 * smoothly; other combinations crossfade. The tint replaces every painted pixel while
 * preserving alpha, so reveals and fades stay intact. Brush coordinates resolve against the
 * icon's layout bounds, like `Modifier.background(brush)`.
 */
@Composable
fun VectorAnimation(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    tintFrom: Brush,
    tintTo: Brush = tintFrom,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    animationSpec: AnimationSpec<Float> = tween(400),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
) {
    val automaticProgress by rememberAutoPlayProgress(
        vector = vector,
        animation = animation,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
        enabled = progress == null,
    )
    VectorAnimator(
        animation = VectorAnimationDefinition(vector, animation, configuration),
        progress = progress ?: automaticProgress,
        tintFrom = tintFrom,
        tintTo = tintTo,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/** Starts one-shot vector animations at zero, then plays them once for each definition. */
@Composable
private fun rememberAutoPlayProgress(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    animationSpec: AnimationSpec<Float>,
    motionPreference: MotionPreference,
    enabled: Boolean,
): State<Float> {
    val effectiveSpec = when (motionPreference) {
        MotionPreference.REDUCED -> tween<Float>(durationMillis = 120)
        else -> animationSpec
    }
    val progress = remember(vector, animation) { Animatable(0f) }
    val fallback = rememberUpdatedState(if (motionPreference == MotionPreference.INSTANT) 1f else 0f)
    val animatedProgress = remember(progress) { derivedStateOf { progress.value } }
    LaunchedEffect(vector, animation, animationSpec, motionPreference, enabled) {
        if (!enabled || motionPreference == MotionPreference.INSTANT) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, effectiveSpec)
    }
    return if (enabled && motionPreference != MotionPreference.INSTANT) animatedProgress else fallback
}

/** Fully manual animation driver: the caller owns progress and can seek deterministically. */
@Composable
fun VectorAnimator(
    animation: VectorAnimationDefinition,
    progress: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    width: Dp = animation.vector.defaultWidth,
    height: Dp = animation.vector.defaultHeight,
) {
    VectorAnimatorCore(
        animation = animation,
        progress = progress,
        tintFrom = null,
        tintTo = null,
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
) {
    VectorAnimatorCore(
        animation = animation,
        progress = progress,
        tintFrom = tintFrom,
        tintTo = tintTo,
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
) {
    VectorAnimatorCore(
        animation = animation,
        progress = progress,
        tintFrom = tintFrom,
        tintTo = tintTo,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/** Convenience drawing entry point for a vector reveal animation. */
@Composable
fun DrawIcon(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(400),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
) = VectorAnimation(
    vector = vector,
    animation = animation,
    modifier = modifier,
    animationSpec = animationSpec,
    configuration = configuration,
    contentDescription = contentDescription,
    motionPreference = motionPreference,
    width = width,
    height = height
)

/** Convenience drawing entry point with a color tint that transitions from [tintFrom] to [tintTo]. */
@Composable
fun DrawIcon(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    tintFrom: Color,
    tintTo: Color = tintFrom,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(400),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
) = VectorAnimation(
    vector = vector,
    animation = animation,
    tintFrom = tintFrom,
    tintTo = tintTo,
    modifier = modifier,
    animationSpec = animationSpec,
    configuration = configuration,
    contentDescription = contentDescription,
    motionPreference = motionPreference,
    width = width,
    height = height
)

/** Convenience drawing entry point with a gradient tint that transitions from [tintFrom] to [tintTo]. */
@Composable
fun DrawIcon(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    tintFrom: Brush,
    tintTo: Brush = tintFrom,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(400),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference = MotionPreference.FULL,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
) = VectorAnimation(
    vector = vector,
    animation = animation,
    tintFrom = tintFrom,
    tintTo = tintTo,
    modifier = modifier,
    animationSpec = animationSpec,
    configuration = configuration,
    contentDescription = contentDescription,
    motionPreference = motionPreference,
    width = width,
    height = height
)

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
    )
}

@Composable
private fun VectorPlanCanvas(
    plan: MorphPlan,
    progress: Float,
    configuration: MorphConfiguration,
    animation: VectorAnimationSpec?,
    modifier: Modifier,
    contentDescription: String?,
    width: Dp,
    height: Dp,
    tintFrom: Any?,
    tintTo: Any?,
) {
    val planningAnimation = animation ?: configuration.animation
    val evaluator = remember(plan, planningAnimation, configuration.colorSpace, configuration.staggerDelay, configuration.staggerOrder) {
        planningAnimation?.let {
            TimelineEvaluator(
                animation = it,
                plan = plan,
                colorSpace = configuration.colorSpace,
                staggerDelay = if (configuration.staggerDelay > 0f) configuration.staggerDelay else it.staggerDelay,
                staggerOrder = if (configuration.staggerDelay > 0f) configuration.staggerOrder else it.staggerOrder,
                customDrawOrder = configuration.customDrawOrder,
                timingMode = configuration.timing,
            )
        }
    }
    val frame = remember(plan) { EvaluatedFrame(plan) }
    val renderer = remember { MorphRenderer() }
    val accessibleModifier = if (contentDescription == null) modifier else modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(modifier = accessibleModifier.size(width, height)) {
        evaluator?.evaluate(progress.coerceIn(0f, 1f), frame)
        renderer.render(this, plan, frame, progress, configuration, tintFrom, tintTo)
    }
}

private val preparedVectors = WeakHashMap<ImageVector, PreparedVector>()
private val planCache = MorphPlanCache(maxEntries = 64)

@Composable
private fun rememberPlan(
    from: ImageVector,
    to: ImageVector,
    configuration: MorphConfiguration,
): MorphPlan {
    val planningConfiguration = configuration.copy(animation = null)
    return remember(from, to, planningConfiguration) {
        val preparedFrom = prepared(from)
        val preparedTo = prepared(to)
        planCache.getOrPut(preparedFrom, preparedTo, planningConfiguration) {
            MorphPlanner.plan(preparedFrom, preparedTo, planningConfiguration)
        }
    }
}

@Synchronized
private fun prepared(vector: ImageVector): PreparedVector =
    preparedVectors.getOrPut(vector) { VectorCompiler.compile(vector) }
