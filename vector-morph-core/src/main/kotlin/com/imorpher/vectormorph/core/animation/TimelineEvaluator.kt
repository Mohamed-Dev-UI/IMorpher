package com.imorpher.vectormorph.core.animation

import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.interpolation.ColorInterpolator
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.PairKind
import com.imorpher.vectormorph.core.reveal.DrawOrder

/**
 * The output of evaluating a [VectorAnimation] at one progress value.
 *
 * All float arrays are pre-allocated and reused between frames; NaN means
 * "no override this frame — fall back to the path/plan default".
 */
class EvaluatedFrame(pairCount: Int) {
    /** Vector-level property values ([PropKey] indexed). */
    val vectorProps = FloatArray(PropKey.COUNT)

    /** Per-pair property values; index matches [MorphPlan.pairs]. */
    val pathProps: Array<PathFrameValues> = Array(pairCount) { PathFrameValues() }

    /** Brush override at vector level, if any track applies. */
    var vectorBrushMode: Int = PathFrameValues.BRUSH_NONE
    var vectorPrimaryBrush: androidx.compose.ui.graphics.Brush? = null
    var vectorSecondaryBrush: androidx.compose.ui.graphics.Brush? = null
    var vectorBrushProgress: Float = 0.5f
    var vectorStrokeBrushMode: Int = PathFrameValues.BRUSH_NONE
    var vectorPrimaryStrokeBrush: androidx.compose.ui.graphics.Brush? = null
    var vectorSecondaryStrokeBrush: androidx.compose.ui.graphics.Brush? = null
    var vectorStrokeBrushProgress: Float = 0.5f

    init {
        vectorProps.fill(Float.NaN)
        pathProps.forEach { it.props.fill(Float.NaN) }
    }

    constructor(plan: MorphPlan) : this(plan.pairs.size) {
        plan.pairs.forEachIndexed { index, pair ->
            (pair.fromPath?.groupNames.orEmpty() + pair.toPath?.groupNames.orEmpty())
                .distinct().forEach { name -> pathProps[index].groupProps[name] = PathFrameValues() }
        }
    }
}

/**
 * Evaluates a [VectorAnimation] against a [MorphPlan].
 *
 * Target resolution (names/groups → pair indices) happens once at construction; per-frame
 * evaluation is a linear scan over the (few) tracks writing into the reusable
 * [EvaluatedFrame] buffers — no allocation, no geometry work.
 */
