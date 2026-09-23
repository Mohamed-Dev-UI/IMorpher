package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.animation.BrushChannel
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PathStart

/**
 * `VectorAnimation { ... }` — the timeline DSL.
 *
 * Two styles mix freely:
 *
 * 1. Keyframe style (`at`) — declarative points on a global 0..1 timeline:
 * ```
 * VectorAnimation {
 *     at(0f)  { alpha = 0f; scale = 0.9f }
 *     at(0.2f){ reveal = 1f }
 *     at(0.7f){ fillProgress = 1f }
 *     at(1f)  { scale = 1f }
 * }
 * ```
 *
 * 2. Track style — per-path or per-group property tracks with independent intervals:
 * ```
 * VectorAnimation {
 *     path("outline") {
 *         strokeReveal(start = PathStart.TOP_RIGHT, direction = DrawDirection.BOTTOM_LEFT, interval = 0f..0.5f)
 *     }
 *     path("fill") {
 *         fill(mode = FillMode.DIRECTIONAL, interval = 0.4f..0.9f)
 *     }
 * }
 * ```
 *
 * All times are normalized 0..1 fractions of the driving animation (the `AnimationSpec`
 * passed to [com.imorpher.vectormorph.compose.MorphIcon] or the driver behind [DrawIcon]).
 */
class VectorAnimationBuilder internal constructor() {

    internal var defaultEasing: Easing = androidx.compose.animation.core.FastOutSlowInEasing
    internal var appearanceOnly = true
    private var customizer: CustomVectorAnimation? = null

    // keyframe storage: time -> (prop -> value)
    private val keyframes = sortedMapOf<Float, MutableMap<PropKey, Float>>()
    private val keyframeEasings = mutableMapOf<Float, Easing>()
    private val keyframeColors = sortedMapOf<Float, Color>()

    // explicit segments
    private data class ScalarRequest(
        val target: AnimationTarget,
        val prop: PropKey,
        val start: Float,
        val end: Float,
        val from: Float,
        val to: Float,
        val easing: Easing,
        val draw: VectorAnimation.DrawConfig?,
    )

    private data class BrushRequest(
        val target: AnimationTarget,
        val channel: BrushChannel,
        val start: Float,
        val end: Float,
        val from: BrushSpec,
        val to: BrushSpec,
        val easing: Easing,
    )

    private val scalars = ArrayList<ScalarRequest>()
    private val brushes = ArrayList<BrushRequest>()
    internal var staggerDelay: Float = 0f
    internal var staggerOrder: com.imorpher.vectormorph.core.model.DrawOrderStrategy =
        com.imorpher.vectormorph.core.model.DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT

    // ------------------------------------------------------------------ entry points

    fun defaultEasing(easing: Easing) {
        defaultEasing = easing
    }

    /** Install a path callback for fully custom geometry, paint, reveal, and transforms. */
    fun customAnimation(animation: CustomVectorAnimation) {
        customizer = animation
    }

    /** Declares property values at a normalized [time]. Consecutive keys interpolate. */
    fun at(time: Float, easing: Easing? = null, block: KeyframeScope.() -> Unit) {
        val scope = KeyframeScope()
        scope.block()
        val t = time.coerceIn(0f, 1f)
        val map = keyframes.getOrPut(t) { mutableMapOf() }
        map.putAll(scope.values)
        scope.color?.let { keyframeColors[t] = it }
        if (easing != null) keyframeEasings[t] = easing
        if (scope.values.isNotEmpty()) appearanceOnly = false
    }

    fun path(name: String, occurrence: Int = 0, block: PathAnimationScope.() -> Unit) =
        PathAnimationScope(AnimationTarget.Path(name, occurrence), this).apply(block)

    fun pathIndex(index: Int, block: PathAnimationScope.() -> Unit) =
        PathAnimationScope(AnimationTarget.PathIndex(index), this).apply(block)

