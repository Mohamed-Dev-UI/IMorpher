package com.imorpher.vectormorph.core.matching

/**
 * Minimum-cost one-to-one assignment (Kuhn–Munkres / Hungarian algorithm with potentials).
 *
 * Greedy cheapest-edge matching is locally optimal only: it can leave an expensive pairing behind
 * whenever an early cheap edge blocks two moderately cheap ones. Path and contour matching both
 * use this solver for small problems, where O(n³) is exact and negligible, and fall back to
 * greedy above [MAX_OPTIMAL_SIZE].
 */
internal object Assignment {

    /** Largest matrix side solved exactly; above this the cubic cost outweighs the quality win. */
    const val MAX_OPTIMAL_SIZE = 32

    /**
     * Solves the rectangular assignment problem for [cost] (rows × cols, smaller side fully
     * matched). Returns `row → col`, with `-1` for rows that could not be matched (only possible
     * when the expensive fallback path is used; this solver always matches `min(rows, cols)`).
     */
    fun solve(cost: Array<FloatArray>): IntArray {
        val rows = cost.size
        if (rows == 0) return IntArray(0)
        val cols = cost[0].size
        if (cols == 0) return IntArray(rows) { -1 }

        // The classic formulation requires rows <= cols; transpose when the matrix is tall.
        val transposed = rows > cols
        val n = if (transposed) cols else rows
        val m = if (transposed) rows else cols

        // 1-based potentials/assignment arrays for the row-minimisation form.
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)

        fun costAt(rowIndex: Int, colIndex: Int): Double =
            if (transposed) cost[colIndex][rowIndex].toDouble() else cost[rowIndex][colIndex].toDouble()

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.MAX_VALUE }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = 0
                for (j in 1..m) {
                    if (used[j]) continue
                    val current = costAt(i0 - 1, j - 1) - u[i0] - v[j]
                    if (current < minv[j]) {
                        minv[j] = current
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                if (j1 == 0) break // no augmenting column: numerically degenerate input
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val result = IntArray(rows) { -1 }
        for (j in 1..m) {
            val assignedRow = p[j]
            if (assignedRow == 0) continue
            val originalRow = if (transposed) j - 1 else assignedRow - 1
            val originalCol = if (transposed) assignedRow - 1 else j - 1
            result[originalRow] = originalCol
        }
        return result
    }
}