class TimelineEvaluator(
    animation: VectorAnimation,
    plan: MorphPlan,
    private val colorSpace: ColorInterpolationSpace,
    staggerDelay: Float = animation.staggerDelay,
    staggerOrder: DrawOrderStrategy = animation.staggerOrder,
    customDrawOrder: List<String> = emptyList(),
    private val timingMode: TimingMode = TimingMode.BY_PATH_LENGTH,
) {
    private val tracks: List<VectorAnimation.Track> = animation.tracks
    private val brushTracks: List<VectorAnimation.BrushTrack> = animation.brushTracks

    /** target → pair indices (built once). */
    private val targetIndexCache = HashMap<AnimationTarget, IntArray>()
    private val staggerOffsets = FloatArray(plan.pairs.size)
    private var maxStaggerOffset = 0f
    private val pathLengths = FloatArray(plan.pairs.size)
    private var maxPathLength = 0f
    private val pathOrderRanks = IntArray(plan.pairs.size)
    /** One shared viewport-space box per named group, covering both morph endpoints. */
    private val groupBounds = HashMap<String, FloatArray>()
    private val groupLengths = HashMap<String, Float>()
    private var maxGroupLength = 0f

    init {
        val order = DrawOrder.orderIndices(plan, staggerOrder, customDrawOrder)
        order.forEachIndexed { rank, pairIndex -> pathOrderRanks[pairIndex] = rank }
        plan.pairs.forEachIndexed { i, pair ->
            val length = maxOf(pair.fromPath?.totalLength ?: 0f, pair.toPath?.totalLength ?: 0f)
            pathLengths[i] = length
            maxPathLength = maxOf(maxPathLength, length)
        }
        val sideLengths = HashMap<String, FloatArray>()
        fun collectGroupLengths(paths: List<com.imorpher.vectormorph.core.model.PreparedPath>, side: Int) {
            paths.forEach { path -> path.groupNames.forEach { name ->
                val lengths = sideLengths.getOrPut(name) { FloatArray(2) }
                lengths[side] += path.totalLength
            } }
        }
        collectGroupLengths(plan.from.paths, 0); collectGroupLengths(plan.to.paths, 1)
        sideLengths.forEach { (name, lengths) ->
            val length = maxOf(lengths[0], lengths[1])
            groupLengths[name] = length
            maxGroupLength = maxOf(maxGroupLength, length)
        }
        for (pair in plan.pairs) {
            for (path in listOfNotNull(pair.fromPath, pair.toPath)) {
                path.groupNames.forEach { name ->
                    val box = groupBounds.getOrPut(name) {
                        floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                    }
                    box[0] = minOf(box[0], path.bounds[0]); box[1] = minOf(box[1], path.bounds[1])
                    box[2] = maxOf(box[2], path.bounds[2]); box[3] = maxOf(box[3], path.bounds[3])
                }
            }
        }
        fun indexesFor(target: AnimationTarget): IntArray = when (target) {
            is AnimationTarget.Vector -> IntArray(0)
            is AnimationTarget.AllPaths -> IntArray(plan.pairs.size) { it }
            is AnimationTarget.PathIndex -> plan.pairs.indices.filter { i ->
                plan.pairs[i].fromPath?.index == target.index || plan.pairs[i].toPath?.index == target.index
            }.toIntArray()
            is AnimationTarget.Path -> {
                val matching = ArrayList<Int>(4)
                plan.pairs.forEachIndexed { idx, pair ->
                    val fromName = pair.fromPath?.name
                    val toName = pair.toPath?.name
                    if (matchesPathName(fromName, target.name, target.occurrence) ||
                        matchesPathName(toName, target.name, target.occurrence)
                    ) matching.add(idx)
                }
                matching.toIntArray()
            }
            is AnimationTarget.Group -> {
                val out = ArrayList<Int>(4)
                plan.pairs.forEachIndexed { idx, pair ->
                    val inFrom = pair.fromPath?.groupNames?.contains(target.name) == true
                    val inTo = pair.toPath?.groupNames?.contains(target.name) == true
                    if (inFrom || inTo) out.add(idx)
                }
                out.toIntArray()
            }
        }

        // pre-resolve all targets referenced by tracks
        val allTargets = HashSet<AnimationTarget>()
        tracks.forEach { allTargets.add(it.target) }
        brushTracks.forEach { allTargets.add(it.target) }
        for (t in allTargets) targetIndexCache[t] = indexesFor(t)

        if (staggerDelay > 0f && plan.pairs.isNotEmpty()) {
            val offsets = DrawOrder.staggerOffsets(plan, order, staggerDelay)
            for (i in offsets.indices) {
                staggerOffsets[i] = offsets[i].coerceAtMost(0.95f)
                maxStaggerOffset = maxOf(maxStaggerOffset, staggerOffsets[i])
            }
        }
    }

    private fun matchesPathName(actual: String?, requested: String, occurrence: Int): Boolean {
        if (actual == null) return false
        val expected = if (occurrence == 0) requested else "$requested[$occurrence]"
        return actual == expected
    }

    /** Evaluates every track at [progress] into the reusable [frame] buffers. */
    fun evaluate(progress: Float, frame: EvaluatedFrame) {
        val p = progress.coerceIn(0f, 1f)

        // reset overrides to NaN (renderer interprets NaN as "use default")
        reset(frame.vectorProps)
        for (i in frame.pathProps.indices) {
            val pv = frame.pathProps[i]
            reset(pv.props)
            pv.drawConfigs.fill(null)
            pv.groupBounds.fill(Float.NaN)
            pv.customOverrides = null
            pv.groupProps.values.forEach { group ->
                reset(group.props)
                group.drawConfigs.fill(null)
                group.groupBounds.fill(Float.NaN)
                group.customOverrides = null
                group.brushMode = PathFrameValues.BRUSH_NONE
                group.primaryBrush = null; group.secondaryBrush = null; group.brushProgress = 0.5f
                group.strokeBrushMode = PathFrameValues.BRUSH_NONE
                group.primaryStrokeBrush = null; group.secondaryStrokeBrush = null; group.strokeBrushProgress = 0.5f
            }
            pv.brushMode = PathFrameValues.BRUSH_NONE
            pv.primaryBrush = null
            pv.secondaryBrush = null
            pv.brushProgress = 0.5f
            pv.strokeBrushMode = PathFrameValues.BRUSH_NONE
            pv.primaryStrokeBrush = null
            pv.secondaryStrokeBrush = null
            pv.strokeBrushProgress = 0.5f
        }
        frame.vectorBrushMode = PathFrameValues.BRUSH_NONE
        frame.vectorPrimaryBrush = null
        frame.vectorSecondaryBrush = null
        frame.vectorBrushProgress = 0.5f
        frame.vectorStrokeBrushMode = PathFrameValues.BRUSH_NONE
        frame.vectorPrimaryStrokeBrush = null
        frame.vectorSecondaryStrokeBrush = null
        frame.vectorStrokeBrushProgress = 0.5f

        for (track in tracks) {
            when (val target = track.target) {
                is AnimationTarget.Vector -> {
                    val value = evaluateTrack(track, p)
                    if (!value.isNaN()) frame.vectorProps[track.prop.ordinal] = value
                }
                else -> {
                    val idxs = targetIndexCache[target] ?: continue
                    for (i in idxs) {
                        if (i in frame.pathProps.indices) {
                            val localProgress = if (target is AnimationTarget.Group) progressForGroup(p, target.name)
                                else progressForPair(p, i)
                            val value = evaluateTrack(track, localProgress)
                            if (value.isNaN()) continue
                            val values = if (target is AnimationTarget.Group) {
                                frame.pathProps[i].groupProps.getOrPut(target.name) { PathFrameValues() }
                            } else frame.pathProps[i]
                            values.props[track.prop.ordinal] = value
                            if (target is AnimationTarget.Group && track.prop in TRANSFORM_PROPS) {
                                groupBounds[target.name]?.copyInto(values.groupBounds)
                            }
                            val active = activeSegment(track.segments, localProgress)
                            val draw = active?.draw
                            if (draw != null) values.drawConfigs[track.prop.ordinal] = draw
                        }
                    }
                }
            }
        }

        for (bt in brushTracks) {
            when (val target = bt.target) {
                is AnimationTarget.Vector -> {
                    val result = evaluateBrush(bt, p) ?: continue
                    if (bt.channel == BrushChannel.FILL) {
                        when (result) {
                            is GradientInterpolator.Result.Interpolated -> {
                                frame.vectorBrushMode = PathFrameValues.BRUSH_INTERPOLATED
                                frame.vectorPrimaryBrush = result.brush
                                frame.vectorSecondaryBrush = null
                                frame.vectorBrushProgress = 1f
                            }
                            is GradientInterpolator.Result.Crossfade -> {
                                frame.vectorBrushMode = PathFrameValues.BRUSH_CROSSFADE
                                frame.vectorPrimaryBrush = result.fromBrush
                                frame.vectorSecondaryBrush = result.toBrush
                                frame.vectorBrushProgress = result.progress
                            }
                            GradientInterpolator.Result.None -> Unit
                        }
                    } else {
                        when (result) {
                            is GradientInterpolator.Result.Interpolated -> {
                                frame.vectorStrokeBrushMode = PathFrameValues.BRUSH_INTERPOLATED
                                frame.vectorPrimaryStrokeBrush = result.brush
                                frame.vectorSecondaryStrokeBrush = null
                                frame.vectorStrokeBrushProgress = 1f
                            }
                            is GradientInterpolator.Result.Crossfade -> {
                                frame.vectorStrokeBrushMode = PathFrameValues.BRUSH_CROSSFADE
                                frame.vectorPrimaryStrokeBrush = result.fromBrush
                                frame.vectorSecondaryStrokeBrush = result.toBrush
                                frame.vectorStrokeBrushProgress = result.progress
                            }
                            GradientInterpolator.Result.None -> Unit
                        }
                    }
                }
                else -> {
                    val idxs = targetIndexCache[target] ?: continue
                    for (i in idxs) {
                        if (i in frame.pathProps.indices) {
                            val localProgress = if (target is AnimationTarget.Group) progressForGroup(p, target.name)
                                else progressForPair(p, i)
                            val result = evaluateBrush(bt, localProgress) ?: continue
                            val values = if (target is AnimationTarget.Group) {
                                frame.pathProps[i].groupProps.getOrPut(target.name) { PathFrameValues() }
                            } else frame.pathProps[i]
                            if (bt.channel == BrushChannel.FILL) {
                                when (result) {
                                    is GradientInterpolator.Result.Interpolated -> {
                                        values.brushMode = PathFrameValues.BRUSH_INTERPOLATED
                                        values.primaryBrush = result.brush
                                        values.secondaryBrush = null
                                        values.brushProgress = 1f
                                    }
                                    is GradientInterpolator.Result.Crossfade -> {
                                        values.brushMode = PathFrameValues.BRUSH_CROSSFADE
                                        values.primaryBrush = result.fromBrush
                                        values.secondaryBrush = result.toBrush
                                        values.brushProgress = result.progress
                                    }
                                    GradientInterpolator.Result.None -> Unit
                                }
                            } else {
                                when (result) {
                                    is GradientInterpolator.Result.Interpolated -> {
                                        values.strokeBrushMode = PathFrameValues.BRUSH_INTERPOLATED
                                        values.primaryStrokeBrush = result.brush
                                        values.secondaryStrokeBrush = null
                                        values.strokeBrushProgress = 1f
                                    }
                                    is GradientInterpolator.Result.Crossfade -> {
                                        values.strokeBrushMode = PathFrameValues.BRUSH_CROSSFADE
                                        values.primaryStrokeBrush = result.fromBrush
                                        values.secondaryStrokeBrush = result.toBrush
                                        values.strokeBrushProgress = result.progress
                                    }
                                    GradientInterpolator.Result.None -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun reset(props: FloatArray) {
        for (i in props.indices) props[i] = Float.NaN
    }

    private fun progressForPair(progress: Float, pairIndex: Int): Float {
        if (pairIndex !in staggerOffsets.indices) return progress
        val timed = when (timingMode) {
            TimingMode.BY_PATH_LENGTH -> {
                val length = pathLengths[pairIndex]
                if (length <= 1e-5f || maxPathLength <= 1e-5f) progress
                else (progress * maxPathLength / length).coerceIn(0f, 1f)
            }
            TimingMode.BY_PATH -> {
                val count = pathOrderRanks.size.coerceAtLeast(1)
                ((progress * count) - pathOrderRanks[pairIndex]).coerceIn(0f, 1f)
            }
            TimingMode.BY_COMMAND, TimingMode.UNIFORM -> progress
        }
        if (maxStaggerOffset <= 0f) return timed
        val duration = (1f - maxStaggerOffset).coerceAtLeast(0.05f)
        return ((timed - staggerOffsets[pairIndex]) / duration).coerceIn(0f, 1f)
    }

    /** Group tracks share one clock across all descendants so the group stays rigid. */
    private fun progressForGroup(progress: Float, name: String): Float = when (timingMode) {
        TimingMode.BY_PATH_LENGTH -> {
            val length = groupLengths[name] ?: 0f
            if (length <= 1e-5f || maxGroupLength <= 1e-5f) progress
            else (progress * maxGroupLength / length).coerceIn(0f, 1f)
        }
        TimingMode.BY_PATH, TimingMode.BY_COMMAND, TimingMode.UNIFORM -> progress
    }

    private fun evaluateBrush(track: VectorAnimation.BrushTrack, progress: Float): GradientInterpolator.Result? {
        val segment = activeSegment(track.segments, progress) ?: return null
        val localT = segmentLocalT(segment.start, segment.end, progress)
        val eased = segment.easing.transform(localT)
        return GradientInterpolator.interpolate(segment.from, segment.to, eased, colorSpace)
    }

    private fun evaluateTrack(track: VectorAnimation.Track, p: Float): Float {
        val seg = activeSegment(track.segments, p) ?: return Float.NaN
        val localT = segmentLocalT(seg.start, seg.end, p)
        val eased = seg.easing.transform(localT)
        return if (track.prop == PropKey.ROTATION_DEG) {
            ColorInterpolator.lerpAngleDeg(seg.from, seg.to, eased)
        } else {
            seg.from + (seg.to - seg.from) * eased
        }
    }

    private fun activeSegment(
        segments: List<VectorAnimation.Segment>,
        p: Float,
    ): VectorAnimation.Segment? = selectSegment(segments, p, { it.start }, { it.end })

    private fun activeSegment(
        segments: List<VectorAnimation.BrushSegment>,
        p: Float,
    ): VectorAnimation.BrushSegment? = selectSegment(segments, p, { it.start }, { it.end })

    private inline fun <T> selectSegment(
        segments: List<T>,
        p: Float,
        start: (T) -> Float,
        end: (T) -> Float,
    ): T? {
        if (segments.isEmpty()) return null
        segments.lastOrNull { p >= start(it) && p <= end(it) }?.let { return it }
        segments.lastOrNull { p > end(it) }?.let { return it }
        return segments.first()
    }

    private fun segmentLocalT(start: Float, end: Float, p: Float): Float {
        if (end <= start) return 0f
        return ((p - start) / (end - start)).coerceIn(0f, 1f)
    }

    companion object {
        private val TRANSFORM_PROPS = setOf(
            PropKey.SCALE_X, PropKey.SCALE_Y, PropKey.ROTATION_DEG,
            PropKey.TRANSLATION_X, PropKey.TRANSLATION_Y, PropKey.PIVOT_X, PropKey.PIVOT_Y,
        )
        /**
         * Resolves the effective morph fraction for pair [pairIndex]: an explicit
         * MORPH_PROGRESS override if present, otherwise the global progress.
         */
        fun morphFraction(props: FloatArray, globalProgress: Float): Float {
            val override = props[PropKey.MORPH_PROGRESS.ordinal]
            return if (override.isNaN()) globalProgress else override
        }

        /** Resolves color interpolation space for a pair (used by the renderer). */
        fun pairSpace(config: ColorInterpolationSpace): ColorInterpolationSpace = config

        internal fun angleLerp(from: Float, to: Float, t: Float): Float =
            ColorInterpolator.lerpAngleDeg(from, to, t)
    }
}
