package com.imorpher.vectormorph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.transition.ContentTransition
import com.imorpher.vectormorph.core.transition.ContentTransitionResolver

/**
 * Progress-driven **visual transition** between two composable slots.
 *
 * This is not a structural or geometric morph: the two trees are composed independently and
 * composited over the same progress value, with no diffing, path extraction, or shared state.
 * Use it for cards, screens, text, and any content that cannot expose matching geometry; for
 * vector endpoints use [MorphVector] or [MorphIcon].
 *
 * Both [from] and [to] stay composed for the entire transition. That keeps each endpoint's
 * state alive (including `remember`, scroll positions, and animations), makes seeking and
 * retargeting deterministic, and avoids a composition boundary at the halfway point. The cost
 * is that both trees exist at once; keep side effects that must not run twice out of the slots,
 * and hoist expensive loading to the caller. The incoming slot is drawn on top, so at
 * overlapping pixels it owns pointer input; an endpoint is marked invisible to accessibility
 * services only when its resolved alpha is `0` or a wipe has not revealed it yet.
 *
 * Progress is caller-owned and clamped to `0..1`. At `0` [from] renders exactly; at `1` [to]
 * does. The container's semantics carry [contentDescription] when supplied; each slot keeps
 * its own semantics. Slots are laid out by the outer [Box] and aligned with
 * [contentAlignment]; a wipe reveals the incoming slot within that slot's own bounds.
 *
 * Accessibility: [motionPreference] applies the same contract as [MorphPainter] — `null`
 * resolves from the system animator-duration scale, `REDUCED` drops layer transforms, and
 * `CROSSFADE_ONLY`/`INSTANT` collapse the transition to a crossfade / an endpoint switch.
 */
@Composable
fun MorphContent(
    progress: Float,
    from: @Composable BoxScope.() -> Unit,
    to: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    transition: ContentTransition = ContentTransition.Default,
    contentAlignment: Alignment = Alignment.TopStart,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
) {
    val preference = motionPreference ?: rememberMotionPreference()
    val resolved = remember(progress, transition, preference) {
        ContentTransitionResolver.resolve(progress, transition, preference)
    }
    val boxModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Box(modifier = boxModifier, contentAlignment = contentAlignment) {
        Box(
            modifier = Modifier.contentEndpoint(resolved.from),
            contentAlignment = contentAlignment,
        ) {
            from()
        }
        Box(
            modifier = Modifier.contentEndpoint(resolved.to, resolved.wipe, incoming = true),
            contentAlignment = contentAlignment,
        ) {
            to()
        }
    }
}