    fun allPaths(block: PathAnimationScope.() -> Unit) =
        PathAnimationScope(AnimationTarget.AllPaths, this).apply(block)

    fun group(name: String, block: PathAnimationScope.() -> Unit) =
        PathAnimationScope(AnimationTarget.Group(name), this).apply(block)

    fun stagger(delay: Float, order: com.imorpher.vectormorph.core.model.DrawOrderStrategy) {
        staggerDelay = delay
        staggerOrder = order
    }

    // ------------------------------------------------------------------ vector-level tracks

    /** Reveals (draws) every path, optionally coupling the fill to follow the stroke. */
    fun reveal(
        mode: DrawMode = DrawMode.FORWARD,
        start: PathStart = PathStart.START,
        direction: DrawDirection = DrawDirection.AUTO,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
        followWithFill: Boolean = true,
    ) {
        allPaths {
            drawProgress(
                interval = interval,
                easing = easing ?: defaultEasing,
                mode = mode,
                start = start,
                direction = direction,
            )
        }
        if (followWithFill) {
            // fill trails the stroke slightly for a polished "draw then fill" feel
            val fillStart = (interval.start + (interval.endInclusive - interval.start) * 0.65f).coerceIn(0f, 1f)
            allPaths {
                fillProgress(0f, 1f, fillStart..interval.endInclusive, easing ?: defaultEasing)
                fill(mode = FillMode.DIRECTIONAL, direction = direction)
            }
        }
    }

    fun drawProgress(from: Float = 0f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        allPaths { drawProgress(from, to, interval, easing ?: defaultEasing) }
    }

    fun fillProgress(from: Float = 0f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        allPaths { fillProgress(from, to, interval, easing ?: defaultEasing) }
    }

    fun fill(
        mode: FillMode = FillMode.DIRECTIONAL,
        direction: DrawDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        allPaths { fill(mode, direction, interval, easing ?: defaultEasing) }
    }

    fun morphProgress(from: Float = 0f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        allPaths { morph(from, to, interval, easing ?: defaultEasing) }
    }

    fun alpha(from: Float = 1f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        addScalar(AnimationTarget.Vector, PropKey.ALPHA, from, to, interval, easing ?: defaultEasing)
    }

    fun scale(from: Float, to: Float, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null, origin: com.imorpher.vectormorph.core.model.TransformOrigin = com.imorpher.vectormorph.core.model.TransformOrigin.Center) {
        addScalar(AnimationTarget.Vector, PropKey.SCALE_X, from, to, interval, easing ?: defaultEasing)
        addScalar(AnimationTarget.Vector, PropKey.SCALE_Y, from, to, interval, easing ?: defaultEasing)
        addScalar(AnimationTarget.Vector, PropKey.PIVOT_X, origin.x, origin.x, interval, easing ?: defaultEasing)
        addScalar(AnimationTarget.Vector, PropKey.PIVOT_Y, origin.y, origin.y, interval, easing ?: defaultEasing)
    }

    fun rotation(from: Float, to: Float, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null, pivot: com.imorpher.vectormorph.core.model.TransformOrigin = com.imorpher.vectormorph.core.model.TransformOrigin.Center) {
        addScalar(AnimationTarget.Vector, PropKey.ROTATION_DEG, from, to, interval, easing ?: defaultEasing)
        addScalar(AnimationTarget.Vector, PropKey.PIVOT_X, pivot.x, pivot.x, interval, easing ?: defaultEasing)
        addScalar(AnimationTarget.Vector, PropKey.PIVOT_Y, pivot.y, pivot.y, interval, easing ?: defaultEasing)
    }

    fun translation(fromX: Float, fromY: Float, toX: Float, toY: Float, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        addScalar(AnimationTarget.Vector, PropKey.TRANSLATION_X, fromX, toX, interval, easing ?: defaultEasing)
        addScalar(AnimationTarget.Vector, PropKey.TRANSLATION_Y, fromY, toY, interval, easing ?: defaultEasing)
    }

