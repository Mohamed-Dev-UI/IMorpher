package com.imorpher.vectormorph.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.animation.AnimationTarget
import com.imorpher.vectormorph.core.animation.ClipRevealConfig
import com.imorpher.vectormorph.core.animation.DrawConfig
import com.imorpher.vectormorph.core.animation.FillRevealConfig
import com.imorpher.vectormorph.core.animation.PropKey
import com.imorpher.vectormorph.core.animation.Reveal
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimationCodec
import com.imorpher.vectormorph.core.gradients.linearGradientSpecAtAngle
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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
        assertNotNull(decoded.strokeRevealFor("outline[1]", null, 0, -1, PropKey.DRAW_PROGRESS))
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
     * to null/AUTO. Comparison is field-based (compiled tracks and typed reveal configs),
     * not pixels.
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
        val sourceAuto = revealOf(source, "autoAnchor", PropKey.DRAW_PROGRESS) as DrawConfig
        assertEquals(PathStart.AUTO, sourceAuto.start)
        assertEquals(DrawDirection.BOTTOM_TO_TOP, sourceAuto.direction)
        assertStrokeEquals(sourceAuto, revealOf(decoded, "autoAnchor", PropKey.DRAW_PROGRESS) as DrawConfig)

        // (b) the inherited fill direction is concrete (not AUTO, not null).
        val sourceFill = revealOf(source, "inheritedFill", PropKey.FILL_PROGRESS) as FillRevealConfig
        assertEquals(FillMode.DIRECTIONAL, sourceFill.fillMode)
        assertEquals(DrawDirection.TOP_TO_BOTTOM, sourceFill.fillDirection)
        assertFillEquals(sourceFill, revealOf(decoded, "inheritedFill", PropKey.FILL_PROGRESS) as FillRevealConfig)
        assertEquals(
            DrawDirection.TOP_TO_BOTTOM,
            (revealOf(decoded, "inheritedFill", PropKey.DRAW_PROGRESS) as DrawConfig).direction,
        )

        // (c) explicit START is preserved verbatim — never re-anchored by its direction.
        val sourceStart = revealOf(source, "literalStart", PropKey.DRAW_PROGRESS) as DrawConfig
        assertEquals(PathStart.START, sourceStart.start)
        assertStrokeEquals(sourceStart, revealOf(decoded, "literalStart", PropKey.DRAW_PROGRESS) as DrawConfig)
    }

    /** Resolves the single reveal of one (named-path target, property) track. */
    private fun revealOf(
        animation: VectorAnimation,
        targetName: String,
        prop: PropKey,
    ): Reveal = animation.tracks.single {
        it.prop == prop && (it.target as? AnimationTarget.Path)?.name == targetName
    }.segments.single().reveal ?: throw AssertionError("Track $targetName/$prop carries no reveal")

    /** Field-by-field [DrawConfig] comparison (it is not a data class). */
    private fun assertStrokeEquals(expected: DrawConfig, actual: DrawConfig) {
        assertEquals(expected.mode, actual.mode)
        assertEquals(expected.start, actual.start)
        assertEquals(expected.direction, actual.direction)
    }

    /** Field-by-field [FillRevealConfig] comparison (it is not a data class). */
    private fun assertFillEquals(expected: FillRevealConfig, actual: FillRevealConfig) {
        assertEquals(expected.fillMode, actual.fillMode)
        assertEquals(expected.fillDirection, actual.fillDirection)
    }

    /** Field-by-field [ClipRevealConfig] comparison (it is not a data class). */
    private fun assertClipEquals(expected: ClipRevealConfig, actual: ClipRevealConfig) {
        assertEquals(expected.direction, actual.direction)
    }

    /**
     * Golden-bytes pin for v1 decoding (Review Focus 5): the VERSION-1 field layout — the
     * mode/start/direction string slots followed by the nullable fill pair — must keep decoding
     * now that the writer emits version 2 typed records. The stroke record spells the real trio
     * plus two `false` fill flags; the fill record writes `FORWARD`/`START`/`AUTO` placeholder
     * strings that no consumer reads and then the real fill pair; the clip record writes the
     * `FORWARD`/`START` placeholders plus its real moving-edge direction. Decoding yields the
     * three typed reveals exactly; re-encoding switches to version 2 without losing structure.
     */
    @Test
    fun goldenVersionOneBytesDecodeIntoTypedRevealsAndReEncodeToTheSameLayout() {
        val layout = goldenVersionOneBytes()
        val decoded = VectorAnimationCodec.decode(layout.bytes)

        assertEquals(3, decoded.tracks.size)
        val stroke = decoded.tracks.single { it.prop == PropKey.DRAW_PROGRESS }.segments.single().reveal
        val fill = decoded.tracks.single { it.prop == PropKey.FILL_PROGRESS }.segments.single().reveal
        val clip = decoded.tracks.single { it.prop == PropKey.CLIP_PROGRESS }.segments.single().reveal

        // Stroke spell-out: FORWARD / AUTO / BOTTOM_TO_TOP; the two trailing fill slots are
        // read-and-dropped — stroke reveals carry no fill coupling anymore.
        assertStrokeEquals(
            DrawConfig(mode = DrawMode.FORWARD, start = PathStart.AUTO, direction = DrawDirection.BOTTOM_TO_TOP),
            stroke as DrawConfig,
        )
        assertFillEquals(FillRevealConfig(FillMode.DIRECTIONAL, null), fill as FillRevealConfig)
        assertClipEquals(ClipRevealConfig(DrawDirection.LEFT_TO_RIGHT), clip as ClipRevealConfig)

        // Round-trip identity: decode → encode again → decode yields the same typed reveals.
        val reEncoded = VectorAnimationCodec.encode(decoded)
        val roundTripped = VectorAnimationCodec.decode(reEncoded)
        assertStrokeEquals(
            stroke as DrawConfig,
            roundTripped.tracks.single { it.prop == PropKey.DRAW_PROGRESS }.segments.single().reveal as DrawConfig,
        )
        assertFillEquals(
            fill as FillRevealConfig,
            roundTripped.tracks.single { it.prop == PropKey.FILL_PROGRESS }.segments.single().reveal as FillRevealConfig,
        )
        assertClipEquals(
            clip as ClipRevealConfig,
            roundTripped.tracks.single { it.prop == PropKey.CLIP_PROGRESS }.segments.single().reveal
                as ClipRevealConfig,
        )

        // Version 2 writes typed records and 257 easing samples, so a re-encode does NOT
        // reproduce the v1 byte layout. What must hold is the semantic round-trip and the
        // version bump; the golden v1 blob above is the forward-compatibility pin.
        val header = java.io.DataInputStream(java.io.ByteArrayInputStream(reEncoded))
        assertEquals(0x564D414E, header.readInt())
        assertEquals("re-encoding a legacy definition writes the current version", 2, header.readInt())
        assertTrue(
            "typed records are narrower than the v1 five-slot layout",
            reEncoded.size != layout.bytes.size,
        )
    }

    /**
     * Legacy-spelling variant of the golden blob: pre-cleanup encoders baked start-anchor
     * `"AUTO"` (not `"START"`) into the FILL and CLIP records because the shared
     * `cfg(..., PathStart.AUTO, ...)` was created before the per-property split. `"AUTO"`
     * is a valid `PathStart` name, so the decode validates the slot and drops the value —
     * the typed reveals must come out exactly identical to the `START`-placeholder variant
     * and to what the current encoder re-emits for them.
     */
    @Test
    fun legacyAutoStartSpellingDecodes() {
        val legacy = VectorAnimationCodec.decode(goldenVersionOneBytes(fillClipStartSlot = "AUTO").bytes)
        val postSplit = VectorAnimationCodec.decode(goldenVersionOneBytes().bytes)

        assertFillEquals(
            postSplit.fillRevealOf(PropKey.FILL_PROGRESS),
            legacy.fillRevealOf(PropKey.FILL_PROGRESS),
        )
        assertClipEquals(
            postSplit.clipRevealOf(PropKey.CLIP_PROGRESS),
            legacy.clipRevealOf(PropKey.CLIP_PROGRESS),
        )
        assertStrokeEquals(
            postSplit.strokeRevealOf(PropKey.DRAW_PROGRESS),
            legacy.strokeRevealOf(PropKey.DRAW_PROGRESS),
        )
        // Both decodes carry the same reveals, so the deterministic encode places them
        // back into byte-identical (post-split spelling) blobs.
        assertArrayEquals(VectorAnimationCodec.encode(postSplit), VectorAnimationCodec.encode(legacy))
    }

    /**
     * Decode strictness parity (review follow-up 4): the old decoder momentarily
     * enum-validated the nullable pair slots on EVERY record — also on stroke and clip
     * records that then dropped the values. Corrupt bytes in those slots must still
     * throw exactly as before instead of being silently read-and-dropped.
     */
    @Test
    fun corruptPairSlotStillThrowsExactlyAsBefore() {
        // A DRAW record whose fillMode slot carries a name that is not a FillMode fails
        // the decode (the pre-cleanup decoder validated it on the DrawConfig's fillMode
        // field before the renderer ever dropped it).
        assertThrows(IllegalArgumentException::class.java) {
            VectorAnimationCodec.decode(goldenVersionOneBytes(strokeFillModeName = "NOT_A_FILL_MODE").bytes)
        }
    }

    /** Single-typed-reveal readers over the golden blob's three AllPaths tracks. */
    private fun VectorAnimation.strokeRevealOf(prop: PropKey): DrawConfig {
        val reveal = tracks.single { it.prop == prop }.segments.single().reveal
        return reveal as? DrawConfig ?: throw AssertionError("Track $prop carries no stroke reveal")
    }

    private fun VectorAnimation.fillRevealOf(prop: PropKey): FillRevealConfig {
        val reveal = tracks.single { it.prop == prop }.segments.single().reveal
        return reveal as? FillRevealConfig ?: throw AssertionError("Track $prop carries no fill reveal")
    }

    private fun VectorAnimation.clipRevealOf(prop: PropKey): ClipRevealConfig {
        val reveal = tracks.single { it.prop == prop }.segments.single().reveal
        return reveal as? ClipRevealConfig ?: throw AssertionError("Track $prop carries no clip reveal")
    }

    /** Hand-built literal byte array + the offsets of the compared spot regions. */
    private class GoldenLayout(
        val bytes: ByteArray,
        val strokeRecordStart: Int,
        val strokeRecordEnd: Int,
        val strokeFillModeNullOffset: Int,
        val strokeFillDirectionNullOffset: Int,
        val fillPairStart: Int,
        val fillPairEnd: Int,
        val clipDirectionStart: Int,
        val clipDirectionEnd: Int,
    )

    /** The documented V1 layout above, emitted literally with [DataOutputStream]. */
    private fun goldenVersionOneBytes(
        fillClipStartSlot: String = "START",
        strokeFillModeName: String? = null,
    ): GoldenLayout {
        val sink = ByteArrayOutputStream()
        val out = DataOutputStream(sink)
        fun easingSamples() {
            out.writeInt(129)   // EASING_SAMPLES: LinearEasing sampled 129 times
            (0 until 129).forEach { sample -> out.writeFloat(sample / 128f) }
        }
        // ---- header
        out.writeInt(0x564D414E)                       // MAGIC "VMAN"
        out.writeInt(1)                                // VERSION — unchanged by the typed split
        out.writeBoolean(false)                        // slot reserved, was appearanceOnly ≤ 0.2
        out.writeFloat(0f)                             // staggerDelay
        out.writeUTF("TOP_LEFT_TO_BOTTOM_RIGHT")       // staggerOrder
        // ---- tracks
        out.writeInt(3)                                // track count: one DRAW, one FILL, one CLIP
        // -- track 1 of 3: DRAW_PROGRESS (AllPaths)
        out.writeByte(1)                               // target tag: AllPaths
        out.writeUTF("DRAW_PROGRESS")
        out.writeInt(1)                                // segment count
        out.writeFloat(0f); out.writeFloat(1f)         // segment start / end
        out.writeFloat(0f); out.writeFloat(1f)         // segment from / to
        easingSamples()
        val strokeRecordStart = sink.size()
        out.writeBoolean(true)                         // hasReveal
        out.writeUTF("FORWARD")                        // mode (real)
        out.writeUTF("AUTO")                           // start anchor (real)
        out.writeUTF("BOTTOM_TO_TOP")                  // direction (real)
        val strokeFillModeNullOffset = sink.size()
        out.writeBoolean(strokeFillModeName != null)
        strokeFillModeName?.let(out::writeUTF)         // nullable fillMode slot — stroke carries none
        val strokeFillDirectionNullOffset = sink.size()
        out.writeBoolean(false)                        // nullable fillDirection slot — stroke carries none
        val strokeRecordEnd = sink.size()
        // -- track 2 of 3: FILL_PROGRESS (AllPaths)
        out.writeByte(1)
        out.writeUTF("FILL_PROGRESS")
        out.writeInt(1)
        out.writeFloat(0f); out.writeFloat(1f)
        out.writeFloat(0f); out.writeFloat(1f)
        easingSamples()
        out.writeBoolean(true)                         // hasReveal
        out.writeUTF("FORWARD")                        // placeholder — no consumer reads it
        out.writeUTF(fillClipStartSlot)                // placeholder — no consumer reads it
        out.writeUTF("AUTO")                           // placeholder — no consumer reads it
        val fillPairStart = sink.size()
        out.writeBoolean(true); out.writeUTF("DIRECTIONAL")  // fillMode (real)
        out.writeBoolean(false)                        // fillDirection = null → renderer fallback
        val fillPairEnd = sink.size()
        // -- track 3 of 3: CLIP_PROGRESS (AllPaths)
        out.writeByte(1)
        out.writeUTF("CLIP_PROGRESS")
        out.writeInt(1)
        out.writeFloat(0f); out.writeFloat(1f)
        out.writeFloat(0f); out.writeFloat(1f)
        easingSamples()
        out.writeBoolean(true)                         // hasReveal
        out.writeUTF("FORWARD")                        // placeholder — no consumer reads it
        out.writeUTF(fillClipStartSlot)                // placeholder — no consumer reads it
        val clipDirectionStart = sink.size()
        out.writeUTF("LEFT_TO_RIGHT")                  // moving clip edge (the record's real field)
        val clipDirectionEnd = sink.size()
        out.writeBoolean(false)                        // nullable fillMode slot — clip carries none
        out.writeBoolean(false)                        // nullable fillDirection slot — clip carries none
        // ---- brushes
        out.writeInt(0)                                // brush track count
        out.flush()
        return GoldenLayout(
            bytes = sink.toByteArray(),
            strokeRecordStart = strokeRecordStart,
            strokeRecordEnd = strokeRecordEnd,
            strokeFillModeNullOffset = strokeFillModeNullOffset,
            strokeFillDirectionNullOffset = strokeFillDirectionNullOffset,
            fillPairStart = fillPairStart,
            fillPairEnd = fillPairEnd,
            clipDirectionStart = clipDirectionStart,
            clipDirectionEnd = clipDirectionEnd,
        )
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
