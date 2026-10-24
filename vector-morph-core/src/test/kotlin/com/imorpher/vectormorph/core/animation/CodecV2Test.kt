package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec v2 contract (Review Focus 5): version-2 typed records round-trip losslessly, and the
 * committed version-1 fixture still decodes.
 */
class CodecV2Test {

    @Test
    fun committedFixtureIsVersionOne() {
        val bytes = fixtureBytes()

        assertTrue("fixture must be non-empty", bytes.size > 8)
        val header = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        assertEquals(0x564D414E, header.readInt())
        assertEquals("the golden fixture must stay at wire version 1", 1, header.readInt())
    }

    @Test
    fun v1FixtureDecodesSuccessfully() {
        val decoded = VectorAnimationCodec.decode(fixtureBytes())

        // The fixture exercises all three reveal kinds plus both brush channels.
        val draw = decoded.tracks.single { it.prop == PropKey.DRAW_PROGRESS }
            .segments.single().reveal as DrawConfig
        assertEquals(DrawMode.FORWARD, draw.mode)
        assertEquals(PathStart.TOP_LEFT, draw.start)
        assertEquals(DrawDirection.LEFT_TO_RIGHT, draw.direction)

        val fillTrack = decoded.tracks.single { it.prop == PropKey.FILL_PROGRESS }
        val fillSegments = fillTrack.segments
        assertEquals("the fill reveal overrides the first 20% of the timeline", 2, fillSegments.size)
        val fill = fillSegments.last().reveal as FillRevealConfig
        assertEquals(FillMode.RADIAL, fill.fillMode)
        assertEquals(DrawDirection.TOP_TO_BOTTOM, fill.fillDirection)

        val clip = decoded.tracks.single { it.prop == PropKey.CLIP_PROGRESS }
            .segments.single().reveal as ClipRevealConfig
        assertEquals(DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT, clip.direction)

        assertEquals(2, decoded.brushTracks.size)
        assertTrue(decoded.brushTracks.any { it.channel == BrushChannel.STROKE })
        assertTrue(decoded.brushTracks.any { it.channel == BrushChannel.FILL })
    }

    @Test
    fun v2RoundTripPreservesStructure() {
        val original = canonicalAnimation()

        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(original))

        assertEquals(original.tracks.size, decoded.tracks.size)
        assertEquals(original.brushTracks.size, decoded.brushTracks.size)
        assertEquals(original.staggerDelay, decoded.staggerDelay, 0f)
        assertEquals(original.staggerOrder, decoded.staggerOrder)
        assertEquals(original.appearanceOnly, decoded.appearanceOnly)

        for (i in original.tracks.indices) {
            val expected = original.tracks[i]
            val actual = decoded.tracks[i]
            assertEquals(expected.target, actual.target)
            assertEquals(expected.prop, actual.prop)
            assertEquals(expected.segments.size, actual.segments.size)
            for (j in expected.segments.indices) {
                val e = expected.segments[j]
                val a = actual.segments[j]
                assertEquals(e.start, a.start, 0f)
                assertEquals(e.end, a.end, 0f)
                assertEquals(e.from, a.from, 0f)
                assertEquals(e.to, a.to, 0f)
                assertRevealEquals(e.reveal, a.reveal)
            }
        }
    }

    @Test
    fun v2EncodingWritesVersionTwoHeaderAndTypedRecords() {
        val bytes = VectorAnimationCodec.encode(canonicalAnimation())

        val header = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        assertEquals(0x564D414E, header.readInt())
        assertEquals(2, header.readInt())
        // And the same bytes decode back into the same structure.
        assertEquals(canonicalAnimation().tracks.size, VectorAnimationCodec.decode(bytes).tracks.size)
    }

    @Test
    fun v2EncodingIsDeterministic() {
        val animation = canonicalAnimation()

        assertTrue(
            VectorAnimationCodec.encode(animation).contentEquals(VectorAnimationCodec.encode(animation)),
        )
    }

    @Test
    fun decodedAnimationCarriesNoCustomizer() {
        assertNotNull(VectorAnimationCodec.decode(fixtureBytes()))
        assertEquals(null, VectorAnimationCodec.decode(fixtureBytes()).customizer)
    }

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/vman-v1-canonical.vman")?.use { it.readBytes() }
            ?: error("missing golden fixture vman-v1-canonical.vman")

    private fun assertRevealEquals(expected: Reveal?, actual: Reveal?) {
        when (expected) {
            null -> assertEquals(null, actual)
            is DrawConfig -> {
                val a = actual as DrawConfig
                assertEquals(expected.mode, a.mode)
                assertEquals(expected.start, a.start)
                assertEquals(expected.direction, a.direction)
            }
            is FillRevealConfig -> {
                val a = actual as FillRevealConfig
                assertEquals(expected.fillMode, a.fillMode)
                assertEquals(expected.fillDirection, a.fillDirection)
            }
            is ClipRevealConfig -> {
                assertEquals(expected.direction, (actual as ClipRevealConfig).direction)
            }
        }
    }

    /** Mirrors the fixture animation: every reveal kind, both brush channels, a keyframe pair. */
    private fun canonicalAnimation(): VectorAnimation = VectorAnimation {
        defaultEasing(LinearEasing)
        at(0f) { alpha = 0f }
        at(1f) { alpha = 1f }
        path("drawn") {
            drawProgress(
                interval = 0f..1f,
                mode = DrawMode.FORWARD,
                start = PathStart.TOP_LEFT,
                direction = DrawDirection.LEFT_TO_RIGHT,
            )
        }
        path("filled") {
            fillProgress(interval = 0.2f..1f)
            fill(mode = FillMode.RADIAL, direction = DrawDirection.TOP_TO_BOTTOM, interval = 0.2f..1f)
        }
        allPaths {
            clipReveal(
                direction = DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT,
                interval = 0.1f..0.9f,
            )
        }
        gradient(
            from = BrushSpec.Solid(Color.Red),
            to = BrushSpec.Solid(Color.Blue),
            interval = 0f..1f,
        )
        strokeColor(from = Color.Green, to = Color.Magenta, interval = 0f..0.5f)
    }
}
