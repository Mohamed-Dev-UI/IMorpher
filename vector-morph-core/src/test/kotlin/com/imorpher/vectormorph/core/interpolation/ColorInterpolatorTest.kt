package com.imorpher.vectormorph.core.interpolation

import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perceptual color interpolation in all four configured spaces. */
class ColorInterpolatorTest {

    @Test
    fun sRgbInterpolatesComponentWise() {
        val mid = ColorInterpolator.lerp(Color.Black, Color.White, 0.5f, ColorInterpolationSpace.SRGB)
        assertEquals(0.5f, mid.red, 0.01f)
        assertEquals(0.5f, mid.green, 0.01f)
        assertEquals(0.5f, mid.blue, 0.01f)

        val quarter = ColorInterpolator.lerp(Color.Black, Color.White, 0.25f, ColorInterpolationSpace.SRGB)
        assertEquals(0.25f, quarter.red, 0.01f)
    }

    @Test
    fun linearSrgbIsBrighterThanSRgbAtTheMidpoint() {
        val srgb = ColorInterpolator.lerp(Color.Black, Color.White, 0.5f, ColorInterpolationSpace.SRGB)
        val linear = ColorInterpolator.lerp(Color.Black, Color.White, 0.5f, ColorInterpolationSpace.LINEAR_SRGB)
        assertEquals(0.735f, linear.red, 0.02f)
        assertTrue(linear.red > srgb.red)
    }

    @Test
    fun oklabAndOklchStaysNeutralForGrayGradients() {
        for (space in listOf(ColorInterpolationSpace.OKLAB, ColorInterpolationSpace.OKLCH)) {
            val mid = ColorInterpolator.lerp(Color.Black, Color.White, 0.5f, space)
            assertEquals("gray ramp must stay gray in $space", mid.red, mid.blue, 0.02f)
            assertEquals(mid.red, mid.green, 0.02f)
            assertTrue("mid gray must be lighter than black (${mid.red})", mid.red > 0.3f)
            assertTrue("mid gray must be darker than white", mid.red < 0.65f)
        }
    }

    @Test
    fun oklabRoundTripsPrimaryColors() {
        for (color in listOf(Color.Red, Color(0xFF00E676), Color.Blue, Color.White, Color.Black)) {
            val lab = ColorInterpolator.srgbToOklab(color)
            val back = ColorInterpolator.oklabToSrgb(lab, color.alpha)
            assertEquals(color.red, back.red, 0.01f)
            assertEquals(color.green, back.green, 0.01f)
            assertEquals(color.blue, back.blue, 0.01f)
        }
    }

    @Test
    fun oklchConversionRoundTrips() {
        val lab = ColorInterpolator.srgbToOklab(Color.Red)
        val lch = ColorInterpolator.oklabToOklch(lab)
        val back = ColorInterpolator.oklchToOklab(lch)
        assertEquals(lab[0], back[0], 1e-4f)
        assertEquals(lab[1], back[1], 1e-4f)
        assertEquals(lab[2], back[2], 1e-4f)
        assertTrue("chroma of red must be positive", lch[1] > 0f)
    }

    @Test
    fun oklchHueTravelsTheShortWayAroundTheWheel() {
        // Hue 30° (orange) → hue 330° (pink): short path crosses 0°, not 180°.
        val from = ColorInterpolator.oklchToOklab(floatArrayOf(0.7f, 0.15f, 30f * Math.PI.toFloat() / 180f))
        val to = ColorInterpolator.oklchToOklab(floatArrayOf(0.7f, 0.15f, 330f * Math.PI.toFloat() / 180f))
        val fromColor = ColorInterpolator.oklabToSrgb(from, 1f)
        val toColor = ColorInterpolator.oklabToSrgb(to, 1f)
        val mid = ColorInterpolator.lerp(fromColor, toColor, 0.5f, ColorInterpolationSpace.OKLCH)
        val midLch = ColorInterpolator.oklabToOklch(ColorInterpolator.srgbToOklab(mid))
        // short route midpoint ≈ 0°/360°, long route would pass 180°
        val hueDegrees = midLch[2] * 180f / Math.PI.toFloat()
        assertTrue("hue should be near 0/360, was $hueDegrees", hueDegrees < 30f || hueDegrees > 330f)
    }

    @Test
    fun alphaInterpolatesInEverySpace() {
        val transparent = Color(0xFF000000).copy(alpha = 0f)
        val opaque = Color(0xFF000000).copy(alpha = 1f)
        for (space in ColorInterpolationSpace.entries) {
            val mid = ColorInterpolator.lerp(transparent, opaque, 0.5f, space)
            assertEquals("alpha must lerp in $space", 0.5f, mid.alpha, 0.01f)
        }
    }

    @Test
    fun endpointsAreExact() {
        val red = Color(0xFFE53935)
        val blue = Color(0xFF1E88E5)
        for (space in ColorInterpolationSpace.entries) {
            val atZero = ColorInterpolator.lerp(red, blue, 0f, space)
            val atOne = ColorInterpolator.lerp(red, blue, 1f, space)
            assertEquals("start color at t=0 in $space", red.red, atZero.red, 0.01f)
            assertEquals("start color at t=0 in $space", red.green, atZero.green, 0.01f)
            assertEquals("end color at t=1 in $space", blue.blue, atOne.blue, 0.01f)
        }
    }

    @Test
    fun angleLerpTakesTheShortArc() {
        assertEquals(360f, ColorInterpolator.lerpAngleDeg(350f, 10f, 0.5f), 1e-3f)
        assertEquals(0f, ColorInterpolator.lerpAngleDeg(10f, 350f, 0.5f), 1e-3f)
        assertEquals(25f, ColorInterpolator.lerpAngleDeg(10f, 40f, 0.5f), 1e-3f)
        // at t=0 the start comes back untouched; the result may exceed 360° but always
        // points the same way (370° ≡ 10°).
        assertEquals(350f, ColorInterpolator.lerpAngleDeg(350f, 10f, 0f), 1e-3f)
        assertEquals(370f, ColorInterpolator.lerpAngleDeg(350f, 10f, 1f), 1e-3f)
        // turns beyond one full circle collapse to their equivalent shortest arc
        // (450° ≡ 90°), so 0° → 450° at t=0.5 is 45°.
        assertEquals(45f, ColorInterpolator.lerpAngleDeg(0f, 450f, 0.5f), 1e-3f)
    }
}
