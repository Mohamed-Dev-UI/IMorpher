package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.gradients.BrushSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Versioned, deterministic binary representation of a [VectorAnimation]. A vector itself is
 * intentionally not embedded: pair the decoded animation with an `ImageVector` in the app.
 * Easing functions are sampled into 129 values so arbitrary Compose `Easing` implementations can
 * be reused with sub-percent visual error without relying on class names or reflection.
 *
 * Custom render callbacks and custom `Brush` implementations contain executable/runtime state and
 * are rejected with a descriptive exception. Built-in solid, linear, radial, and sweep brushes
 * are fully supported.
 *
 * Wire format stability: the on-disk layout is unchanged since version 1. Reveal records keep a
 * five-slot shape per segment regardless of kind; FILL_PROGRESS and CLIP_PROGRESS records carry
 * meaningless placeholder strings ("FORWARD"/"START") in the slots they never consume, which the
 * decoder enum-validates and drops. Re-encoding always writes the canonical placeholders, so
 * legacy-spelled files (e.g. `AUTO` in a fill/clip record's start slot) decode identically but do
 * not round-trip byte-for-byte.
 */
object VectorAnimationCodec {
    private const val MAGIC = 0x564D414E // VMAN
    private const val VERSION = 1
    private const val EASING_SAMPLES = 129
    private const val MAX_ITEMS = 100_000

    /**
     * Serializes [animation] into a versioned, deterministic byte array (magic "VMAN").
     * Easings are baked into sampled curves and built-in brush specs are stored by value;
     * custom render callbacks and custom [androidx.compose.ui.graphics.Brush]es are rejected.
     */
    fun encode(animation: VectorAnimation): ByteArray {
        require(animation.customizer == null) {
            "VectorAnimationCodec cannot serialize CustomVectorAnimation callbacks; define the callback in code."
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeBoolean(animation.appearanceOnly)
            out.writeFloat(animation.staggerDelay)
            out.writeUTF(animation.staggerOrder.name)
            out.writeInt(animation.tracks.size)
            animation.tracks.forEach { track ->
                writeTarget(out, track.target); out.writeUTF(track.prop.name)
                out.writeInt(track.segments.size)
                track.segments.forEach { segment ->
                    out.writeFloat(segment.start); out.writeFloat(segment.end)
                    out.writeFloat(segment.from); out.writeFloat(segment.to)
                    writeEasing(out, segment.easing)
                    out.writeBoolean(segment.reveal != null)
                    segment.reveal?.let { reveal ->
                        // Same 5-field record per reveal as version 1 (mode/start/direction
                        // slots + the nullable fill pair) — dispatching by the record's
                        // property keeps the byte layout stable while the payload becomes typed.
                        when (track.prop) {
                            PropKey.DRAW_PROGRESS -> {
                                val draw = reveal as? DrawConfig
                                    ?: throw IllegalStateException(
                                        "DRAW_PROGRESS segment carries ${reveal::class.simpleName}",
                                    )
                                out.writeUTF(draw.mode.name); out.writeUTF(draw.start.name); out.writeUTF(draw.direction.name)
                                // The fill pair no longer rides stroke reveals: keep the two
                                // nullable slots as backward-compatible null writes.
                                writeNullableEnum(out, null); writeNullableEnum(out, null)
                            }
                            PropKey.FILL_PROGRESS -> {
                                val fill = reveal as? FillRevealConfig
                                    ?: throw IllegalStateException(
                                        "FILL_PROGRESS segment carries ${reveal::class.simpleName}",
                                    )
                                out.writeUTF(com.imorpher.vectormorph.core.model.DrawMode.FORWARD.name)
                                out.writeUTF(com.imorpher.vectormorph.core.model.PathStart.START.name)
                                out.writeUTF(com.imorpher.vectormorph.core.model.DrawDirection.AUTO.name)
                                writeNullableEnum(out, fill.fillMode?.name)
                                writeNullableEnum(out, fill.fillDirection?.name)
                            }
                            PropKey.CLIP_PROGRESS -> {
                                val clip = reveal as? ClipRevealConfig
                                    ?: throw IllegalStateException(
                                        "CLIP_PROGRESS segment carries ${reveal::class.simpleName}",
                                    )
                                out.writeUTF(com.imorpher.vectormorph.core.model.DrawMode.FORWARD.name)
                                out.writeUTF(com.imorpher.vectormorph.core.model.PathStart.START.name)
                                out.writeUTF(clip.direction.name)
                                writeNullableEnum(out, null); writeNullableEnum(out, null)
                            }
                            else -> throw IllegalStateException(
                                "Reveal record on non-reveal property ${track.prop.name}",
                            )
                        }
                    }
                }
            }
            out.writeInt(animation.brushTracks.size)
            animation.brushTracks.forEach { track ->
                writeTarget(out, track.target); out.writeUTF(track.channel.name)
                out.writeInt(track.segments.size)
                track.segments.forEach { segment ->
                    out.writeFloat(segment.start); out.writeFloat(segment.end)
                    writeBrush(out, segment.from); writeBrush(out, segment.to)
                    writeEasing(out, segment.easing)
                }
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Restores a [VectorAnimation] written by [encode]. Validates the header, version, all
     * counts and enums, and rejects trailing bytes; truncated or corrupt input raises
     * [IllegalArgumentException]. The decoded animation evaluates identically to the
     * original (within the easings' sampling tolerance) but carries no customizer.
     */
    fun decode(bytes: ByteArray): VectorAnimation = try {
        decodeUnchecked(bytes)
    } catch (truncated: EOFException) {
        throw IllegalArgumentException("Truncated vector animation definition", truncated)
    }

    /** Header/structure validation and field decoding shared by [decode]. */
    private fun decodeUnchecked(bytes: ByteArray): VectorAnimation {
        require(bytes.size <= 64 * 1024 * 1024) { "Animation definition exceeds 64 MiB" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Not a VectorAnimationCodec definition" }
            val version = input.readInt()
            require(version == VERSION) { "Unsupported vector animation definition version $version" }
            val appearanceOnly = input.readBoolean()
            val staggerDelay = input.readFloat()
            val staggerOrder = enumValue<com.imorpher.vectormorph.core.model.DrawOrderStrategy>(input.readUTF())

            val trackCount = readCount(input)
            val tracks = ArrayList<VectorAnimation.Track>(trackCount)
            repeat(trackCount) {
                val target = readTarget(input)
                val prop = enumValue<PropKey>(input.readUTF())
                val segmentCount = readCount(input)
                val segments = ArrayList<VectorAnimation.Segment>(segmentCount)
                repeat(segmentCount) {
                    val start = input.readFloat(); val end = input.readFloat()
                    val from = input.readFloat(); val to = input.readFloat()
                    val easing = readEasing(input)
                    // Version-1 record layout: mode/start/direction string slots followed by
                    // the nullable fill pair, regardless of the record's property. Decode
                    // validates every slot with the enum it is written as (parity with the
                    // pre-cleanup decoder, which enum-checked all five) and maps the record
                    // onto the property's typed reveal variant; placeholder/mismatched
                    // slots are read, validated, and dropped.
                    val reveal = if (input.readBoolean()) {
                        val mode = enumValue<com.imorpher.vectormorph.core.model.DrawMode>(input.readUTF())
                        val anchor = enumValue<com.imorpher.vectormorph.core.model.PathStart>(input.readUTF())
                        val direction = enumValue<com.imorpher.vectormorph.core.model.DrawDirection>(input.readUTF())
                        val fillModeName = readNullableEnum(input)?.let { enumValue<com.imorpher.vectormorph.core.model.FillMode>(it) }
                        val fillDirectionName = readNullableEnum(input)?.let { enumValue<com.imorpher.vectormorph.core.model.DrawDirection>(it) }
                        when (prop) {
                            PropKey.DRAW_PROGRESS -> VectorAnimation.DrawConfig(mode, anchor, direction)
                            PropKey.FILL_PROGRESS -> VectorAnimation.FillRevealConfig(
                                fillMode = fillModeName,
                                fillDirection = fillDirectionName,
                            )
                            PropKey.CLIP_PROGRESS -> VectorAnimation.ClipRevealConfig(direction)
                            else -> throw IllegalStateException(
                                "Reveal record on non-reveal property ${prop.name}",
                            )
                        }
                    } else null
                    segments.add(VectorAnimation.Segment(start, end, from, to, easing, reveal))
                }
                tracks.add(VectorAnimation.Track(target, prop, segments))
            }

            val brushTrackCount = readCount(input)
            val brushTracks = ArrayList<VectorAnimation.BrushTrack>(brushTrackCount)
            repeat(brushTrackCount) {
                val target = readTarget(input)
                val channel = enumValue<BrushChannel>(input.readUTF())
                val segmentCount = readCount(input)
                val segments = ArrayList<VectorAnimation.BrushSegment>(segmentCount)
                repeat(segmentCount) {
                    val start = input.readFloat(); val end = input.readFloat()
                    val from = readBrush(input); val to = readBrush(input)
                    segments.add(VectorAnimation.BrushSegment(start, end, from, to, readEasing(input)))
                }
                brushTracks.add(VectorAnimation.BrushTrack(target, segments, channel))
            }
            require(input.available() == 0) { "Trailing bytes in vector animation definition" }
            return VectorAnimation(tracks, brushTracks, appearanceOnly, staggerDelay, staggerOrder)
        }
    }

    /** Tagged union encoding of [AnimationTarget]; tags 0..4, unknown tags fail decode. */
    private fun writeTarget(out: DataOutputStream, target: AnimationTarget) {
        when (target) {
            AnimationTarget.Vector -> out.writeByte(0)
            AnimationTarget.AllPaths -> out.writeByte(1)
            is AnimationTarget.Path -> { out.writeByte(2); out.writeUTF(target.name); out.writeInt(target.occurrence) }
            is AnimationTarget.PathIndex -> { out.writeByte(3); out.writeInt(target.index) }
            is AnimationTarget.Group -> { out.writeByte(4); out.writeUTF(target.name) }
        }
    }

    /** Inverse of [writeTarget]; unknown tags abort the decode. */
    private fun readTarget(input: DataInputStream): AnimationTarget = when (input.readUnsignedByte()) {
        0 -> AnimationTarget.Vector
        1 -> AnimationTarget.AllPaths
        2 -> AnimationTarget.Path(input.readUTF(), input.readInt())
        3 -> AnimationTarget.PathIndex(input.readInt())
        4 -> AnimationTarget.Group(input.readUTF())
        else -> error("Unknown animation target tag")
    }

    /**
     * Tagged union encoding of [BrushSpec]; tags 0..3 (solid, linear, radial, sweep).
     * [BrushSpec.Unknown] is rejected — a custom Brush implementation cannot be persisted.
     */
    private fun writeBrush(out: DataOutputStream, brush: BrushSpec) {
        when (brush) {
            is BrushSpec.Solid -> { out.writeByte(0); writeColor(out, brush.color) }
            is BrushSpec.Linear -> {
                out.writeByte(1); writeColors(out, brush.colors); writeStops(out, brush.stops)
                out.writeFloat(brush.startX); out.writeFloat(brush.startY); out.writeFloat(brush.endX); out.writeFloat(brush.endY)
                out.writeBoolean(brush.angleDegrees != null); brush.angleDegrees?.let(out::writeFloat)
            }
            is BrushSpec.Radial -> {
                out.writeByte(2); writeColors(out, brush.colors); writeStops(out, brush.stops)
                out.writeFloat(brush.centerX); out.writeFloat(brush.centerY); out.writeFloat(brush.radius)
            }
            is BrushSpec.Sweep -> {
                out.writeByte(3); writeColors(out, brush.colors); writeStops(out, brush.stops)
                out.writeFloat(brush.centerX); out.writeFloat(brush.centerY)
            }
            is BrushSpec.Unknown -> throw IllegalArgumentException(
                "VectorAnimationCodec cannot serialize a custom Brush implementation",
            )
        }
    }

    /** Inverse of [writeBrush]; unknown tags abort the decode. */
    private fun readBrush(input: DataInputStream): BrushSpec = when (input.readUnsignedByte()) {
        0 -> BrushSpec.Solid(readColor(input))
        1 -> {
            val colors = readColors(input); val stops = readStops(input)
            val startX = input.readFloat(); val startY = input.readFloat()
            val endX = input.readFloat(); val endY = input.readFloat()
            val angle = if (input.readBoolean()) input.readFloat() else null
            BrushSpec.Linear(colors, stops, startX, startY, endX, endY, angle)
        }
        2 -> BrushSpec.Radial(
            readColors(input), readStops(input), input.readFloat(), input.readFloat(), input.readFloat(),
        )
        3 -> BrushSpec.Sweep(readColors(input), readStops(input), input.readFloat(), input.readFloat())
        else -> error("Unknown brush tag")
    }

    /** Length-prefixed color list. */
    private fun writeColors(out: DataOutputStream, colors: List<Color>) {
        out.writeInt(colors.size); colors.forEach { writeColor(out, it) }
    }
    /** Inverse of [writeColors]. */
    private fun readColors(input: DataInputStream): List<Color> = List(readCount(input)) { readColor(input) }

    /** Packed 64-bit color value, preserving the exact colorspace/alpha bits. */
    private fun writeColor(out: DataOutputStream, color: Color) = out.writeLong(color.value.toLong())

    /** Inverse of [writeColor]. */
    private fun readColor(input: DataInputStream): Color = Color(input.readLong().toULong())

    /** Null flag + length-prefixed stop list. */
    private fun writeStops(out: DataOutputStream, stops: List<Float>?) {
        out.writeBoolean(stops != null); stops?.let { out.writeInt(it.size); it.forEach(out::writeFloat) }
    }
    /** Inverse of [writeStops]. */
    private fun readStops(input: DataInputStream): List<Float>? =
        if (!input.readBoolean()) null else List(readCount(input)) { input.readFloat() }

    /**
     * Bakes [easing] into [EASING_SAMPLES] uniform samples so any Compose easing survives
     * the round trip with sub-percent visual error and no class-name/reflection dependency.
     */
    private fun writeEasing(out: DataOutputStream, easing: Easing) {
        out.writeInt(EASING_SAMPLES)
        repeat(EASING_SAMPLES) { i -> out.writeFloat(easing.transform(i.toFloat() / (EASING_SAMPLES - 1))) }
    }

    /** Restores a sampled easing; sample counts outside the accepted range abort the decode. */
    private fun readEasing(input: DataInputStream): Easing {
        val count = readCount(input)
        require(count in 2..4097) { "Invalid serialized easing sample count: $count" }
        val samples = FloatArray(count) { input.readFloat() }
        return SampledEasing(samples)
    }

    /** Linear interpolation over the serialized easing samples; clamps outside 0..1. */
    private class SampledEasing(private val values: FloatArray) : Easing {
        override fun transform(fraction: Float): Float {
            val x = fraction.coerceIn(0f, 1f) * (values.size - 1)
            val i = x.toInt().coerceAtMost(values.lastIndex - 1)
            val t = x - i
            return values[i] + (values[i + 1] - values[i]) * t
        }
    }

    /** Null flag + enum name. */
    private fun writeNullableEnum(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null); value?.let(out::writeUTF)
    }
    /** Inverse of [writeNullableEnum]. */
    private fun readNullableEnum(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    /** Enum lookup by name; unknown names abort the decode. */
    private inline fun <reified T : Enum<T>> enumValue(name: String): T = enumValueOf(name)

    /** Length prefix validated against [MAX_ITEMS] so corrupt counts fail fast. */
    private fun readCount(input: DataInputStream): Int = input.readInt().also {
        require(it in 0..MAX_ITEMS) { "Invalid item count in vector animation definition: $it" }
    }
}
