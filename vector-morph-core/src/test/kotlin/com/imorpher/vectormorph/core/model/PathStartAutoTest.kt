package com.imorpher.vectormorph.core.model

import com.imorpher.vectormorph.core.animation.VectorAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1 of the start/direction coordination plan: `PathStart.AUTO` is the sentinel meaning
 * "derive the anchor from [DrawDirection]" — it must exist as a genuine, distinct constant and
 * be the new default of `VectorAnimation.DrawConfig`.
 */
class PathStartAutoTest {

    @Test
    fun startAutoIsAGenuineConstantDistinctFromStart() {
        assertTrue(PathStart.entries.contains(PathStart.AUTO))
        assertNotEquals(PathStart.AUTO, PathStart.START)
    }

    @Test
    fun drawConfigDefaultsToAuto() {
        assertEquals(PathStart.AUTO, VectorAnimation.DrawConfig().start)
    }
}
