package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.imorpher.vectormorph.core.model.MotionPreference

/**
 * Drives a normalized morph progress from a boolean selection, with an explicit settle callback.
 *
 * Unlike `animateFloatAsState`, this controller owns an [Animatable], so an interrupted run can
 * be retargeted from wherever it currently is, and [onSettled] fires exactly once each time the
 * value comes to rest at the requested target. A retarget mid-flight cancels the pending
 * animation and its callback — the callback belongs to the settled state, not to every frame.
 *
 * @param selected target selection; `true` animates to 1, `false` to 0.
 * @param animationSpec motion curve for the full transition.
 * @param motionPreference explicit accessibility preference, or null to resolve from the system
 *   animator-duration scale (see [rememberMotionPreference]).
 * @param onSettled invoked with the settled selection once per completed transition (including
 *   the initial resting state).
 */
@Composable
fun rememberMorphAnimation(
    selected: Boolean,
    animationSpec: AnimationSpec<Float> = tween(350),
    motionPreference: MotionPreference? = null,
    onSettled: ((Boolean) -> Unit)? = null,
): State<Float> {
    val preference = motionPreference ?: rememberMotionPreference()
    val target = if (selected) 1f else 0f
    val spec = when (preference) {
        MotionPreference.REDUCED -> tween<Float>(durationMillis = 120)
        else -> animationSpec
    }
    val progress = remember { Animatable(target) }
    val state = remember(progress) { derivedStateOf { progress.value } }
    val latestCallback = rememberUpdatedState(onSettled)
    val latestSelected = rememberUpdatedState(selected)
    val latestSpec = rememberUpdatedState(spec)

    // Key on the target only: the default animationSpec expression allocates a new instance per
    // composition, and keying on it would restart the animation every frame forever.
    LaunchedEffect(target, preference) {
        when (preference) {
            MotionPreference.INSTANT -> progress.snapTo(target)
            else -> progress.animateTo(target, latestSpec.value)
        }
        // Reached only when the animation completed (cancellation throws and skips this line),
        // so interruption never reports a state the UI did not settle on.
        latestCallback.value?.invoke(latestSelected.value)
    }
    return state
}
