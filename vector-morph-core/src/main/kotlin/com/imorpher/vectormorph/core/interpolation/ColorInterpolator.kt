package com.imorpher.vectormorph.core.interpolation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Perceptual color interpolation.
 *
 * Colors are NEVER lerped as packed ARGB integers. Depending on the configured
 * [ColorInterpolationSpace] they are converted to sRGB, linear sRGB, OKLab or OKLCH,
 * interpolated there, and converted back.
 *
 * OKLab matrices: Björn Ottosson (bottosson.github.io), public domain reference constants.
 */
object ColorInterpolator {

    fun lerp(from: Color, to: Color, t: Float, space: ColorInterpolationSpace): Color = when (space) {
        ColorInterpolationSpace.SRGB -> lerpSrgb(from, to, t)
        ColorInterpolationSpace.LINEAR_SRGB -> lerpLinearSrgb(from, to, t)
        ColorInterpolationSpace.OKLAB -> lerpOklab(from, to, t)
        ColorInterpolationSpace.OKLCH -> lerpOklch(from, to, t)
    }

    // ------------------------------------------------------------------ sRGB

    private fun lerpSrgb(a: Color, b: Color, t: Float): Color {
        val aSrgb = a.convert(ColorSpaces.Srgb)
        val bSrgb = b.convert(ColorSpaces.Srgb)
        return Color(
            red = aSrgb.red + (bSrgb.red - aSrgb.red) * t,
            green = aSrgb.green + (bSrgb.green - aSrgb.green) * t,
            blue = aSrgb.blue + (bSrgb.blue - aSrgb.blue) * t,
            alpha = a.alpha + (b.alpha - a.alpha) * t,
            colorSpace = ColorSpaces.Srgb,
        ).convert(a.colorSpace)
    }

    private fun lerpLinearSrgb(a: Color, b: Color, t: Float): Color {
        val ar = a.convert(ColorSpaces.LinearSrgb)
        val br = b.convert(ColorSpaces.LinearSrgb)
        val lerped = Color(
            red = ar.red + (br.red - ar.red) * t,
            green = ar.green + (br.green - ar.green) * t,
            blue = ar.blue + (br.blue - ar.blue) * t,
            alpha = a.alpha + (b.alpha - a.alpha) * t,
            colorSpace = ColorSpaces.LinearSrgb,
        )
        return lerped.convert(a.colorSpace)
    }

    // ------------------------------------------------------------------ OKLab

    fun srgbToOklab(c: Color): FloatArray {
        val lin = c.convert(ColorSpaces.LinearSrgb)
        val r = lin.red; val g = lin.green; val b = lin.blue

        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val l_ = cbrt(l)
        val m_ = cbrt(m)
        val s_ = cbrt(s)

        val L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        val A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        val B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        return floatArrayOf(L, A, B)
    }

    fun oklabToSrgb(lab: FloatArray, alpha: Float): Color {
        val L = lab[0]
        val A = lab[1]
        val B = lab[2]
        val l_ = L + 0.3963377774f * A + 0.2158037573f * B
        val m_ = L - 0.1055613458f * A - 0.0638541728f * B
        val s_ = L - 0.0894841775f * A - 1.2914855480f * B

        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        val r = +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s

        return Color(
            red = r.coerceIn(0f, 1f),
            green = g.coerceIn(0f, 1f),
            blue = b.coerceIn(0f, 1f),
            alpha = alpha,
            colorSpace = ColorSpaces.LinearSrgb,
        ).convert(ColorSpaces.Srgb)
    }

    private fun lerpOklab(a: Color, b: Color, t: Float): Color {
        val la = srgbToOklab(a)
        val lb = srgbToOklab(b)
        return oklabToSrgb(
            floatArrayOf(
                la[0] + (lb[0] - la[0]) * t,
                la[1] + (lb[1] - la[1]) * t,
                la[2] + (lb[2] - la[2]) * t,
            ),
            a.alpha + (b.alpha - a.alpha) * t,
        )
    }

    // ------------------------------------------------------------------ OKLCH

    fun oklabToOklch(lab: FloatArray): FloatArray {
        val L = lab[0]
        val A = lab[1]
        val B = lab[2]
        val C = sqrt(A * A + B * B)
        var H = kotlin.math.atan2(B, A)
        if (H < 0f) H += (2f * Math.PI).toFloat()
        return floatArrayOf(L, C, H)
    }

    fun oklchToOklab(lch: FloatArray): FloatArray {
        val h = lch[2]
        return floatArrayOf(
            lch[0],
            lch[1] * cos(h),
            lch[1] * sin(h),
        )
    }

    private fun lerpOklch(a: Color, b: Color, t: Float): Color {
        val la = oklabToOklch(srgbToOklab(a))
        val lb = oklabToOklch(srgbToOklab(b))
        // hue travels the short way around the wheel
        var dh = lb[2] - la[2]
        if (dh > Math.PI) dh -= (2f * Math.PI).toFloat()
        if (dh < -Math.PI) dh += (2f * Math.PI).toFloat()
        val lerped = floatArrayOf(
            la[0] + (lb[0] - la[0]) * t,
            la[1] + (lb[1] - la[1]) * t,
            la[2] + dh * t,
        )
        return oklabToSrgb(oklchToOklab(lerped), a.alpha + (b.alpha - a.alpha) * t)
    }

    // ------------------------------------------------------------------ utils

    private fun cbrt(x: Float): Float =
        if (x >= 0f) x.pow(1f / 3f) else -((-x).pow(1f / 3f))

    /** Shortest-path angular interpolation in degrees (350° → 10° goes through 360°, not backwards). */
    fun lerpAngleDeg(from: Float, to: Float, t: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return from + delta * t
    }
}
