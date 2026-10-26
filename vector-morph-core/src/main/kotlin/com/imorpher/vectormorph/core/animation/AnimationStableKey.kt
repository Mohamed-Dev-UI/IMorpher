package com.imorpher.vectormorph.core.animation

/**
 * A stable, content-derived key for an entire timeline.
 *
 * Recomposing compositions must key `remember`/`LaunchedEffect` on semantics, not on
 * identity: an inline-DSL one-shot rebuilt per recomposition gets a NEW
 * [VectorAnimation] instance every pass, and keying an effect on that identity restarts
 * the animation forever. [stableKey] returns the same value for both builds so such
 * compositions settle.
 *
 * Folding rules: track targets, property ordinals, segment from/to/interval floats,
 * easing identity, reveal payload fields, and brush specs fold structurally — any
 * difference changes the key. `CustomVectorAnimation` callbacks fold as
 * [System.identityHashCode] (presense marker plus instance identity: callbacks cannot be
 * compared structurally, so the key is deliberately unequal across processes for those
 * timelines; brush `BrushSpec.Unknown` holders are identity-folded for the same reason).
 * Build-time warnings are diagnostics and do NOT fold into the key.
 *
 * Pure fold over already-validated immutable data: safe on any thread.
 */
public fun VectorAnimation.stableKey(): Int {
    var hash = 0xF1E5D9C7.toInt()
    fun mix(int: Int) {
        hash = (hash xor int) * 0x01000193.toInt()
    }
    fun mixFloat(float: Float) = mix(float.toRawBits())

    for (track in tracks) {
        when (val target = track.target) {
            AnimationTarget.Vector -> mix(0)
            AnimationTarget.AllPaths -> mix(1)
            is AnimationTarget.Path -> {
                mix(2); mix(target.name.hashCode()); mix(target.occurrence)
            }
            is AnimationTarget.PathIndex -> { mix(3); mix(target.index) }
            is AnimationTarget.Group -> { mix(4); mix(target.name.hashCode()) }
        }
        mix(track.prop.ordinal)
        for (segment in track.segments) {
            mixFloat(segment.start); mixFloat(segment.end)
            mixFloat(segment.from); mixFloat(segment.to)
            mix(System.identityHashCode(segment.easing))
            when (val reveal = segment.reveal) {
                null -> mix(0)
                is DrawConfig -> {
                    mix(1); mix(reveal.mode.ordinal); mix(reveal.start.ordinal); mix(reveal.direction.ordinal)
                }
                is FillRevealConfig -> {
                    mix(2)
                    mix(reveal.fillMode?.ordinal ?: -1)
                    mix(reveal.fillDirection?.ordinal ?: -2)
                }
                is ClipRevealConfig -> { mix(3); mix(reveal.direction.ordinal) }
            }
        }
    }

    for (track in brushTracks) {
        when (val target = track.target) {
            AnimationTarget.Vector -> mix(10)
            AnimationTarget.AllPaths -> mix(11)
            is AnimationTarget.Path -> { mix(12); mix(target.name.hashCode()); mix(target.occurrence) }
            is AnimationTarget.PathIndex -> { mix(13); mix(target.index) }
            is AnimationTarget.Group -> { mix(14); mix(target.name.hashCode()) }
        }
        mix(track.channel.ordinal)
        for (segment in track.segments) {
            mixFloat(segment.start); mixFloat(segment.end)
            mix(segment.from.hashCode())
            mix(segment.to.hashCode())
            mix(System.identityHashCode(segment.easing))
        }
    }

    mix(staggerDelay.toRawBits())
    mix(staggerOrder.ordinal)
    val customizer = customizer
    if (customizer == null) {
        mix(0)
    } else {
        mix(1)
        mix(System.identityHashCode(customizer))
    }
    return hash
}