    fun color(from: Color, to: Color, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        brushes.add(
            BrushRequest(
                AnimationTarget.Vector, BrushChannel.FILL, interval.start, interval.endInclusive,
                BrushSpec.Solid(from), BrushSpec.Solid(to),
                easing ?: defaultEasing,
            )
        )
    }

    /** Reveals inherited group clipping geometry along a directional moving edge. */
    fun clipReveal(
        from: Float = 0f,
        to: Float = 1f,
        direction: DrawDirection = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) = allPaths {
        clipReveal(from, to, direction, interval, easing ?: defaultEasing)
    }

    /** Animate every path's stroke paint from one solid color to another. */
    fun strokeColor(from: Color, to: Color, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        allPaths { strokeColor(from, to, interval, easing) }
    }

    fun gradient(from: BrushSpec, to: BrushSpec, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        brushes.add(BrushRequest(AnimationTarget.Vector, BrushChannel.FILL, interval.start, interval.endInclusive, from, to, easing ?: defaultEasing))
    }

    // ------------------------------------------------------------------ compile

    internal fun addScalar(
        target: AnimationTarget,
        prop: PropKey,
        from: Float,
        to: Float,
        interval: ClosedFloatingPointRange<Float>,
        easing: Easing,
        draw: VectorAnimation.DrawConfig? = null,
    ) {
        val start = interval.start.coerceIn(0f, 1f)
        val end = interval.endInclusive.coerceIn(0f, 1f)
        if (end <= start) return
        scalars.add(ScalarRequest(target, prop, start, end, from, to, easing, draw))
        if (prop != PropKey.ALPHA && prop != PropKey.FILL_ALPHA && prop != PropKey.STROKE_ALPHA) {
            appearanceOnly = false
        }
    }

    internal fun addBrush(
        target: AnimationTarget,
        start: Float,
        end: Float,
        from: BrushSpec,
        to: BrushSpec,
        easing: Easing,
        channel: BrushChannel = BrushChannel.FILL,
    ) {
        if (end <= start) return
        brushes.add(BrushRequest(target, channel, start, end, from, to, easing))
    }

