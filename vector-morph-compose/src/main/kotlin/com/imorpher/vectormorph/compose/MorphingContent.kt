package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.transition.ContentTransition

/**
 * Content slot that morphs to whatever [target] is currently set, from whatever it last
 * settled on — the [MorphingIcon] chained/state-driven contract applied to composable
 * content ([MorphContent] endpoints).
 *
 * Contract (important for chained/state-driven content):
 *  - The first composition draws [to] immediately at progress 1 — no intro animation.
 *  - When [target] changes, the morph runs from the **last settled** slot content to the
 *    new [to] slot: both stay composed for the whole transition (slot state, remember
 *    blocks, and scroll positions stay alive, exactly as [MorphContent] promises).
 *  - If [target] changes again mid-flight, the in-progress morph is abandoned and the new
 *    morph restarts from that same last settled content (never from a mid-animation frame).
 *  - Reduced/instant motion preferences control the progress clock: `INSTANT` renders the
 *    new target directly; `REDUCED` shortens the transition. Visual layer preferences are
 *    resolved by [MorphContent] itself.
 *
 * Retargeting is keyed on [target] reference equality, mirroring `AnimatedContent`'
 * state-based usage; pass a stable state (a model object, enum, or key) rather than a raw
 * lambda for `[to]` — the slot lambda is re-invoked on every frame anyway.
 *
 * Use [MorphPrecompiler.warm] for icon pairs that must animate on first display.
 */
@Composable
fun MorphingContent(
    target: Any?,
    to: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    transition: ContentTransition = ContentTransition.Default,
    contentAlignment: Alignment = Alignment.TopStart,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
) {
    val preference = motionPreference ?: rememberMotionPreference()
    val renderTransition = if (preference == MotionPreference.CROSSFADE_ONLY) {
        ContentTransition.Crossfade()
    } else {
        transition
    }

    var settledTarget by remember { mutableStateOf(target) }
    var settledContent by remember { mutableStateOf(to) }
    var animating by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    val currentTo by rememberUpdatedState(to)

    MorphContent(
        progress = if (animating) progress.value.coerceIn(0f, 1f) else 1f,
        modifier = modifier,
        transition = renderTransition,
        contentAlignment = contentAlignment,
        contentDescription = contentDescription,
        from = settledContent,
        to = currentTo,
    )

    LaunchedEffect(target) {
        if (target == settledTarget) return@LaunchedEffect
        // A retarget cancels this effect and restarts it from `settledContent`, which is only
        // ever advanced after a transition settles — the documented restart-from-last-settled rule.
        progress.snapTo(0f)
        animating = true
        when (preference) {
            MotionPreference.INSTANT -> progress.snapTo(1f)
            MotionPreference.REDUCED -> progress.animateTo(1f, tween(120))
            else -> progress.animateTo(1f, animationSpec)
        }
        settledTarget = target
        settledContent = currentTo
        animating = false
    }
}
