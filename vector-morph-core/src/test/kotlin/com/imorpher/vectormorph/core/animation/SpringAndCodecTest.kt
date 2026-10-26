package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.gradients.linearGradientSpecAtAngle
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spring-shaped easing and the versioned binary codec. */
class SpringAndCodecTest {

    // ------------------------------------------------------------------ SpringEasing

    @Test
    fun springEasingEndsAtTheExactBounds() {
        val spring = SpringEasing(0.5f, 300f)
        assertEquals(0f, spring.transform(0f), 0f)
        assertEquals(1f, spring.transform(1f), 1e-4f)
        assertEquals(0f, spring.transform(-1f), 0f)
        assertEquals(1f, spring.transform(2f), 1e-4f)
    }

    @Test
    fun underdampedSpringOvershootsThenSettles() {
        val spring = SpringEasing(dampingRatio = 0.4f, stiffness = 200f)
        var peak = 0f
        var steps = 0
        var t = 0.01f
        while (t < 1f) {
            peak = maxOf(peak, spring.transform(t))
            t += 0.01f
            steps++
        }
        assertTrue("bouncy spring must overshoot: peak=$peak over $steps steps", peak > 1.02f)
        assertTrue("spring must settle back near 1: ${spring.transform(1f)}", spring.transform(1f) < 1.05f)
    }

    @Test
    fun criticallyDampedSpringNeverOvershoots() {
        val spring = SpringEasing(dampingRatio = 1f, stiffness = 400f)
        var t = 0.01f
        while (t < 1f) {
            assertTrue("critically damped must stay ≤ 1 at t=$t", spring.transform(t) <= 1f + 1e-4f)
            assertTrue(spring.transform(t) >= 0f)
            t += 0.01f
        }
    }

    @Test
    fun stifferSpringsReachAnyValueSooner() {
        val soft = SpringEasing(1f, 100f)
        val stiff = SpringEasing(1f, 10_000f)
        var softer = true
        for (t in listOf(0.05f, 0.1f, 0.2f, 0.4f)) {
            if (soft.transform(t) >= stiff.transform(t)) softer = false
        }
        assertTrue("the stiffer spring should progress faster", softer)
    }

    @Test
    fun springEasingIsDeterministicAcrossInstances() {
        val a = SpringEasing(0.6f, 350f)
        val b = SpringEasing(0.6f, 350f)
        for (t in listOf(0.1f, 0.3f, 0.5f, 0.9f)) {
            assertEquals(a.transform(t), b.transform(t), 1e-6f)
        }
        assertTrue(a.settleDurationSeconds > 0f)
        assertTrue(a.settleDurationSeconds <= 10f)
    }

    @Test
    fun companionsExposeTheDocumentedPresets() {
        assertTrue(SpringEasing.MediumBouncy.transform(0.3f) > 0f)
        assertTrue(SpringEasing.NoBouncy.transform(0.999f) <= 1f)
        assertTrue(SpringEasing.of(0.5f, 300f).transform(0.5f) in 0f..1.2f)
    }

    @Test
    fun springEasingDrivesTracksThroughTheCodecSampling() {
        val spring = SpringEasing.MediumBouncy
        val linear = LinearEasing
        // at the same fraction the two easings must differ (the spring has character)
        assertNotEquals(linear.transform(0.3f), spring.transform(0.3f), 0.01f)
    }

    // ------------------------------------------------------------------ codec

