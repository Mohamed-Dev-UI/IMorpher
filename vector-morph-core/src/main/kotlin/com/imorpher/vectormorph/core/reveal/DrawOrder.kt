package com.imorpher.vectormorph.core.reveal

import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.PairKind
import kotlin.math.atan2

/**
 * Computes the order in which paths reveal/draw and the stagger offsets between them.
 * Order affects only *timing* (which path starts drawing first); rendering order stays
 * the vector's declaration order unless
 * [com.imorpher.vectormorph.core.model.MorphConfiguration.keepRenderOrder] is false.
 */
object DrawOrder {

    /** Returns pair indices in reveal order. */
    fun orderIndices(plan: MorphPlan, strategy: DrawOrderStrategy, customOrder: List<String>): IntArray {
        val pairs = plan.pairs
        val idx = IntArray(pairs.size) { it }
        when (strategy) {
            DrawOrderStrategy.BY_INDEX -> Unit
            DrawOrderStrategy.BY_NAME -> {
                // BY_NAME derives its ranks from the pair names alone: customDrawOrder is
                // reserved for CUSTOM. Letting the list reorder BY_NAME made the two
                // strategies indistinguishable when any list was supplied.
                val order = pairs.mapNotNull { pathOf(it)?.name }.sorted()
                sortByNames(idx, pairs, order)
            }
            DrawOrderStrategy.BY_POSITION,
            DrawOrderStrategy.AUTO,
            DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
            -> sortByCorner(idx, pairs) { p -> p.bounds[0] + p.bounds[1] } // min x + min y
            DrawOrderStrategy.TOP_RIGHT_TO_BOTTOM_LEFT ->
                sortByCorner(idx, pairs) { p -> -p.bounds[2] + p.bounds[1] } // max x desc + min y
            DrawOrderStrategy.BOTTOM_LEFT_TO_TOP_RIGHT ->
                sortByCorner(idx, pairs) { p -> p.bounds[0] - p.bounds[3] }
            DrawOrderStrategy.BOTTOM_RIGHT_TO_TOP_LEFT ->
                sortByCorner(idx, pairs) { p -> -p.bounds[2] - p.bounds[3] }
            DrawOrderStrategy.CLOCKWISE -> sortByAngle(idx, plan, clockwise = true)
            DrawOrderStrategy.COUNTER_CLOCKWISE -> sortByAngle(idx, plan, clockwise = false)
            DrawOrderStrategy.CUSTOM -> sortByNames(idx, pairs, customOrder)
        }
        return idx
    }

    /** The path whose geometry drives this pair's ordering (target preferred). */
    private fun pathOf(pair: com.imorpher.vectormorph.core.plan.PathPairPlan) =
        pair.toPath ?: pair.fromPath

    /** Stable sort of [idx] by a scalar corner key computed from each pair's representative path. */
    private fun sortByCorner(
        idx: IntArray,
        pairs: List<com.imorpher.vectormorph.core.plan.PathPairPlan>,
        key: (com.imorpher.vectormorph.core.model.PreparedPath) -> Float,
    ) {
        val keyed = idx.sortedBy { i -> pairs[i].let { pathOf(it) }?.let(key) ?: Float.MAX_VALUE }
        keyed.forEachIndexed { o, v -> idx[o] = v }
    }

    /** Stable sort of [idx] by each pair's rank in the [order] list (unknown names last). */
    private fun sortByNames(
        idx: IntArray,
        pairs: List<com.imorpher.vectormorph.core.plan.PathPairPlan>,
        order: List<String>,
    ) {
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { i, n -> rank[n] = i }
        val keyed = idx.sortedBy { i ->
            val p = pairs[i]
            val name = p.toPath?.name ?: p.fromPath?.name
            name?.let { rank[it] } ?: Int.MAX_VALUE
        }
        keyed.forEachIndexed { o, v -> idx[o] = v }
    }

