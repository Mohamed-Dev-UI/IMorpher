package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.LinearEasing
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape pins for the per-property typed reveals (DrawConfig structural cleanup): every
 * reveal property compiles exactly the variant its renderer consumes, and fill tracks no
 * longer carry the stroke-only mode/start/direction fields.
 */
class RevealTypeShapeTest {

    @Test
    fun fillCompilesAFillReveal() {
        val anim = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.BOTTOM_TO_TOP) }
        }
        val reveal = anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.single().reveal
        assertTrue(reveal is FillRevealConfig)
        assertEquals(FillMode.DIRECTIONAL, (reveal as FillRevealConfig).fillMode)
    }

    @Test
    fun strokeRevealCompilesAStrokeConfig() {
        val anim = VectorAnimation {
            defaultEasing(LinearEasing)
            path("p") { strokeReveal(start = PathStart.TOP, direction = DrawDirection.BOTTOM_TO_TOP) }
        }
        val reveal = anim.tracks.first { it.prop == PropKey.DRAW_PROGRESS }.segments.single().reveal
        assertTrue(reveal is DrawConfig && (reveal as DrawConfig).start == PathStart.TOP)
    }

    @Test
    fun clipRevealCompilesAClipConfig() {
        val anim = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { clipReveal(direction = DrawDirection.LEFT_TO_RIGHT) }
        }
        val reveal = anim.tracks.first { it.prop == PropKey.CLIP_PROGRESS }.segments.single().reveal
        assertTrue(reveal is ClipRevealConfig && (reveal as ClipRevealConfig).direction == DrawDirection.LEFT_TO_RIGHT)
    }

    @Test
    fun fillTrackCarriesNoStrokeFields() {
        // Structural assertion: the fill reveal exposes mode/start/direction through no
        // DrawConfig — encoded as the absence of a DrawConfig on the FILL track.
        val anim = VectorAnimation {
            defaultEasing(LinearEasing)
            allPaths { fill(mode = FillMode.DIRECTIONAL) }
        }
        val reveal = anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.single().reveal
        assertFalse(reveal is DrawConfig)
    }
}
