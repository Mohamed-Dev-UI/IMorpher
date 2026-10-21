package com.imorpher.vectormorph.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Task 4 probes (start/direction coordination plan rule 4): the traversal policy rides on
 * [MorphConfiguration] — `LEGACY` by default (today's contour-order behavior) — and must
 * survive data-class equality so `remember`-style cache keys stay sound.
 */
class DirectionTraversalConfigTest {

    @Test
    fun defaultIsLegacy() {
        assertEquals(DirectionTraversal.LEGACY, MorphConfiguration().directionTraversal)
        assertEquals(DirectionTraversal.LEGACY, MorphConfiguration.Default.directionTraversal)
    }

    @Test
    fun honorFlagSurvivesEquality() {
        val honored = MorphConfiguration(directionTraversal = DirectionTraversal.HONOR_DIRECTION)
        assertEquals(MorphConfiguration(), MorphConfiguration())
        assertEquals(honored, MorphConfiguration(directionTraversal = DirectionTraversal.HONOR_DIRECTION))
        assertNotEquals(MorphConfiguration(), honored)
        assertNotEquals(MorphConfiguration(), MorphConfiguration(staggerDelay = 0.2f))
    }
}