    internal fun build(): VectorAnimation {
        val compiledScalars = ArrayList<VectorAnimation.Track>(scalars.size + 8)

        // 1) keyframes → segments (vector level)
        val keys = keyframes.keys.toList()
        for (prop in PropKey.entries) {
            val touchedKeys = keys.filter { keyframes.getValue(it).containsKey(prop) }
            if (touchedKeys.isEmpty()) continue
            val segments = ArrayList<VectorAnimation.Segment>(maxOf(1, touchedKeys.size - 1))
            if (touchedKeys.size == 1) {
                val v = keyframes.getValue(touchedKeys[0]).getValue(prop)
                segments.add(
                    VectorAnimation.Segment(0f, 1f, v, v, keyframeEasings[touchedKeys[0]] ?: defaultEasing)
                )
            } else {
                for (i in 0 until touchedKeys.size - 1) {
                    val t0 = touchedKeys[i]
                    val t1 = touchedKeys[i + 1]
                    segments.add(
                        VectorAnimation.Segment(
                            t0, t1,
                            keyframes.getValue(t0).getValue(prop),
                            keyframes.getValue(t1).getValue(prop),
                            keyframeEasings[t1] ?: defaultEasing,
                        )
                    )
                }
            }
            compiledScalars.add(VectorAnimation.Track(AnimationTarget.Vector, prop, segments))
        }

        // 2) Compile explicit intervals together per (target, property). This preserves every
        //    interval and lets a timeline hold the previous value across gaps.
        for ((targetAndProperty, requests) in scalars.groupBy { it.target to it.prop }) {
            val segments = requests.sortedBy { it.start }.map { req ->
                VectorAnimation.Segment(req.start, req.end, req.from, req.to, req.easing, req.draw)
            }
            compiledScalars.add(
                VectorAnimation.Track(
                    targetAndProperty.first,
                    targetAndProperty.second,
                    segments,
                )
            )
        }

        // 3) color keyframes form a proper color timeline instead of zero-length tracks.
        val compiledBrushes = ArrayList<VectorAnimation.BrushTrack>()
        val colorKeys = keyframeColors.entries.toList()
        if (colorKeys.isNotEmpty()) {
            val segments = ArrayList<VectorAnimation.BrushSegment>(maxOf(1, colorKeys.size - 1))
            if (colorKeys.size == 1) {
                val color = BrushSpec.Solid(colorKeys.first().value)
                segments.add(VectorAnimation.BrushSegment(0f, 1f, color, color, defaultEasing))
            } else {
                for (i in 0 until colorKeys.lastIndex) {
                    val start = colorKeys[i]
                    val end = colorKeys[i + 1]
                    segments.add(
                        VectorAnimation.BrushSegment(
                            start.key,
                            end.key,
                            BrushSpec.Solid(start.value),
                            BrushSpec.Solid(end.value),
                            keyframeEasings[end.key] ?: defaultEasing,
                        )
                    )
                }
            }
            compiledBrushes.add(VectorAnimation.BrushTrack(AnimationTarget.Vector, segments))
        }

        // 4) brush tracks: consecutive same-target brush requests merge into segments
        val brushByTarget = brushes.groupBy { it.target to it.channel }
        for ((targetAndChannel, reqs) in brushByTarget) {
            val sorted = reqs.sortedBy { it.start }
            // single request → one segment; multiple → treat each as a segment
            compiledBrushes.add(
                VectorAnimation.BrushTrack(
                    targetAndChannel.first,
                    sorted.map { VectorAnimation.BrushSegment(it.start, it.end, it.from, it.to, it.easing) },
                    targetAndChannel.second,
                )
            )
        }

        return VectorAnimation(compiledScalars, compiledBrushes, appearanceOnly, staggerDelay, staggerOrder, customizer)
    }
}

// ------------------------------------------------------------------ scopes

/** Property setter available inside `at(t) { ... }` keyframes. */
class KeyframeScope internal constructor() {
    internal val values = mutableMapOf<PropKey, Float>()
    internal var color: Color? = null

    /** Global alpha (0 = invisible). */
    var alpha: Float
        get() = values[PropKey.ALPHA] ?: 1f
        set(v) { values[PropKey.ALPHA] = v }

    /** Uniform scale. */
    var scale: Float
        get() = values[PropKey.SCALE_X] ?: 1f
        set(v) { values[PropKey.SCALE_X] = v; values[PropKey.SCALE_Y] = v }

    var scaleX: Float
        get() = values[PropKey.SCALE_X] ?: 1f
        set(v) { values[PropKey.SCALE_X] = v }

    var scaleY: Float
        get() = values[PropKey.SCALE_Y] ?: -1f
        set(v) { values[PropKey.SCALE_Y] = v }

    /** Degrees, clockwise. */
    var rotation: Float
        get() = values[PropKey.ROTATION_DEG] ?: 0f
        set(v) { values[PropKey.ROTATION_DEG] = v }

    var translationX: Float
        get() = values[PropKey.TRANSLATION_X] ?: 0f
        set(v) { values[PropKey.TRANSLATION_X] = v }

    var translationY: Float
        get() = values[PropKey.TRANSLATION_Y] ?: 0f
        set(v) { values[PropKey.TRANSLATION_Y] = v }

    /** Draw/reveal progress shorthand (same property as [com.imorpher.vectormorph.core.animation.PropKey.DRAW_PROGRESS]). */
    var reveal: Float
        get() = values[PropKey.DRAW_PROGRESS] ?: 1f
        set(v) { values[PropKey.DRAW_PROGRESS] = v }

