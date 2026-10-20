package com.imorpher.vectormorph.core.reveal

import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for [DirectionMath.resolve] — start/direction/mode precedence. */
class DirectionResolverTest {

    // ------------------------------------------------- plan-pinned resolution rules

    @Test
    fun autoStartDerivesAnchorFromDirection() {
        for (direction in DrawDirection.entries - DrawDirection.CUSTOM - DrawDirection.AUTO) {
            assertEquals(
                DirectionMath.anchorPathStartOf(direction),
                DirectionMath.resolve(PathStart.AUTO, direction, DrawMode.FORWARD).anchor,
            )
        }
        assertEquals(PathStart.TOP_LEFT, DirectionMath.resolve(PathStart.AUTO, DrawDirection.AUTO, DrawMode.FORWARD).anchor)
    }

    @Test
    fun explicitStartWinsWithWarning() {
        val r = DirectionMath.resolve(PathStart.TOP, DrawDirection.LEFT_TO_RIGHT, DrawMode.FORWARD)
        assertEquals(PathStart.TOP, r.anchor)
        assertTrue(DrawResolutionWarning.START_OVERRIDES_DIRECTION in r.warnings)
    }

    @Test
    fun startNeverRemainsAuto() {
        assertTrue(DrawDirection.entries.none { DirectionMath.resolve(PathStart.START, it, DrawMode.FORWARD).anchor == PathStart.AUTO })
    }

    @Test
    fun outsideInWarnsAnchorIgnored() {
        assertTrue(
            DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE
                in DirectionMath.resolve(PathStart.CENTER, DrawDirection.LEFT_TO_RIGHT, DrawMode.OUTSIDE_IN).warnings,
        )
    }

    @Test
    fun centerOutDirectionWarnsOnStroke() {
        assertTrue(
            DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE
                in DirectionMath.resolve(PathStart.AUTO, DrawDirection.CENTER_OUT, DrawMode.FORWARD).warnings,
        )
    }

    // ------------------------------------------------- extracted renderer table

    @Test
    fun anchorPathStartOfMatchesTheRendererTable() {
        assertEquals(PathStart.TOP_LEFT, DirectionMath.anchorPathStartOf(DrawDirection.AUTO))
        assertEquals(PathStart.TOP_LEFT, DirectionMath.anchorPathStartOf(DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT))
        assertEquals(PathStart.TOP_RIGHT, DirectionMath.anchorPathStartOf(DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT))
        assertEquals(PathStart.BOTTOM_LEFT, DirectionMath.anchorPathStartOf(DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT))
        assertEquals(PathStart.BOTTOM_RIGHT, DirectionMath.anchorPathStartOf(DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT))
        assertEquals(PathStart.LEFT, DirectionMath.anchorPathStartOf(DrawDirection.LEFT_TO_RIGHT))
        assertEquals(PathStart.RIGHT, DirectionMath.anchorPathStartOf(DrawDirection.RIGHT_TO_LEFT))
        assertEquals(PathStart.TOP, DirectionMath.anchorPathStartOf(DrawDirection.TOP_TO_BOTTOM))
        assertEquals(PathStart.BOTTOM, DirectionMath.anchorPathStartOf(DrawDirection.BOTTOM_TO_TOP))
        assertEquals(PathStart.TOP, DirectionMath.anchorPathStartOf(DrawDirection.CLOCKWISE))
        assertEquals(PathStart.TOP, DirectionMath.anchorPathStartOf(DrawDirection.COUNTER_CLOCKWISE))
        assertEquals(PathStart.START, DirectionMath.anchorPathStartOf(DrawDirection.CENTER_OUT))
        assertEquals(PathStart.START, DirectionMath.anchorPathStartOf(DrawDirection.OUTSIDE_IN))
        assertEquals(PathStart.START, DirectionMath.anchorPathStartOf(DrawDirection.CUSTOM))
    }

    // ------------------------------------------------- fixed contract: CUSTOM direction

    @Test
    fun customDirectionHasNoConcreteAnchorAndDefaultsToTopLeft() {
        val r = DirectionMath.resolve(PathStart.AUTO, DrawDirection.CUSTOM, DrawMode.FORWARD)
        assertEquals(PathStart.TOP_LEFT, r.anchor)
        assertEquals(listOf(DrawResolutionWarning.MISSING_CONCRETE_ANCHOR), r.warnings)
    }

    @Test
    fun customDirectionDoesNotConflictWithExplicitStart() {
        // start supplies the concrete anchor; a CUSTOM direction claims nothing to contradict it.
        val explicit = DirectionMath.resolve(PathStart.BOTTOM, DrawDirection.CUSTOM, DrawMode.FORWARD)
        assertEquals(PathStart.BOTTOM, explicit.anchor)
        assertEquals(emptyList<DrawResolutionWarning>(), explicit.warnings)
        assertEquals(PathStart.START, DirectionMath.resolve(PathStart.START, DrawDirection.CUSTOM, DrawMode.FORWARD).anchor)
    }

    // ------------------------------------------------- fixed contract: CUSTOM mode

    @Test
    fun customModeResolvesLikeForward() {
        val forward = DirectionMath.resolve(PathStart.TOP, DrawDirection.LEFT_TO_RIGHT, DrawMode.FORWARD)
        val custom = DirectionMath.resolve(PathStart.TOP, DrawDirection.LEFT_TO_RIGHT, DrawMode.CUSTOM)
        assertEquals(forward, custom)
        assertEquals(PathStart.TOP_LEFT, DirectionMath.resolve(PathStart.AUTO, DrawDirection.AUTO, DrawMode.CUSTOM).anchor)
    }

    // ------------------------------------------------- START_OVERRIDES_DIRECTION is not unconditional

    @Test
    fun matchingAnchorProducesNoWarnings() {
        for (direction in DrawDirection.entries - DrawDirection.CUSTOM - DrawDirection.CENTER_OUT - DrawDirection.OUTSIDE_IN) {
            val r = DirectionMath.resolve(DirectionMath.anchorPathStartOf(direction), direction, DrawMode.FORWARD)
            assertTrue("expected no warnings for $direction, got ${r.warnings}", r.warnings.isEmpty())
        }
        // sweep directions must not add a stray START_OVERRIDES_DIRECTION on top of the sweep warning
        assertEquals(
            listOf(DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE),
            DirectionMath.resolve(DirectionMath.anchorPathStartOf(DrawDirection.CENTER_OUT), DrawDirection.CENTER_OUT, DrawMode.FORWARD).warnings,
        )
        assertEquals(
            listOf(DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE),
            DirectionMath.resolve(DirectionMath.anchorPathStartOf(DrawDirection.OUTSIDE_IN), DrawDirection.OUTSIDE_IN, DrawMode.FORWARD).warnings,
        )
    }

    @Test
    fun derivedAnchorsAreNeverAuto() {
        assertTrue(
            DrawDirection.entries.none {
                DirectionMath.resolve(PathStart.AUTO, it, DrawMode.FORWARD).anchor == PathStart.AUTO
            },
        )
    }
}