    /**
     * Stable sort of [idx] by each pair's polar angle around the viewport center; ascending
     * atan2 in Y-down space walks the screen clockwise.
     */
    private fun sortByAngle(idx: IntArray, plan: MorphPlan, clockwise: Boolean) {
        val cx = plan.maxViewportWidth / 2f
        val cy = plan.maxViewportHeight / 2f
        val keyed = idx.sortedBy { i ->
            val p = pathOf(plan.pairs[i]) ?: return@sortedBy Float.MAX_VALUE
            var a = atan2(p.centroidY - cy, p.centroidX - cx)
            if (!clockwise) a = -a
            a
        }
        keyed.forEachIndexed { o, v -> idx[o] = v }
    }

    /**
     * Stagger delays per pair: `pairDelay = orderRank * staggerDelay`, squeezed proportionally
     * so the last path still starts within the timeline (`max offset ≤ 0.95`): when the raw
     * schedule overruns, every offset scales by `0.95 / maxOffset`, preserving the relative
     * rank spacing instead of clamping collinear starts onto one instant.
     */
    fun staggerOffsets(
        plan: MorphPlan,
        order: IntArray,
        staggerDelay: Float,
    ): FloatArray {
        val offsets = FloatArray(plan.pairs.size)
        if (staggerDelay <= 0f) return offsets
        val rankOf = IntArray(plan.pairs.size)
        order.forEachIndexed { rank, pairIndex -> rankOf[pairIndex] = rank }
        var maxOffset = 0f
        for (i in offsets.indices) {
            offsets[i] = staggerDelay * rankOf[i]
            if (offsets[i] > maxOffset) maxOffset = offsets[i]
        }
        if (maxOffset > 0.95f) {
            val scale = 0.95f / maxOffset
            for (i in offsets.indices) offsets[i] *= scale
        }
        return offsets
    }
}

/**
 * Direction math for reveals: maps a [com.imorpher.vectormorph.core.model.DrawDirection] onto
 * concrete vector geometry (gradient axis, sweep sign, start point).
 */
object DirectionMath {

    /** Returns (startX, startY, endX, endY) in normalized 0..1 bounds space for linear/directional fills. */
    fun axisFor(
        direction: com.imorpher.vectormorph.core.model.DrawDirection,
        autoFallback: com.imorpher.vectormorph.core.model.DrawDirection =
            com.imorpher.vectormorph.core.model.DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
    ): FloatArray = when (direction) {
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT -> floatArrayOf(0f, 0f, 1f, 1f)
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT -> floatArrayOf(1f, 0f, 0f, 1f)
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT -> floatArrayOf(0f, 1f, 1f, 0f)
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT -> floatArrayOf(1f, 1f, 0f, 0f)
        com.imorpher.vectormorph.core.model.DrawDirection.LEFT_TO_RIGHT -> floatArrayOf(0f, 0.5f, 1f, 0.5f)
        com.imorpher.vectormorph.core.model.DrawDirection.RIGHT_TO_LEFT -> floatArrayOf(1f, 0.5f, 0f, 0.5f)
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_TO_BOTTOM -> floatArrayOf(0.5f, 0f, 0.5f, 1f)
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_TO_TOP -> floatArrayOf(0.5f, 1f, 0.5f, 0f)
        else -> axisFor(autoFallback, autoFallback) // AUTO/center/etc.
    }

    /** Start-point anchor for a draw direction in bounds space (where the "pen" begins). */
    fun anchorFor(direction: com.imorpher.vectormorph.core.model.DrawDirection): FloatArray =
        axisFor(direction).let { floatArrayOf(it[0], it[1]) }

    /**
     * Arc start angle (degrees, Android `arcTo` convention) for a `FillMode.SWEEP` reveal:
     * CLOCKWISE/COUNTER_CLOCKWISE spin from the top, every other direction starts at the
     * angle its [anchorFor] anchor sits at (atan2 in Y-down space walks the screen
     * clockwise). Both sweep sites — the renderer's fill mask and the clip pass's leg
     * masks — share this so one direction renders the same wedge wherever a fill sweeps.
     */
    fun sweepStartAngle(direction: com.imorpher.vectormorph.core.model.DrawDirection): Float = when (direction) {
        com.imorpher.vectormorph.core.model.DrawDirection.CLOCKWISE,
        com.imorpher.vectormorph.core.model.DrawDirection.COUNTER_CLOCKWISE,
        -> -90f
        else -> {
            val anchor = anchorFor(direction)
            Math.toDegrees(atan2(anchor[1] - 0.5, anchor[0] - 0.5)).toFloat()
        }
    }

