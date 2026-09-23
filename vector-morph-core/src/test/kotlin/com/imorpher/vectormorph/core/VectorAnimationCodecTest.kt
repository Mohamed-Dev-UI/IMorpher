package com.imorpher.vectormorph.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.animation.AnimationTarget
import com.imorpher.vectormorph.core.animation.PropKey
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimationCodec
import com.imorpher.vectormorph.core.gradients.linearGradientSpecAtAngle
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorAnimationCodecTest {
    @Test
    fun binaryRoundTripPreservesTracksBrushesAndRevealConfiguration() {
        val source = VectorAnimation {
            defaultEasing(LinearEasing)
            path("outline", occurrence = 1) {
                alpha(0f, 1f, 0.1f..0.8f)
                clipReveal(direction = com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT)
                strokeReveal(start = com.imorpher.vectormorph.core.model.PathStart.TOP_RIGHT)
            }
            group("body") { rotation(-20f, 30f) }
            gradient(
                linearGradientSpecAtAngle(listOf(Color.Red, Color.Blue), 350f),
                linearGradientSpecAtAngle(listOf(Color.Green, Color.White), 10f),
            )
        }

        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(source))

        assertEquals(source.tracks.size, decoded.tracks.size)
        assertEquals(source.brushTracks.size, decoded.brushTracks.size)
        val pathTrack = decoded.tracks.single { it.target == AnimationTarget.Path("outline", 1) && it.prop == PropKey.ALPHA }
        assertEquals(0.1f, pathTrack.segments.single().start, 0f)
        assertEquals(0.8f, pathTrack.segments.single().end, 0f)
        assertNotNull(decoded.drawConfigFor("outline[1]", null, 0, -1, PropKey.DRAW_PROGRESS))
        val linear = decoded.brushTracks.single().segments.first().from as com.imorpher.vectormorph.core.gradients.BrushSpec.Linear
        assertEquals(350f, linear.angleDegrees!!, 0f)
    }

    @Test
    fun codecRejectsRuntimeCallbacksAndInvalidHeaders() {
        val callbackAnimation = VectorAnimation { customAnimation { null } }
        assertThrows(IllegalArgumentException::class.java) { VectorAnimationCodec.encode(callbackAnimation) }
        assertThrows(IllegalArgumentException::class.java) { VectorAnimationCodec.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun angleGradientsTakeTheShortAngularRouteWithoutCollapsingTheirAxis() {
        val from = linearGradientSpecAtAngle(listOf(Color.Red, Color.Blue), 0f)
        val to = linearGradientSpecAtAngle(listOf(Color.Red, Color.Blue), 180f)
        val result = GradientInterpolator.interpolate(from, to, 0.5f, ColorInterpolationSpace.SRGB)
            as GradientInterpolator.Result.Interpolated
        val middle = com.imorpher.vectormorph.core.gradients.BrushSpec.from(result.brush)
            as com.imorpher.vectormorph.core.gradients.BrushSpec.Linear

        assertEquals(0f, middle.endX - middle.startX, 0.02f)
        assertEquals(1f, kotlin.math.abs(middle.endY - middle.startY), 0.02f)
    }
}
