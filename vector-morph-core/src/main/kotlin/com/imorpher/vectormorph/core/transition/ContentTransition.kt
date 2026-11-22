package com.imorpher.vectormorph.core.transition

import com.imorpher.vectormorph.core.model.MotionPreference

/**
 * A progress-driven **visual transition** between two rendered endpoints.
 *
 * This is the non-geometric half of the library's content model: the two endpoints are
 * composited (faded, wiped, transformed) and never inspected for matching paths, contours, or
 * paint. Use it for opaque painters, raster images, video frames, and arbitrary composable
 * slots, where interpolating internal geometry is either impossible or meaningless. Vector
 * endpoints keep using `MorphVector` and the planning pipeline.
 *
 * The model is pure data plus [ContentTransitionResolver]; rendering is platform-side. Every
 * transition honors the same contract:
 *
 * - Progress is normalized, caller-owned, and clamped to `0..1` (NaN resolves to `0`).
 * - At progress `0` the outgoing endpoint renders exactly on its own; at progress `1` the
 *   incoming endpoint does. A configured [ContentEffect] can intentionally break that
 *   endpoint guarantee, exactly as an explicit effect would on any other frame.
 * - A transition never claims to interpolate hidden structure: [Crossfade] and [FadeOver]
 *   change composite alpha, and [Wipe] changes which pixels of the incoming endpoint exist.
 *
 * Both endpoints are intended to stay composed for the whole transition. That keeps their
 * state alive, makes seeking/retargeting deterministic, and avoids a composition boundary at
 * the halfway point; the caller remains responsible for the cost of both endpoint trees.
 */
sealed interface ContentTransition {

    /** Per-endpoint layer effects applied in addition to the compositing mode. */
    val effects: List<ContentEffect>

    /**
     * Both endpoints fade against the same background: the outgoing alpha is `1 - progress`
     * and the incoming alpha is `progress`. At the midpoint the background shows through, so
     * this is the "through" composite (the historical `Crossfade` behavior).
     */
    data class Crossfade(
        override val effects: List<ContentEffect> = emptyList(),
    ) : ContentTransition

    /**
     * The incoming endpoint fades in over the outgoing endpoint, which stays fully opaque.
     * At the midpoint the result is the outgoing endpoint with the incoming endpoint painted
     * over it at half alpha; nothing of the background shows through the outgoing pixels.
     * This is the "over" composite.
     */
    data class FadeOver(
        override val effects: List<ContentEffect> = emptyList(),
    ) : ContentTransition

    /**
     * A directional reveal: the incoming endpoint is uncovered by a moving edge while the
     * outgoing endpoint stays fully opaque behind it.
     *
     * [feather] softens the edge across that fraction of the wipe axis. `0` is a hard clip,
     * `0.5` is the maximum useful value (a wider ramp would never fully uncover the layer at
     * progress 1); values outside `0..0.5` are clamped, and non-finite values resolve to `0`.
     * At progress `0` none of the incoming endpoint is drawn; at `1` all of it is.
     */
    data class Wipe(
        val direction: WipeDirection = WipeDirection.LEFT_TO_RIGHT,
        val feather: Float = 0f,
        override val effects: List<ContentEffect> = emptyList(),
    ) : ContentTransition

    companion object {
        /** The default transition: a through crossfade with no extra effects. */
        val Default: ContentTransition = Crossfade()
    }
}

/**
 * Returns this transition adapted for an accessibility [MotionPreference].
 *
 * Content transitions are progress-driven, so the preference cannot shorten an animation the
 * caller owns; it reduces the *spatial* motion the transition would otherwise paint:
 *
 * - [MotionPreference.FULL] returns this transition unchanged.
 * - [MotionPreference.REDUCED] keeps the compositing mode (crossfade, fade-over, wipe) and
 *   alpha [ContentEffect.Fade]s, but drops [ContentEffect.Scale], [ContentEffect.Translate],
 *   and [ContentEffect.Rotate] — the layer transforms a reduced-motion user is sensitive to.
 * - [MotionPreference.CROSSFADE_ONLY] resolves to a plain crossfade with only fade effects,
 *   mirroring the vector family's crossfade fallback override.
 * - [MotionPreference.INSTANT] resolves to a crossfade with no effects; the resolver snaps
 *   progress to an endpoint so nothing is painted part-way.
 *
 * The adaptation is pure and observable: callers can report the resolved choice, and tests can
 * pin it without rendering.
 */
fun ContentTransition.reduced(preference: MotionPreference): ContentTransition {
    val fades = effects.filterIsInstance<ContentEffect.Fade>()
    return when (preference) {
        MotionPreference.FULL -> this

        MotionPreference.REDUCED -> when (this) {
            is ContentTransition.Crossfade -> copy(effects = fades)
            is ContentTransition.FadeOver -> copy(effects = fades)
            is ContentTransition.Wipe -> copy(effects = fades)
        }

        MotionPreference.CROSSFADE_ONLY -> ContentTransition.Crossfade(effects = fades)

        MotionPreference.INSTANT -> ContentTransition.Crossfade()
    }
}

