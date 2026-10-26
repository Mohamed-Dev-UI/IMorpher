package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [VectorAnimation.stableKey] contract: two builds from identical DSL calls return the
 * same key; any track/segment/payload difference changes it. Customizer callbacks and
 * brush (custom) identity fold as identity — unequal across processes by design.
 */
class AnimationStableKeyTest {

    private fun reveal(
        direction: DrawDirection = DrawDirection.LEFT_TO_RIGHT,
        start: PathStart = PathStart.AUTO,
        interval: ClosedFloatingPointRange<Float> = 0f..1f,
    ): VectorAnimation = VectorAnimation {
        defaultEasing(LinearEasing)
        allPaths {
            drawProgress(
                from = 0f, to = 1f, interval = interval,
                mode = DrawMode.FORWARD, start = start, direction = direction,
            )
        }
    }

    @Test
    fun identicalDslBuildsShareAStableKey() {
        assertEquals(reveal().stableKey(), reveal().stableKey())
        assertEquals(reveal().stableKey(), reveal().stableKey())
    }

    @Test
    fun changedIntervalChangesTheKey() {
        assertNotEquals(reveal(interval = 0f..1f).stableKey(), reveal(interval = 0f..0.5f).stableKey())
    }

    @Test
    fun changedRevealPayloadChangesTheKey() {
        assertNotEquals(
            reveal(direction = DrawDirection.LEFT_TO_RIGHT).stableKey(),
            reveal(direction = DrawDirection.RIGHT_TO_LEFT).stableKey(),
        )
        assertNotEquals(
            reveal(start = PathStart.AUTO).stableKey(),
            reveal(start = PathStart.CUSTOM).stableKey(),
        )
    }

    @Test
    fun changedPropertyOrTargetChangesTheKey() {
        val geometry = reveal()
        val alpha = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
        }
        assertNotEquals(geometry.stableKey(), alpha.stableKey())

        val named = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { alpha(0f, 1f) }
        }
        val renamed = VectorAnimation {
            defaultEasing(LinearEasing)
            path("ghost") { alpha(0f, 1f) }
        }
        assertNotEquals(named.stableKey(), renamed.stableKey())
    }

    @Test
    fun changedEasingChangesTheKey() {
        val linear = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f, interval = 0f..1f) }
        }
        val fastOut = VectorAnimation {
            defaultEasing(androidx.compose.animation.core.FastOutSlowInEasing)
            allPaths { alpha(0f, 1f, interval = 0f..1f) }
        }
        assertNotEquals(linear.stableKey(), fastOut.stableKey())
    }

    @Test
    fun brushTracksFoldStructurally() {
        fun build(color: Long): VectorAnimation = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths {
                gradient(
                    from = BrushSpec.Solid(Color(color)),
                    to = BrushSpec.Solid(Color(0xFF442200)),
                    interval = 0f..1f,
                )
            }
        }
        assertEquals(build(0xFF112233).stableKey(), build(0xFF112233).stableKey())
        assertNotEquals(build(0xFF112233).stableKey(), build(0xFF112244).stableKey())
    }

    @Test
    fun staggerPayloadChangesTheKey() {
        fun build(delay: Float): VectorAnimation = VectorAnimation {
            defaultEasing(LinearEasing)
            stagger(delay = delay, order = DrawOrderStrategy.BOTTOM_RIGHT_TO_TOP_LEFT)
        }
        assertNotEquals(build(0.1f).stableKey(), build(0.2f).stableKey())
        assertEquals(build(0.1f).stableKey(), build(0.1f).stableKey())
    }

    /** Degenerate drops must not leak into the key: equal tracks fold equal. */
    @Test
    fun droppedDegenerateRequestsAreInvisibleToTheKey() {
        val withDrop = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths {
                drawProgress(from = 0f, to = 1f, interval = 0.5f..0.25f) // dropped
                alpha(0f, 1f)
            }
        }
        val without = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { alpha(0f, 1f) }
        }
        assertEquals(withDrop.stableKey(), without.stableKey())
    }

    @Test
    fun customizerPresenceChangesTheKey() {
        val custom = VectorAnimation {
            defaultEasing(LinearEasing)
            customAnimation { null }
        }
        val plain = VectorAnimation {
            defaultEasing(LinearEasing)
        }
        assertNotEquals(custom.stableKey(), plain.stableKey())
    }
}
