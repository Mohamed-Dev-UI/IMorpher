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
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PathStart
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

    /**
     * Pin for the start/direction coordination contract (plan task 8, review focus 5):
     * `PathStart.AUTO` (the sentinel), an explicit `PathStart.START` (literal arc 0), and a
     * concrete **inherited** fill direction all survive the binary round trip. The codec
     * stores enums by name, so the decoded animation must resolve anchors and travel
     * identically to the encoded one — a START default must never re-anchor through the
     * direction table, and an inherited fillDirection must stay a real value, not revert
     * to null/AUTO. Comparison is field-based (compiled tracks/DrawConfigs), not pixels.
     */
    @Test
    fun startDirectionPrecedenceFieldsSurviveBinaryRoundTrip() {
        val source = VectorAnimation {
            // (a) `start = AUTO` is the sentinel: the anchor derives from `direction`
            //     at render time. AUTO itself must round-trip by name like every enum.
            path("autoAnchor") {
                strokeReveal(start = PathStart.AUTO, direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..0.5f)
            }
            // (b) fill() without a direction inherits the target's first explicitly
            //     declared stroke direction at build time; the concrete TOP_TO_BOTTOM
            //     must survive the codec's null-flag + name encoding as a REAL value.
            path("inheritedFill") {
                strokeReveal(direction = DrawDirection.TOP_TO_BOTTOM, interval = 0f..0.4f)
                fill(interval = 0.45f..0.9f)
            }
            // (c) START is a concrete anchor (literal arc position 0), independent of
            //     `direction`; it must not be re-anchored through the direction table.
            path("literalStart") {
                drawProgress(start = PathStart.START, interval = 0.2f..0.7f)
            }
        }

        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(source))

        // Scalars, intervals, targets, and properties survive the round trip verbatim.
        assertEquals(source.tracks.size, decoded.tracks.size)
        source.tracks.zip(decoded.tracks).forEach { (expected, actual) ->
            assertEquals(expected.target, actual.target)
            assertEquals(expected.prop, actual.prop)
            assertEquals(expected.segments.size, actual.segments.size)
            expected.segments.zip(actual.segments).forEach { (e, a) ->
                assertEquals(e.start, a.start, 0f)
                assertEquals(e.end, a.end, 0f)
                assertEquals(e.from, a.from, 0f)
                assertEquals(e.to, a.to, 0f)
            }
        }

        // (a) AUTO stays AUTO; the explicit BOTTOM_TO_TOP travel direction is preserved.
        val sourceAuto = drawConfigOf(source, "autoAnchor", PropKey.DRAW_PROGRESS)
        assertEquals(PathStart.AUTO, sourceAuto.start)
        assertEquals(DrawDirection.BOTTOM_TO_TOP, sourceAuto.direction)
        assertDrawConfigEquals(sourceAuto, drawConfigOf(decoded, "autoAnchor", PropKey.DRAW_PROGRESS))

        // (b) the inherited fill direction is concrete (not AUTO, not null).
        val sourceFill = drawConfigOf(source, "inheritedFill", PropKey.FILL_PROGRESS)
        assertEquals(FillMode.DIRECTIONAL, sourceFill.fillMode)
        assertEquals(DrawDirection.TOP_TO_BOTTOM, sourceFill.fillDirection)
        assertDrawConfigEquals(sourceFill, drawConfigOf(decoded, "inheritedFill", PropKey.FILL_PROGRESS))
        assertEquals(DrawDirection.TOP_TO_BOTTOM, drawConfigOf(decoded, "inheritedFill", PropKey.DRAW_PROGRESS).direction)

        // (c) explicit START is preserved verbatim — never re-anchored by its direction.
        val sourceStart = drawConfigOf(source, "literalStart", PropKey.DRAW_PROGRESS)
        assertEquals(PathStart.START, sourceStart.start)
        assertDrawConfigEquals(sourceStart, drawConfigOf(decoded, "literalStart", PropKey.DRAW_PROGRESS))
    }

    /** Resolves the single draw config of one (named-path target, property) track. */
    private fun drawConfigOf(
        animation: VectorAnimation,
        targetName: String,
        prop: PropKey,
    ): VectorAnimation.DrawConfig =
        animation.tracks.single {
            it.prop == prop && (it.target as? AnimationTarget.Path)?.name == targetName
        }.segments.single().draw ?: throw AssertionError("Track $targetName/$prop carries no draw config")

    /** Field-by-field [VectorAnimation.DrawConfig] comparison (it is not a data class). */
    private fun assertDrawConfigEquals(
        expected: VectorAnimation.DrawConfig,
        actual: VectorAnimation.DrawConfig,
    ) {
        assertEquals(expected.mode, actual.mode)
        assertEquals(expected.start, actual.start)
        assertEquals(expected.direction, actual.direction)
        assertEquals(expected.fillMode, actual.fillMode)
        assertEquals(expected.fillDirection, actual.fillDirection)
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