/** Which endpoint of a [ContentTransition] an effect animates. */
enum class ContentEndpoint {
    /** The outgoing endpoint, fully visible at progress `0`. */
    FROM,

    /** The incoming endpoint, fully visible at progress `1`. */
    TO,
}

/** The four cardinal axes a [ContentTransition.Wipe] can travel along. */
enum class WipeDirection {
    /** Reveal from the left edge toward the right edge. */
    LEFT_TO_RIGHT,

    /** Reveal from the right edge toward the left edge. */
    RIGHT_TO_LEFT,

    /** Reveal from the top edge toward the bottom edge. */
    TOP_TO_BOTTOM,

    /** Reveal from the bottom edge toward the top edge. */
    BOTTOM_TO_TOP,
}

/**
 * One animated layer property for one [ContentEndpoint]. Values interpolate linearly with
 * transition progress and compose with the base compositing mode and with each other:
 *
 * - [Fade] **multiplies** the endpoint's alpha (the mode's alpha curve stays authoritative).
 * - [Scale] multiplies both axes (a negative or non-finite scale resolves to `0`; the final
 *   scale is clamped to `0..4`).
 * - [Translate] adds to the offset, as a fraction of the layer's own width/height.
 * - [Rotate] adds to the rotation, in degrees, around the layer center.
 *
 * The four properties are independent, so effects compose commutatively: list order never
 * changes the resolved layer (repeated translations add, repeated scales multiply, repeated
 * fades multiply, repeated rotations add). Translation, rotation, and scale describe the
 * endpoint **layer**, not its internal content; they never change layout or hit-testing.
 */
sealed interface ContentEffect {

    /** The endpoint this effect animates. */
    val endpoint: ContentEndpoint

    /**
     * Multiplies the endpoint alpha from [from] to [to] as progress advances. Both values are
     * clamped to `0..1` (non-finite values resolve to the neutral `1`); `from = 1, to = 1` is
     * the neutral default.
     *
     * Prefer the transition mode for ordinary crossfades. This effect is for extra fades that
     * compose with a mode, such as fading a wiped layer in at the same time.
     */
    data class Fade(
        override val endpoint: ContentEndpoint,
        val from: Float = 1f,
        val to: Float = 1f,
    ) : ContentEffect

    /**
     * Scales the endpoint from [from] to [to]. `1` is neutral; values are clamped to `0..4`,
     * and non-finite values resolve to `0`. Scaling around the layer center never changes
     * layout or hit-testing.
     */
    data class Scale(
        override val endpoint: ContentEndpoint,
        val from: Float = 1f,
        val to: Float = 1f,
    ) : ContentEffect

    /**
     * Offsets the endpoint from ([fromX], [fromY]) to ([toX], [toY]), in fractions of the
     * layer's width and height (positive X is right, positive Y is down). Non-finite values
     * resolve to `0`; the offset never changes layout or hit-testing.
     */
    data class Translate(
        override val endpoint: ContentEndpoint,
        val fromX: Float = 0f,
        val fromY: Float = 0f,
        val toX: Float = 0f,
        val toY: Float = 0f,
    ) : ContentEffect

    /**
     * Rotates the endpoint from [fromDegrees] to [toDegrees] around the layer center, in
     * degrees (positive = clockwise in Android's Y-down screen space). Non-finite values
     * resolve to `0`; rotation never changes layout or hit-testing.
     */
    data class Rotate(
        override val endpoint: ContentEndpoint,
        val fromDegrees: Float = 0f,
        val toDegrees: Float = 0f,
    ) : ContentEffect
}

/**
 * The resolved draw state of one endpoint layer for a single progress value.
 *
 * All fields are final, sanitized values: [alpha] is in `0..1`, [scaleX]/[scaleY] are in
 * `0..4`, translation is a fraction of the layer size, and rotation is degrees around the
 * layer center.
 */
data class ResolvedContentLayer(
    val alpha: Float,
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val rotationDegrees: Float,
)

/**
 * Wipe state for the incoming layer: [revealedFraction] is the normalized length of the
 * incoming layer that is visible along [direction] (from the edge [direction] starts at), and
 * [feather] is the soft-edge width as a fraction of that axis.
 *
 * Renderers may early-out when [revealedFraction] is `0` (draw nothing) or `1` (draw without a
 * mask). Both values are sanitized by [ContentTransitionResolver].
 */
data class ResolvedWipe(
    val direction: WipeDirection,
    val revealedFraction: Float,
    val feather: Float,
)

/**
 * The complete resolved state of a [ContentTransition] at one progress value. [wipe] is
 * non-null only for [ContentTransition.Wipe]; it always applies to the [to] layer.
 */
data class ResolvedContentTransition(
    val from: ResolvedContentLayer,
    val to: ResolvedContentLayer,
    val wipe: ResolvedWipe?,
)

/**
 * Resolves a [ContentTransition] at a caller-owned progress value.
 *
 * Pure and allocation-light (two small layers plus an optional wipe); safe to call per frame
 * and from preflight code. Progress is clamped to `0..1`, NaN resolves to `0`, and infinities
 * resolve to their nearest endpoint. Every effect value is sanitized before interpolation, so
 * a malformed configuration cannot emit NaN into a renderer.
 */