    private fun triangle() = listOf(
        PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 3f), PathNode.LineTo(12f, 21f), PathNode.Close,
    )

    private fun prepared() = VectorCompiler.compile(
        ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(pathData = triangle(), fill = androidx.compose.ui.graphics.SolidColor(Color.Black), name = "body")
        }.build(),
    )

    @Test
    fun roundTripPreservesEveryTargetKindAndBrushKind() {
        val source = VectorAnimation {
            defaultEasing(LinearEasing)
            alpha(0.5f, 1f, interval = 0f..0.4f)
            scale(0.9f, 1f)
            pathIndex(0) { drawProgress(0f, 1f, 0f..0.6f, mode = DrawMode.CENTER_OUT, start = PathStart.TOP) }
            group("body") { rotation(-15f, 15f) }
            gradient(
                BrushSpec.Radial(listOf(Color.Red, Color.Blue), listOf(0f, 1f), 12f, 12f, 10f),
                BrushSpec.Sweep(listOf(Color.Green, Color.Yellow), null, 8f, 8f),
            )
            path("body") { strokeColor(Color.Black, Color.White) }
            stagger(0.1f, DrawOrderStrategy.COUNTER_CLOCKWISE)
        }

        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(source))

        assertEquals(source.tracks.size, decoded.tracks.size)
        assertEquals(source.brushTracks.size, decoded.brushTracks.size)
        assertEquals(source.staggerDelay, decoded.staggerDelay, 0f)
        assertEquals(source.staggerOrder, decoded.staggerOrder)

        // radial → sweep is a crossfade pair; both endpoint specs must survive
        val gradientTrack = decoded.brushTracks.first { it.segments.first().from is BrushSpec.Radial }
        val from = gradientTrack.segments.first().from as BrushSpec.Radial
        assertEquals(12f, from.centerX, 0f)
        assertEquals(listOf(0f, 1f), from.stops)
        val to = gradientTrack.segments.first().to as BrushSpec.Sweep
        assertEquals(8f, to.centerX, 0f)

        // draw config round trip
        val draw = decoded.drawConfigFor("body", "body", 0, 0, PropKey.DRAW_PROGRESS)
        assertNotNull(draw)
        assertEquals(DrawMode.CENTER_OUT, draw!!.mode)
        assertEquals(PathStart.TOP, draw.start)

        // stroke channel round trip
        val strokeTrack = decoded.brushTracks.first { it.channel == BrushChannel.STROKE }
        assertEquals(AnimationTarget.Path("body", 0), strokeTrack.target)
    }

    @Test
    fun decodedEasingMatchesTheOriginalWithinTolerance() {
        val source = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { alpha(0f, 1f, interval = 0f..1f, easing = SpringEasing.LowBouncy) }
        }
        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(source))
        val original = source.tracks.single().segments.single().easing
        val restored = decoded.tracks.single().segments.single().easing
        for (i in 0..10) {
            val fraction = i / 10f
            assertEquals(original.transform(fraction), restored.transform(fraction), 0.01f)
        }
    }

    @Test
    fun decodeRejectsCorruptPayloads() {
        val valid = VectorAnimationCodec.encode(
            VectorAnimation { defaultEasing(LinearEasing); alpha(0f, 1f) },
        )

        // bad magic
        assertThrows(IllegalArgumentException::class.java) {
            VectorAnimationCodec.decode(byteArrayOf(0, 0, 0, 0) + valid.copyOfRange(4, valid.size))
        }
        // truncated payload
        assertThrows(IllegalArgumentException::class.java) {
            VectorAnimationCodec.decode(valid.copyOfRange(0, valid.size / 2))
        }
        // trailing garbage
        assertThrows(IllegalArgumentException::class.java) {
            VectorAnimationCodec.decode(valid + byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun encodeRejectsCustomBrushesAndCallbacks() {
        val withUnknownBrush = VectorAnimation {
            defaultEasing(LinearEasing)
            gradient(BrushSpec.Unknown(androidx.compose.ui.graphics.SolidColor(Color.Magenta)), BrushSpec.Solid(Color.Red))
        }
        assertThrows(IllegalArgumentException::class.java) { VectorAnimationCodec.encode(withUnknownBrush) }

        val withCallback = VectorAnimation { defaultEasing(LinearEasing); customAnimation { null } }
        assertThrows(IllegalArgumentException::class.java) { VectorAnimationCodec.encode(withCallback) }
    }

    @Test
    fun decodedAnimationEvaluatesLikeTheOriginal() {
        val source = VectorAnimation {
            defaultEasing(LinearEasing)
            path("body") { alpha(0f, 1f, interval = 0.2f..0.8f) }
        }
        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(source))
        val plan = MorphPlanner.plan(prepared(), prepared())

        val originalFrame = EvaluatedFrame(plan)
        val decodedFrame = EvaluatedFrame(plan)
        TimelineEvaluator(source, plan, ColorInterpolationSpace.SRGB, timingMode = com.imorpher.vectormorph.core.model.TimingMode.UNIFORM)
            .evaluate(0.5f, originalFrame)
        TimelineEvaluator(decoded, plan, ColorInterpolationSpace.SRGB, timingMode = com.imorpher.vectormorph.core.model.TimingMode.UNIFORM)
            .evaluate(0.5f, decodedFrame)

        assertEquals(
            originalFrame.pathProps[0].props[PropKey.ALPHA.ordinal],
            decodedFrame.pathProps[0].props[PropKey.ALPHA.ordinal],
            1e-4f,
        )
    }

    @Test
    fun angleGradientsRoundTripTheirAngleMetadata() {
        val source = VectorAnimation {
            defaultEasing(LinearEasing)
            gradient(
                linearGradientSpecAtAngle(listOf(Color.Red, Color.Blue), 350f),
                linearGradientSpecAtAngle(listOf(Color.Green, Color.White), 45f, length = 2f),
            )
        }
        val decoded = VectorAnimationCodec.decode(VectorAnimationCodec.encode(source))
        val from = decoded.brushTracks.single().segments.single().from as BrushSpec.Linear
        assertEquals(350f, from.angleDegrees!!, 0f)
        val to = decoded.brushTracks.single().segments.single().to as BrushSpec.Linear
        assertEquals(45f, to.angleDegrees!!, 0f)
    }
}
