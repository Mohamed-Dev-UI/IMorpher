package com.imorpher.vectormorph.core.reveal

import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.PairKind
import kotlin.math.atan2

/**
 * Computes the order in which paths reveal/draw and the stagger offsets between them.
 * Order affects only *timing* (which path starts drawing first); rendering order stays
 * the vector's declaration order unless [MorphConfiguration.keepRenderOrder] is false.
 */
object DrawOrder {

    /** Returns pair indices in reveal order. */
    fun orderIndices(plan: MorphPlan, strategy: DrawOrderStrategy, customOrder: List<String>): IntArray {
        val pairs = plan.pairs
        val idx = IntArray(pairs.size) { it }
        when (strategy) {
            DrawOrderStrategy.BY_INDEX -> Unit
            DrawOrderStrategy.BY_NAME -> {
                val order = customOrder.ifEmpty {
                    pairs.mapNotNull { p -> listOfNotNull(p.fromPath?.name, p.toPath?.name).firstOrNull() }.sorted()
                }
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

    private fun pathOf(pair: com.imorpher.vectormorph.core.plan.PathPairPlan) =
        pair.toPath ?: pair.fromPath

    private fun sortByCorner(
        idx: IntArray,
        pairs: List<com.imorpher.vectormorph.core.plan.PathPairPlan>,
        key: (com.imorpher.vectormorph.core.model.PreparedPath) -> Float,
    ) {
        val keyed = idx.sortedBy { i -> pairs[i].let { pathOf(it) }?.let(key) ?: Float.MAX_VALUE }
        keyed.forEachIndexed { o, v -> idx[o] = v }
    }

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
     * Stagger delays per pair: `pairDelay = orderRank * staggerDelay`, optionally squeezed so
     * the last path still starts within the timeline (`orderRank * delay ≤ 1 - minDuration`).
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
        for (i in offsets.indices) {
            offsets[i] = staggerDelay * rankOf[i]
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

    /** Whether an AUTO direction should be treated as diagonal travel. */
    fun isDiagonal(direction: com.imorpher.vectormorph.core.model.DrawDirection): Boolean = when (direction) {
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
        com.imorpher.vectormorph.core.model.DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT,
        com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT,
        -> true
        else -> false
    }
}