    /** Whether an AUTO direction should be treated as diagonal travel. */
    fun isDiagonal(direction: com.imorpher.vectormorph.core.model.DrawDirection): Boolean = when (direction) {
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT,
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT,
        -> true
        else -> false
    }

    /**
     * Start-point anchor implied by a [DrawDirection]: where the "pen" begins when the
     * caller supplies no explicit anchor (`PathStart.AUTO`). Authority is intentionally
     * narrow — only a *derived* anchor routes through here:
     *
     * - [PathStart.AUTO] means "derive from `direction`": corners/edges map to their
     *   travel-origin anchor, `CLOCKWISE`/`COUNTER_CLOCKWISE` anchor at [PathStart.TOP],
     *   and a direction of `AUTO` yields [PathStart.TOP_LEFT].
     * - An explicit [PathStart.START] is a concrete anchor of its own (literal arc
     *   position 0 of the normalized contour; the renderer returns that arc fraction
     *   directly) and never re-anchors through this table.
     * - Directions without a travel origin (`CENTER_OUT`, `OUTSIDE_IN`, `CUSTOM`) have
     *   nothing to derive, so they fall through to [PathStart.START]; `CUSTOM` combined
     *   with an AUTO start additionally warns via `DirectionMath.resolve`
     *   ([DrawResolutionWarning.MISSING_CONCRETE_ANCHOR]).
     *
     * Extracted verbatim from the renderer's former `resolveStartFraction` table so the
     * literal semantics survive.
     */
    fun anchorPathStartOf(direction: DrawDirection): PathStart = when (direction) {
        DrawDirection.AUTO -> PathStart.TOP_LEFT
        DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT -> PathStart.TOP_RIGHT
        DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT -> PathStart.BOTTOM_LEFT
        DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT -> PathStart.BOTTOM_RIGHT
        DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT -> PathStart.TOP_LEFT
        DrawDirection.RIGHT_TO_LEFT -> PathStart.RIGHT
        DrawDirection.LEFT_TO_RIGHT -> PathStart.LEFT
        DrawDirection.TOP_TO_BOTTOM -> PathStart.TOP
        DrawDirection.BOTTOM_TO_TOP -> PathStart.BOTTOM
        DrawDirection.CLOCKWISE, DrawDirection.COUNTER_CLOCKWISE -> PathStart.TOP
        else -> PathStart.START
    }

    /**
     * Whether the direction claims a concrete **travel axis** — the four corner diagonals
     * and the four edge axes — whose orientation a traversal can honor. This is the gate
     * for `MorphConfiguration.directionTraversal == HONOR_DIRECTION`: only axis-claiming
     * directions can disagree with a contour's point order in a way traversal can fix.
     *
     * Deliberately excluded: `CLOCKWISE`/`COUNTER_CLOCKWISE` (their contour-order flip is
     * the renderer's winding reversal, not a travel axis), and `AUTO`/`CENTER_OUT`/
     * `OUTSIDE_IN`/`CUSTOM` which claim no concrete axis at all.
     */
    fun isSpatial(direction: DrawDirection): Boolean = when (direction) {
        DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
        DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
        DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT,
        DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT,
        DrawDirection.LEFT_TO_RIGHT,
        DrawDirection.RIGHT_TO_LEFT,
        DrawDirection.TOP_TO_BOTTOM,
        DrawDirection.BOTTOM_TO_TOP,
        -> true
        else -> false
    }

    /**
     * Directions that claim a concrete anchor (the axis directions plus both windings,
     * which anchor at `PathStart.TOP`). Built on [isSpatial]: windings carry an anchor
     * claim without carrying a travel axis.
     */
    private fun isSpatialDirection(direction: DrawDirection): Boolean =
        isSpatial(direction) ||
            direction == DrawDirection.CLOCKWISE ||
            direction == DrawDirection.COUNTER_CLOCKWISE

