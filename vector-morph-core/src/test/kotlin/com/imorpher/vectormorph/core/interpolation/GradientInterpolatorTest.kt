package com.imorpher.vectormorph.core.interpolation

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.RadialGradient
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.SweepGradient
import androidx.compose.ui.geometry.Offset
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.gradients.linearGradientSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brush spec extraction and gradient interpolation between all kind combinations. */
class GradientInterpolatorTest {

    // ------------------------------------------------------------------ BrushSpec.from

    @Test
    fun extractsSolidLinearRadialAndSweepBrushes() {
        val solid = BrushSpec.from(SolidColor(Color.Red))
        assertTrue(solid is BrushSpec.Solid)
        assertEquals(Color.Red, (solid as BrushSpec.Solid).color)

        val linear = BrushSpec.from(Brush.linearGradient(listOf(Color.Red, Color.Blue), start = Offset(0f, 0f), end = Offset(24f, 0f)))
        assertTrue(linear is BrushSpec.Linear)
        linear as BrushSpec.Linear
        assertEquals(2, linear.colors.size)
        assertEquals(0f, linear.startX, 0f)
        assertEquals(24f, linear.endX, 0f)

        val radial = BrushSpec.from(Brush.radialGradient(listOf(Color.Red, Color.Blue), center = Offset(12f, 12f), radius = 10f))
        assertTrue(radial is BrushSpec.Radial)
        radial as BrushSpec.Radial
        assertEquals(12f, radial.centerX, 0f)
        assertEquals(10f, radial.radius, 0f)

        val sweep = BrushSpec.from(Brush.sweepGradient(listOf(Color.Red, Color.Blue), center = Offset(5f, 6f)))
        assertTrue(sweep is BrushSpec.Sweep)
        sweep as BrushSpec.Sweep
        assertEquals(5f, sweep.centerX, 0f)
        assertEquals(6f, sweep.centerY, 0f)
    }

    @Test
    fun stopsAndNullStopsSurviveExtraction() {
        val withStops = BrushSpec.from(
            Brush.linearGradient(0.2f to Color.Red, 0.8f to Color.Blue, start = Offset(0f, 0f), end = Offset(1f, 0f)),
        ) as BrushSpec.Linear
        assertEquals(listOf(0.2f, 0.8f), withStops.stops)

        val withoutStops = BrushSpec.from(
            Brush.linearGradient(listOf(Color.Red, Color.Blue), start = Offset(0f, 0f), end = Offset(1f, 0f)),
        ) as BrushSpec.Linear
        assertEquals(null, withoutStops.stops)
    }

    @Test
    fun unknownSpecsWrapTheirOriginalBrush() {
        val solid = SolidColor(Color.Cyan)
        val unknown = BrushSpec.Unknown(solid)
        assertTrue(unknown.isUnknown)
        assertTrue(unknown.brush === solid)
        assertEquals(null, BrushSpec.from(null))
    }

    @Test
    fun solidPromotionHelperKeepsSolidsAndPassesGradientsThrough() {
        val promoted = BrushSpec.asGradientOrNull(BrushSpec.Solid(Color.Gray)) as BrushSpec.Linear
        assertEquals(listOf(Color.Gray, Color.Gray), promoted.colors)
        val linear = linearGradientSpec(listOf(Color.Red, Color.Blue))
        assertTrue(BrushSpec.asGradientOrNull(linear) === linear)
        assertEquals(null, BrushSpec.asGradientOrNull(null))
    }

    // ------------------------------------------------------------------ interpolate

    @Test
    fun solidToSolidInterpolatesTheColor() {
        val result = GradientInterpolator.interpolate(
            BrushSpec.Solid(Color.Black), BrushSpec.Solid(Color.White), 0.5f, ColorInterpolationSpace.SRGB,
        )
        assertTrue(result is GradientInterpolator.Result.Interpolated)
        val brush = (result as GradientInterpolator.Result.Interpolated).brush as SolidColor
        assertEquals(0.5f, brush.value.red, 0.01f)
    }

