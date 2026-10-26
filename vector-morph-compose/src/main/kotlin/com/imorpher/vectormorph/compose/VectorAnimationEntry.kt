package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.animation.stableKey
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MotionPreference

/**
 * A reusable single-vector animation definition.
 *
 * @param vector The ImageVector drawn (and re-drawn) at every progress value.
 * @param animation The timeline driving reveals, fills, transforms, and paint.
 * @param configuration Engine configuration for the underlying plan (matching, fallbacks…).
 */
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
    motionPreference: MotionPreference? = null,
): State<Float> {
    val preference = motionPreference ?: rememberMotionPreference()
    val bounded = targetProgress.coerceIn(0f, 1f)
    // Animatable (not animateFloatAsState) so the target change keys the effect and the spec is
    // read through rememberUpdatedState: default `tween(...)` expressions allocate a new instance
    // on every recomposition, and keying an effect on that identity restarts the animation each
    // frame — the animation never settles and onSettled never fires.
    val progress = remember { Animatable(bounded) }
    val state = remember(progress) { derivedStateOf { progress.value } }
    val latestSpec = rememberUpdatedState(
        if (preference == MotionPreference.REDUCED) tween<Float>(durationMillis = 120) else animationSpec,
    )
    LaunchedEffect(bounded, preference) {
        when (preference) {
            MotionPreference.INSTANT -> progress.snapTo(bounded)
            else -> progress.animateTo(bounded, latestSpec.value)
        }
    }
    val instant = rememberUpdatedState(bounded)
    return if (preference == MotionPreference.INSTANT) instant else state
}

/**
 * Variant keyed to a reusable [VectorAnimationDefinition].
 *
 * @deprecated the variant never consults the definition — [animation] is ignored here and the
 * call degrades to the plain 3-argument entry below. Sequenced/defined animations belong to the
 * [com.imorpher.vectormorph.compose.VectorAnimator]-style definition player; hand the definition
 * there instead of through this overload.
 */
@Deprecated(
    "Parameter is ignored by this overload; use the three-argument form and hand the " +
        "definition to a dedicated definition player instead.",
    level = DeprecationLevel.WARNING,
)
@Composable
fun rememberVectorAnimation(
    animation: VectorAnimationDefinition,
    targetProgress: Float = 1f,
    animationSpec: AnimationSpec<Float> = tween(400),
    motionPreference: MotionPreference? = null,
): State<Float> = rememberVectorAnimation(targetProgress, animationSpec, motionPreference)

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
    motionPreference: MotionPreference? = null,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
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
    motionPreference: MotionPreference? = null,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
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
        alignment = alignment,
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
    motionPreference: MotionPreference? = null,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
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
        alignment = alignment,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}

/**
 * Starts one-shot vector animations at zero, then plays them once to 1 for each definition.
 *
 * The returned state follows its own internal `Animatable` when [enabled] and the motion
 * preference allows animation; INSTANT returns a constant 1f and a disabled caller gets a
 * constant 0f. Re-composing with a new [vector]/[animation] restarts the one-shot.
 */
@Composable
private fun rememberAutoPlayProgress(
    vector: ImageVector,
    animation: VectorAnimationSpec,
    animationSpec: AnimationSpec<Float>,
    motionPreference: MotionPreference?,
    enabled: Boolean,
): State<Float> {
    val preference = motionPreference ?: rememberMotionPreference()
    val effectiveSpec = when (preference) {
        MotionPreference.REDUCED -> tween<Float>(durationMillis = 120)
        else -> animationSpec
    }
    // Content key, not identity: inline-DSL one-shot call sites rebuild the animation per
    // recomposition — a VectorAnimation carries a DIFFERENT instance each pass. Keying on
    // identity re-allocates the Animatable and re-keys the effect, restarting the timeline
    // forever on such call sites. `stableKey` folds the timeline content instead, so
    // content-identical rebuilds share the key; a whole-tween diff (spec change etc.)
    // changes it and restarts legitimately. The default `tween(...)` spec allocation is
    // read through rememberUpdatedState to give it the same tolerance.
    val animationKey = animation.stableKey()
    val progress = remember(vector, animationKey) { Animatable(0f) }
    val fallback = rememberUpdatedState(if (preference == MotionPreference.INSTANT) 1f else 0f)
    val animatedProgress = remember(progress) { derivedStateOf { progress.value } }
    val latestSpec = rememberUpdatedState(effectiveSpec)
    LaunchedEffect(vector, animationKey, preference, enabled) {
        if (!enabled || preference == MotionPreference.INSTANT) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, latestSpec.value)
    }
    return if (enabled && preference != MotionPreference.INSTANT) animatedProgress else fallback
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
    motionPreference: MotionPreference? = null,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
) = VectorAnimation(
    vector = vector,
    animation = animation,
    modifier = modifier,
    animationSpec = animationSpec,
    configuration = configuration,
    contentDescription = contentDescription,
    motionPreference = motionPreference,
    width = width,
    height = height,
    alignment = alignment,
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
    motionPreference: MotionPreference? = null,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
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
    height = height,
    alignment = alignment,
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
    motionPreference: MotionPreference? = null,
    width: Dp = vector.defaultWidth,
    height: Dp = vector.defaultHeight,
    alignment: Alignment = Alignment.Center,
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
    height = height,
    alignment = alignment,
)