    /**
     * Resolves `start`, `direction`, and `mode` into one concrete anchor plus diagnostics.
     * Pure: no path measurement, deterministic warnings in enum-declaration order.
     *
     * Precedence contract (no silent arbitration):
     * 1. `mode == DrawMode.CUSTOM` resolves like `DrawMode.FORWARD` — the renderer rejects
     *    custom draw modes separately; the resolver only anchors them.
     * 2. Explicit `start != PathStart.AUTO` always owns the anchor. When the direction would
     *    travel from a *different* anchor and is spatial (corner/axis/`CLOCKWISE`/
     *    `COUNTER_CLOCKWISE`), [DrawResolutionWarning.START_OVERRIDES_DIRECTION] is recorded.
     *    A spatial direction is required: a contradicting claim only means something if the
     *    direction carries an anchor at all (e.g. `DrawDirection.AUTO` claims nothing).
     * 3. `start == PathStart.AUTO` derives the anchor from `anchorPathStartOf` (direction
     *    `AUTO` → [PathStart.TOP_LEFT]). `DrawDirection.CUSTOM` has no concrete anchor to
     *    derive, so it emits [DrawResolutionWarning.MISSING_CONCRETE_ANCHOR] and falls back
     *    to the documented default [PathStart.TOP_LEFT]; with an explicit start that warning
     *    never fires because start already supplies the concrete anchor.
     * 4. `mode == DrawMode.OUTSIDE_IN` ignores the anchor entirely — [DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE].
     * 5. `direction == DrawDirection.CENTER_OUT || DrawDirection.OUTSIDE_IN` is only
     *    meaningful for fill sweeps; stroke callers surface [DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE].
     */
    fun resolve(start: PathStart, direction: DrawDirection, mode: DrawMode): ResolvedDraw {
        // Custom draw modes carry no anchoring semantics of their own; anchor like FORWARD.
        val effectiveMode = if (mode == DrawMode.CUSTOM) DrawMode.FORWARD else mode
        var startOverrides = false
        var missingConcreteAnchor = false
        val anchor: PathStart = when {
            start != PathStart.AUTO -> {
                startOverrides = isSpatialDirection(direction) && start != anchorPathStartOf(direction)
                start
            }
            direction == DrawDirection.CUSTOM -> { missingConcreteAnchor = true; PathStart.TOP_LEFT }
            else -> anchorPathStartOf(direction)
        }
        val warnings = mutableListOf<DrawResolutionWarning>()
        if (startOverrides) warnings += DrawResolutionWarning.START_OVERRIDES_DIRECTION
        if (effectiveMode == DrawMode.OUTSIDE_IN) warnings += DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE
        if (direction == DrawDirection.CENTER_OUT || direction == DrawDirection.OUTSIDE_IN) {
            warnings += DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE
        }
        if (missingConcreteAnchor) warnings += DrawResolutionWarning.MISSING_CONCRETE_ANCHOR
        return ResolvedDraw(anchor, warnings)
    }
}

/** Warning categories emitted when start/direction/mode contradict. */
enum class DrawResolutionWarning {
    /** An explicit [PathStart] replaced the anchor the [DrawDirection] would have implied. */
    START_OVERRIDES_DIRECTION,
    /** The resolved draw mode ignores the anchor entirely (e.g. `DrawMode.OUTSIDE_IN`). */
    ANCHOR_IGNORED_BY_MODE,
    /** A sweep-only direction was used where only a stroke reveal can honor it. */
    CENTER_OUT_DIRECTION_ON_STROKE,
    /** The direction supplied no concrete anchor to derive (e.g. `DrawDirection.CUSTOM`). */
    MISSING_CONCRETE_ANCHOR,

    /** A `CustomVectorAnimation` callback threw and has been disabled for this renderer. */
    CUSTOM_ANIMATION_FAILED,
}

/**
 * One resolved anchor decision. [anchor] is never [PathStart.AUTO]; warnings are
 * deterministic (enum-declaration order) and empty when nothing contradicts.
 */
data class ResolvedDraw(
    val anchor: PathStart,
    val warnings: List<DrawResolutionWarning>,
)