    @Test
    fun solidToGradientIsPromotedNotCrossfaded() {
        val to = linearGradientSpec(listOf(Color.Red, Color.Blue))
        for (t in listOf(0f, 0.5f, 1f)) {
            val result = GradientInterpolator.interpolate(BrushSpec.Solid(Color.Yellow), to, t, ColorInterpolationSpace.SRGB)
            assertTrue("t=$t must interpolate", result is GradientInterpolator.Result.Interpolated)
        }
    }

    @Test
    fun mismatchedKindsCrossfade() {
        val linear = linearGradientSpec(listOf(Color.Red, Color.Blue))
        val radial = BrushSpec.Radial(listOf(Color.Red, Color.Blue), null, 0.5f, 0.5f, 0.7f)
        val result = GradientInterpolator.interpolate(linear, radial, 0.5f, ColorInterpolationSpace.SRGB)
        assertTrue(result is GradientInterpolator.Result.Crossfade)
        result as GradientInterpolator.Result.Crossfade
        assertEquals(0.5f, result.progress, 1e-5f)
        assertTrue(result.fromBrush is LinearGradient)
        assertTrue(result.toBrush is RadialGradient)
    }

    @Test
    fun unknownBrushesCrossfadeWithTheirOriginalBrushes() {
        val from = BrushSpec.Unknown(SolidColor(Color.Magenta))
        val to = BrushSpec.Solid(Color.Cyan)
        val result = GradientInterpolator.interpolate(from, to, 0.25f, ColorInterpolationSpace.SRGB)
        assertTrue(result is GradientInterpolator.Result.Crossfade)
        assertEquals(0.25f, (result as GradientInterpolator.Result.Crossfade).progress, 1e-5f)
    }

    @Test
    fun nullEndpointsFadeThroughTransparent() {
        val solid = BrushSpec.Solid(Color.Red)
        val fadeIn = GradientInterpolator.interpolate(null, solid, 0.5f, ColorInterpolationSpace.SRGB)
        assertTrue(fadeIn is GradientInterpolator.Result.Crossfade)
        assertEquals(0.5f, (fadeIn as GradientInterpolator.Result.Crossfade).progress, 1e-5f)

        val fadeOut = GradientInterpolator.interpolate(solid, null, 0.5f, ColorInterpolationSpace.SRGB)
        assertTrue(fadeOut is GradientInterpolator.Result.Crossfade)
        assertEquals(0.5f, (fadeOut as GradientInterpolator.Result.Crossfade).progress, 1e-5f)

        assertTrue(GradientInterpolator.interpolate(null, null, 0.5f, ColorInterpolationSpace.SRGB) is GradientInterpolator.Result.None)
    }

    @Test
    fun radialAndSweepGeometryInterpolates() {
        val a = BrushSpec.Radial(listOf(Color.Red, Color.Blue), null, 0f, 0f, 4f)
        val b = BrushSpec.Radial(listOf(Color.Red, Color.Blue), null, 10f, 8f, 12f)
        val mid = GradientInterpolator.interpolate(a, b, 0.5f, ColorInterpolationSpace.SRGB) as GradientInterpolator.Result.Interpolated
        val spec = BrushSpec.from(mid.brush) as BrushSpec.Radial
        assertEquals(5f, spec.centerX, 1e-4f)
        assertEquals(4f, spec.centerY, 1e-4f)
        assertEquals(8f, spec.radius, 1e-4f)

        val sweepA = BrushSpec.Sweep(listOf(Color.Red, Color.Blue), null, 0f, 0f)
        val sweepB = BrushSpec.Sweep(listOf(Color.Red, Color.Blue), null, 6f, 4f)
        val sweepMid = GradientInterpolator.interpolate(sweepA, sweepB, 0.5f, ColorInterpolationSpace.SRGB) as GradientInterpolator.Result.Interpolated
        val sweepSpec = BrushSpec.from(sweepMid.brush) as BrushSpec.Sweep
        assertEquals(3f, sweepSpec.centerX, 1e-4f)
        assertEquals(2f, sweepSpec.centerY, 1e-4f)
    }

