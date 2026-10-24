package com.imorpher.vectormorph.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Task 4 probes (start/direction coordination plan rule 4): the traversal policy rides on
 * [MorphConfiguration] — `HONOR_DIRECTION` by default since 0.2.0 — and must survive
 * data-class equality so `remember`-style cache keys stay sound.
 */
class DirectionTraversalConfigTest {

    @Test
    fun defaultHonorsDirection() {
        assertEquals(DirectionTraversal.HONOR_DIRECTION, MorphConfiguration().directionTraversal)
        assertEquals(DirectionTraversal.HONOR_DIRECTION, MorphConfiguration.Default.directionTraversal)
    }

    @Test
    fun legacyRemainsOptIn() {
        val legacy = MorphConfiguration(directionTraversal = DirectionTraversal.LEGACY)
        assertNotEquals(MorphConfiguration(), legacy)
        assertEquals(DirectionTraversal.LEGACY, legacy.directionTraversal)
    }

    @Test
    fun honorFlagSurvivesEquality() {
        val honored = MorphConfiguration(directionTraversal = DirectionTraversal.HONOR_DIRECTION)
        val legacy = MorphConfiguration(directionTraversal = DirectionTraversal.LEGACY)
        assertEquals(MorphConfiguration(), honored)
        assertEquals(honored, MorphConfiguration(directionTraversal = DirectionTraversal.HONOR_DIRECTION))
        assertNotEquals(legacy, honored)
        assertNotEquals(MorphConfiguration(), MorphConfiguration(staggerDelay = 0.2f))
    }
}
