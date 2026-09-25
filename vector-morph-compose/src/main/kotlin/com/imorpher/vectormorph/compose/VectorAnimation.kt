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
    )
}

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
) = VectorAnimation(
    vector = vector,
    animation = animation,
    modifier = modifier,
    animationSpec = animationSpec,
    configuration = configuration,
    contentDescription = contentDescription,
    motionPreference = motionPreference,
)

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
        renderer.render(this, plan, frame, progress, configuration)
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