    @Test
    fun linearEndpointsInterpolateWithoutAngleMetadata() {
        val a = linearGradientSpec(listOf(Color.Red, Color.Blue), startX = 0f, startY = 0f, endX = 10f, endY = 0f)
        val b = linearGradientSpec(listOf(Color.Red, Color.Blue), startX = 0f, startY = 10f, endX = 10f, endY = 10f)
        val mid = GradientInterpolator.interpolate(a, b, 0.5f, ColorInterpolationSpace.SRGB) as GradientInterpolator.Result.Interpolated
        val spec = BrushSpec.from(mid.brush) as BrushSpec.Linear
        // both gradients' Y endpoints lerp 0 → 10 and land at 5
        assertEquals(5f, spec.startY, 1e-4f)
        assertEquals(5f, spec.endY, 1e-4f)
    }

    @Test
    fun stopsUnionKeepsBothSidesEndpointsFaithful() {
        val a = BrushSpec.Linear(
            colors = listOf(Color.Red, Color.Green), stops = listOf(0f, 1f),
            startX = 0f, startY = 0f, endX = 1f, endY = 0f,
        )
        val b = BrushSpec.Linear(
            colors = listOf(Color.Blue, Color.Yellow), stops = listOf(0.5f, 1f),
            startX = 0f, startY = 0f, endX = 1f, endY = 0f,
        )
        // union of stop positions: 0, 0.5, 1
        val (colors, stops) = GradientInterpolator.lerpStops(a.colors, a.stops, b.colors, b.stops, 0f, ColorInterpolationSpace.SRGB)
        assertEquals(listOf(0f, 0.5f, 1f), stops)
        // at t=0 every stop carries A's color sampled at that position
        assertEquals(Color.Red, colors[0])
        // position 0.5 is halfway along A's ramp
        assertEquals(Color(0.5f, 0.5f, 0f), colors[1])
        assertEquals(Color.Green, colors[2])

        val (_, stopsAtOne) = GradientInterpolator.lerpStops(a.colors, a.stops, b.colors, b.stops, 1f, ColorInterpolationSpace.SRGB)
        assertEquals(listOf(0f, 0.5f, 1f), stopsAtOne)
    }

    @Test
    fun missingStopsAreDistributedEvenly() {
        val (colors, stops) = GradientInterpolator.lerpStops(
            listOf(Color.Red, Color.Green, Color.Blue), null,
            listOf(Color.Black, Color.White), null, 0f, ColorInterpolationSpace.SRGB,
        )
        assertEquals(listOf(0f, 0.5f, 1f), stops)
        assertEquals(3, colors.size)
    }

    @Test
    fun gradientEndpointsAreExactAtTZeroAndOne() {
        val a = linearGradientSpec(listOf(Color.Red, Color.Blue))
        val b = linearGradientSpec(listOf(Color.Green, Color.Yellow))
        val atZero = BrushSpec.from((GradientInterpolator.interpolate(a, b, 0f, ColorInterpolationSpace.SRGB) as GradientInterpolator.Result.Interpolated).brush) as BrushSpec.Linear
        assertEquals(Color.Red, atZero.colors[0])
        assertEquals(Color.Blue, atZero.colors[1])
        val atOne = BrushSpec.from((GradientInterpolator.interpolate(a, b, 1f, ColorInterpolationSpace.SRGB) as GradientInterpolator.Result.Interpolated).brush) as BrushSpec.Linear
        assertEquals(Color.Green, atOne.colors[0])
        assertEquals(Color.Yellow, atOne.colors[1])
    }
}
