package com.imorpher.vectormorph.core.matching

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exact assignment contract (review: greedy matching can pick a globally worse pairing). */
class AssignmentTest {

    @Test
    fun optimalPicksCheaperGlobalAssignment() {
        val cost = arrayOf(
            floatArrayOf(1f, 2f),
            floatArrayOf(1.5f, 10f),
        )

        val result = Assignment.solve(cost)

        // Greedy takes (0,0)=1 then is stuck with (1,1)=10 (total 11); the optimum is
        // (0,1)=2 + (1,0)=1.5 (total 3.5).
        assertArrayEquals(intArrayOf(1, 0), result)
    }

    @Test
    fun assignmentIsBijective() {
        val cost = arrayOf(
            floatArrayOf(4f, 1f, 8f),
            floatArrayOf(2f, 5f, 9f),
            floatArrayOf(7f, 3f, 6f),
        )

        val result = Assignment.solve(cost)

        assertEquals(3, result.size)
        assertEquals(3, result.toSet().size)
    }

    @Test
    fun tallMatrixMatchesEveryRowAtMostOnce() {
        val cost = arrayOf(
            floatArrayOf(3f, 1f),
            floatArrayOf(1f, 3f),
            floatArrayOf(2f, 2f),
        )

        val result = Assignment.solve(cost)

        assertEquals(3, result.size)
        val used = result.filter { it >= 0 }
        assertEquals(2, used.size)
        assertEquals(2, used.toSet().size)
    }

    @Test
    fun wideMatrixMatchesEveryColumn() {
        val cost = arrayOf(
            floatArrayOf(3f, 1f, 4f),
            floatArrayOf(1f, 3f, 9f),
        )

        val result = Assignment.solve(cost)

        assertEquals(2, result.size)
        assertEquals(setOf(0, 1), result.toSet())
    }

    @Test
    fun emptyInputsAreHandled() {
        assertEquals(0, Assignment.solve(emptyArray()).size)
        assertEquals(1, Assignment.solve(arrayOf(FloatArray(0))).size)
        assertEquals(-1, Assignment.solve(arrayOf(FloatArray(0)))[0])
    }

    @Test
    fun singleCellMatches() {
        assertArrayEquals(intArrayOf(0), Assignment.solve(arrayOf(floatArrayOf(5f))))
    }

    @Test
    fun maxOptimalSizeIsDocumentedBoundary() {
        assertEquals(32, Assignment.MAX_OPTIMAL_SIZE)
    }
}