    var drawProgress: Float
        get() = reveal
        set(v) { reveal = v }

    var fillProgress: Float
        get() = values[PropKey.FILL_PROGRESS] ?: 1f
        set(v) { values[PropKey.FILL_PROGRESS] = v }

    var morphProgress: Float
        get() = values[PropKey.MORPH_PROGRESS] ?: 0f
        set(v) { values[PropKey.MORPH_PROGRESS] = v }

    var strokeWidth: Float
        get() = values[PropKey.STROKE_WIDTH] ?: Float.NaN
        set(v) { values[PropKey.STROKE_WIDTH] = v }

    /** Solid color override at this keyframe. */
    var fillColor: Color?
        get() = color
        set(v) { color = v }
}

/**
 * Property tracks available inside `path("name") { ... }`, `group("g") { ... }`,
 * `allPaths { ... }` scopes. Intervals are normalized 0..1 timeline fractions and may
 * overlap freely (parallel) or tile sequentially.
 */
class PathAnimationScope internal constructor(
    private val target: AnimationTarget,
    private val builder: VectorAnimationBuilder,
) {
    /** Default easing for tracks declared in this scope. */
    var easing: Easing = builder.defaultEasing

    private fun cfg(
        mode: DrawMode, start: PathStart, direction: DrawDirection,
        fillMode: FillMode? = null, fillDirection: DrawDirection? = null,
    ) = VectorAnimation.DrawConfig(mode, start, direction, fillMode, fillDirection)

    /**
     * Progressive stroke draw-on. This is the pen-drawing effect.
     * [mode] picks FORWARD/REVERSE/CENTER_OUT/OUTSIDE_IN/RADIAL; [start] picks where drawing
     * begins (for closed paths the engine finds the best matching point automatically);
     * [direction] picks the overall travel direction.
     */
    fun drawProgress(
        from: Float = 0f,
        to: Float = 1f,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
        mode: DrawMode = DrawMode.FORWARD,
        start: PathStart = PathStart.START,
        direction: DrawDirection = DrawDirection.AUTO,
    ) {
        builder.addScalar(
            target, PropKey.DRAW_PROGRESS, from, to, interval, easing ?: this.easing,
            draw = cfg(mode, start, direction),
        )
    }

    /** Alias of [drawProgress] expressing stroke intent. */
    fun strokeReveal(
        start: PathStart = PathStart.START,
        direction: DrawDirection = DrawDirection.AUTO,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
        mode: DrawMode = DrawMode.FORWARD,
    ) = drawProgress(interval = interval, easing = easing, mode = mode, start = start, direction = direction)

    /** Fill reveal progress. */
    fun fillProgress(
        from: Float = 0f,
        to: Float = 1f,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addScalar(target, PropKey.FILL_PROGRESS, from, to, interval, easing ?: this.easing)
    }

    /** Fill reveal with an explicit mode + direction (attached to the FILL_PROGRESS track). */
    fun fill(
        mode: FillMode = FillMode.DIRECTIONAL,
        direction: DrawDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addScalar(
            target, PropKey.FILL_PROGRESS, 0f, 1f, interval, easing ?: this.easing,
            draw = cfg(DrawMode.FORWARD, PathStart.START, DrawDirection.AUTO, fillMode = mode, fillDirection = direction),
        )
    }

    /** Progressively reveals the inherited clipping mask of this path's named group. */
    fun clipReveal(
        from: Float = 0f,
        to: Float = 1f,
        direction: DrawDirection = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        require(direction != DrawDirection.CUSTOM) { "Use CustomVectorAnimation for custom clip geometry" }
        builder.addScalar(
            target, PropKey.CLIP_PROGRESS, from, to, interval, easing ?: this.easing,
            draw = cfg(DrawMode.FORWARD, PathStart.START, direction),
        )
    }

    /** When this path's geometry morphs (0 = fully source, 1 = fully target). */
    fun morph(
        from: Float = 0f,
        to: Float = 1f,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addScalar(target, PropKey.MORPH_PROGRESS, from, to, interval, easing ?: this.easing)
    }

    fun alpha(from: Float = 1f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        builder.addScalar(target, PropKey.ALPHA, from, to, interval, easing ?: this.easing)
    }

    fun fillAlpha(from: Float = 1f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        builder.addScalar(target, PropKey.FILL_ALPHA, from, to, interval, easing ?: this.easing)
    }

    fun strokeAlpha(from: Float = 1f, to: Float = 1f, interval: ClosedFloatingPointRange<Float> = 0f..1f, easing: Easing? = null) {
        builder.addScalar(target, PropKey.STROKE_ALPHA, from, to, interval, easing ?: this.easing)
    }

    fun scale(
        from: Float,
        to: Float,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
        origin: com.imorpher.vectormorph.core.model.TransformOrigin = com.imorpher.vectormorph.core.model.TransformOrigin.Center,
    ) {
        builder.addScalar(target, PropKey.SCALE_X, from, to, interval, easing ?: this.easing)
        builder.addScalar(target, PropKey.SCALE_Y, from, to, interval, easing ?: this.easing)
        builder.addScalar(target, PropKey.PIVOT_X, origin.x, origin.x, interval, easing ?: this.easing)
        builder.addScalar(target, PropKey.PIVOT_Y, origin.y, origin.y, interval, easing ?: this.easing)
    }

    fun rotation(
        from: Float,
        to: Float,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
        pivot: com.imorpher.vectormorph.core.model.TransformOrigin = com.imorpher.vectormorph.core.model.TransformOrigin.Center,
    ) {
        builder.addScalar(target, PropKey.ROTATION_DEG, from, to, interval, easing ?: this.easing)
        builder.addScalar(target, PropKey.PIVOT_X, pivot.x, pivot.x, interval, easing ?: this.easing)
        builder.addScalar(target, PropKey.PIVOT_Y, pivot.y, pivot.y, interval, easing ?: this.easing)
    }

    fun translation(
        fromX: Float, fromY: Float, toX: Float, toY: Float,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addScalar(target, PropKey.TRANSLATION_X, fromX, toX, interval, easing ?: this.easing)
        builder.addScalar(target, PropKey.TRANSLATION_Y, fromY, toY, interval, easing ?: this.easing)
    }

    fun strokeWidth(
        from: Float,
        to: Float,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addScalar(target, PropKey.STROKE_WIDTH, from, to, interval, easing ?: this.easing)
    }

    /** Solid color interpolation for this path. */
    fun color(
        from: Color,
        to: Color,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addBrush(target, interval.start, interval.endInclusive, BrushSpec.Solid(from), BrushSpec.Solid(to), easing ?: this.easing)
    }

    fun strokeColor(
        from: Color,
        to: Color,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addBrush(
            target, interval.start, interval.endInclusive,
            BrushSpec.Solid(from), BrushSpec.Solid(to), easing ?: this.easing,
            channel = BrushChannel.STROKE,
        )
    }

    /** Gradient interpolation for this path (gradient↔gradient, solid↔gradient). */
    fun gradient(
        from: BrushSpec,
        to: BrushSpec,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addBrush(target, interval.start, interval.endInclusive, from, to, easing ?: this.easing)
    }

    fun strokeGradient(
        from: BrushSpec,
        to: BrushSpec,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
        easing: Easing? = null,
    ) {
        builder.addBrush(
            target, interval.start, interval.endInclusive, from, to, easing ?: this.easing,
            channel = BrushChannel.STROKE,
        )
    }
}

/** Top-level factory: `val anim = VectorAnimation { ... }`. */
fun VectorAnimation(block: VectorAnimationBuilder.() -> Unit): VectorAnimation {
    val builder = VectorAnimationBuilder()
    builder.block()
    return builder.build()
}