object ContentTransitionResolver {

    /** Resolves [transition] at [progress]; see the class KDoc for normalization rules. */
    fun resolve(
        progress: Float,
        transition: ContentTransition = ContentTransition.Default,
    ): ResolvedContentTransition = resolve(progress, transition, MotionPreference.FULL)

    /**
     * Resolves [transition] at [progress] under an accessibility [motionPreference].
     *
     * The transition is first reduced by [ContentTransition.reduced], then evaluated. Under
     * [MotionPreference.INSTANT] progress snaps to the nearer endpoint (`0.5` resolves to `1`),
     * so the outgoing endpoint renders exactly before the midpoint and the incoming one from it
     * — no partial alpha, wipe, or transform is ever painted.
     */
    fun resolve(
        progress: Float,
        transition: ContentTransition,
        motionPreference: MotionPreference,
    ): ResolvedContentTransition {
        val effective = transition.reduced(motionPreference)
        val requested = normalizeProgress(progress)
        val t = if (motionPreference == MotionPreference.INSTANT) {
            if (requested >= 0.5f) 1f else 0f
        } else {
            requested
        }

        var fromAlpha = 1f
        var toAlpha = 1f
        var wipe: ResolvedWipe? = null

        when (effective) {
            is ContentTransition.Crossfade -> {
                fromAlpha = 1f - t
                toAlpha = t
            }

            is ContentTransition.FadeOver -> {
                fromAlpha = 1f
                toAlpha = t
            }

            is ContentTransition.Wipe -> {
                // Both layers stay opaque; the incoming layer's mask decides what exists.
                fromAlpha = 1f
                toAlpha = 1f
                wipe = ResolvedWipe(
                    direction = effective.direction,
                    revealedFraction = t,
                    feather = if (effective.feather.isFinite()) {
                        effective.feather.coerceIn(0f, 0.5f)
                    } else {
                        0f
                    },
                )
            }
        }

        var fromScaleX = 1f
        var fromScaleY = 1f
        var fromTranslateX = 0f
        var fromTranslateY = 0f
        var fromRotation = 0f
        var toScaleX = 1f
        var toScaleY = 1f
        var toTranslateX = 0f
        var toTranslateY = 0f
        var toRotation = 0f

        for (effect in effective.effects) {
            val isFrom = effect.endpoint == ContentEndpoint.FROM
            when (effect) {
                is ContentEffect.Fade -> {
                    val multiplier = lerp(sanitizeUnit(effect.from), sanitizeUnit(effect.to), t)
                        .coerceIn(0f, 1f)
                    if (isFrom) fromAlpha *= multiplier else toAlpha *= multiplier
                }

                is ContentEffect.Scale -> {
                    val scale = lerp(
                        sanitizeNonNegative(effect.from),
                        sanitizeNonNegative(effect.to),
                        t,
                    )
                    if (isFrom) {
                        fromScaleX *= scale
                        fromScaleY *= scale
                    } else {
                        toScaleX *= scale
                        toScaleY *= scale
                    }
                }

                is ContentEffect.Translate -> {
                    val x = lerp(sanitizeFinite(effect.fromX), sanitizeFinite(effect.toX), t)
                    val y = lerp(sanitizeFinite(effect.fromY), sanitizeFinite(effect.toY), t)
                    if (isFrom) {
                        fromTranslateX += x
                        fromTranslateY += y
                    } else {
                        toTranslateX += x
                        toTranslateY += y
                    }
                }

                is ContentEffect.Rotate -> {
                    val degrees = lerp(
                        sanitizeFinite(effect.fromDegrees),
                        sanitizeFinite(effect.toDegrees),
                        t,
                    )
                    if (isFrom) fromRotation += degrees else toRotation += degrees
                }
            }
        }

        return ResolvedContentTransition(
            from = ResolvedContentLayer(
                alpha = fromAlpha.coerceIn(0f, 1f),
                scaleX = fromScaleX.coerceIn(0f, MAX_SCALE),
                scaleY = fromScaleY.coerceIn(0f, MAX_SCALE),
                translationX = fromTranslateX,
                translationY = fromTranslateY,
                rotationDegrees = fromRotation,
            ),
            to = ResolvedContentLayer(
                alpha = toAlpha.coerceIn(0f, 1f),
                scaleX = toScaleX.coerceIn(0f, MAX_SCALE),
                scaleY = toScaleY.coerceIn(0f, MAX_SCALE),
                translationX = toTranslateX,
                translationY = toTranslateY,
                rotationDegrees = toRotation,
            ),
            wipe = wipe,
        )
    }

    private const val MAX_SCALE = 4f

    private fun normalizeProgress(progress: Float): Float = when {
        progress.isNaN() -> 0f
        progress <= 0f -> 0f
        progress >= 1f -> 1f
        else -> progress
    }

    private fun sanitizeFinite(value: Float): Float = if (value.isFinite()) value else 0f

    private fun sanitizeNonNegative(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, MAX_SCALE) else 0f

    private fun sanitizeUnit(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 1f

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
}
