package com.imorpher.vectormorph.core.animation

import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.FillMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Build-time fill-direction inheritance (plan C5): a fill recorded without an explicit
 * direction adopts the travel direction of its target's first stroke request at build
 * time instead of baking a contradicting default. Orphan fills keep `fillDirection = null`
 * so the renderer resolves through `MorphConfiguration.defaultFillDirection` (last fallback).
 */
class FillDirectionInheritanceTest {

    private fun firstFillDirectionOf(animation: VectorAnimation): DrawDirection? =
        animation.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.first().draw!!.fillDirection

    // ------------------------------------------------------------------ plan contract

    @Test
    fun perPathFillInheritsStrokeDirection() {
        val anim = VectorAnimation {
            path("outline") { strokeReveal(direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..0.5f) }
            path("outline") { fill(mode = FillMode.DIRECTIONAL, interval = 0.4f..1f) }   // direction omitted
        }
        val fillTrack = anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }
        assertEquals(DrawDirection.BOTTOM_TO_TOP, fillTrack.segments.first().draw!!.fillDirection)
    }

    @Test
    fun orphanFillStaysNullForRendererFallback() {
        val anim = VectorAnimation { allPaths { fill(mode = FillMode.DIRECTIONAL) } }
        assertNull(anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.first().draw!!.fillDirection)
    }

    // ------------------------------------------------------------------ derived contract pins

    @Test
    fun fillDeclaredBeforeItsStrokeStillInherits() {
        // The rule is declaration order of the stroke request, not relative fill position.
        val anim = VectorAnimation {
            path("outline") { fill(mode = FillMode.DIRECTIONAL) }
            path("outline") { strokeReveal(direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..0.5f) }
        }
        assertEquals(DrawDirection.BOTTOM_TO_TOP, firstFillDirectionOf(anim))
    }

    @Test
    fun explicitFillDirectionIsNeverOverridden() {
        val anim = VectorAnimation {
            path("outline") { strokeReveal(direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..0.5f) }
            path("outline") { fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.TOP_TO_BOTTOM) }
        }
        assertEquals(DrawDirection.TOP_TO_BOTTOM, firstFillDirectionOf(anim))
    }

    @Test
    fun fillDoesNotInheritAcrossDifferentPathTargets() {
        val anim = VectorAnimation {
            path("a") { strokeReveal(direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..0.5f) }
            path("b") { fill(mode = FillMode.DIRECTIONAL) }   // target differs → no stroke source
        }
        assertNull(firstFillDirectionOf(anim))
    }

    @Test
    fun vectorLevelFillInheritsFromVectorLevelStroke() {
        val anim = VectorAnimation {
            allPaths { drawProgress(direction = DrawDirection.BOTTOM_TO_TOP) }
            allPaths { fill(mode = FillMode.DIRECTIONAL) }   // AllPaths↔AllPaths target
        }
        assertEquals(DrawDirection.BOTTOM_TO_TOP, firstFillDirectionOf(anim))
    }
}
