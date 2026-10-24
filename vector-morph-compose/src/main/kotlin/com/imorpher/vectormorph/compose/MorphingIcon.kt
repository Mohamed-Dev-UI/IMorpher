package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MotionPreference

/**
 * An icon that morphs to whatever [target] is currently set, from whatever it last settled on.
 *
 * Contract (important for chained/state-driven icons):
 *  - The first composition draws [target] immediately at progress 1 — no intro animation.
 *  - When [target] changes, the icon morphs from the **last settled** icon to the new target.
 *  - If [target] changes again mid-flight, the in-progress morph is abandoned and the new morph
 *    restarts from that same last settled icon (never from the mid-animation frame).
 *  - Reduced/instant motion preferences collapse or shorten the transition; instant renders the
 *    new target directly.
 *
 * Use [MorphPrecompiler.warm] for icon pairs that must animate on first display.
 */
@Composable
fun MorphingIcon(
    target: ImageVector,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    width: Dp = target.defaultWidth,
    height: Dp = target.defaultHeight,
    alignment: Alignment = Alignment.Center,
) {
    val preference = motionPreference ?: rememberMotionPreference()
    var displayed by remember { mutableStateOf(target) }
    var animating by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }

    val renderConfiguration = if (preference == MotionPreference.CROSSFADE_ONLY) {
        configuration.copy(fallback = FallbackStrategy.CROSSFADE)
    } else {
        configuration
    }

    MorphVector(
        from = displayed,
        to = target,
        progress = if (animating) progress.value.coerceIn(0f, 1f) else 1f,
        modifier = modifier,
        configuration = renderConfiguration,
        contentDescription = contentDescription,
        width = width,
        height = height,
        alignment = alignment,
    )

    LaunchedEffect(target) {
        if (target == displayed) return@LaunchedEffect
        // A retarget cancels this effect and restarts it from `displayed`, which is only ever
        // advanced after a transition settles — the documented restart-from-last-settled rule.
        progress.snapTo(0f)
        animating = true
        when (preference) {
            MotionPreference.INSTANT -> progress.snapTo(1f)
            MotionPreference.REDUCED -> progress.animateTo(1f, tween(120))
            else -> progress.animateTo(1f, animationSpec)
        }
        displayed = target
        animating = false
    }
}
