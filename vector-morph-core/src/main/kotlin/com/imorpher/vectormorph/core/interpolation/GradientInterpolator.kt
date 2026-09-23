package com.imorpher.vectormorph.core.interpolation

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import kotlin.math.max

/**
 * Interpolates [BrushSpec]s: gradient → gradient, solid → gradient, gradient → solid,
 * in any configured color space. Stops are re-sampled onto the union of both gradients' stop
 * positions (never by inventing geometry) so both sides keep their original look at t=0 and t=1.
 * Pairs that cannot be interpolated (unknown brush kinds, linear vs radial etc.) crossfade.
 */
object GradientInterpolator {

    /** The resolved result of interpolating two brush specs at some fraction. */
    sealed class Result {
        /** One interpolated brush, drawn with the given extra alpha multiplier. */
        data class Interpolated(val brush: Brush, val alpha: Float) : Result()

        /** Two brushes drawn on top of each other with complementary alphas. */
        data class Crossfade(val fromBrush: Brush, val toBrush: Brush, val progress: Float) : Result()

        /** Nothing to draw (both sides empty). */
        object None : Result()
    }

    fun interpolate(
        from: BrushSpec?,
        to: BrushSpec?,
        t: Float,
        space: ColorInterpolationSpace,
    ): Result = when {
        from == null && to == null -> Result.None
        from == null -> Result.Crossfade(transparentBrush(), to!!.buildBrush(), t.coerceIn(0f, 1f))
        to == null -> Result.Crossfade(from.buildBrush(), transparentBrush(), t.coerceIn(0f, 1f))
        from is BrushSpec.Unknown || to is BrushSpec.Unknown ->
            Result.Crossfade(from.buildBrush(), to.buildBrush(), t.coerceIn(0f, 1f))
        from is BrushSpec.Solid && to is BrushSpec.Solid ->
            Result.Interpolated(
                SolidColor(ColorInterpolator.lerp(from.color, to.color, t, space)),
                1f,
            )
        from is BrushSpec.Solid || to is BrushSpec.Solid -> {
            // solid ↔ gradient: promote the solid to a 2-stop gradient of the other kind
            val a = if (from is BrushSpec.Solid) promoteSolid(from, to) else from
            val b = if (to is BrushSpec.Solid) promoteSolid(to, from) else to
            if (a != null && b != null && compatibleKinds(a, b)) {
                Result.Interpolated(lerpSameKind(a, b, t, space), 1f)
            } else {
                Result.Crossfade(from.buildBrush(), to.buildBrush(), t.coerceIn(0f, 1f))
            }
        }
        compatibleKinds(from, to) -> Result.Interpolated(lerpSameKind(from, to, t, space), 1f)
        else -> Result.Crossfade(from.buildBrush(), to.buildBrush(), t.coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------------ kind handling

    private fun compatibleKinds(a: BrushSpec, b: BrushSpec): Boolean =
        (a is BrushSpec.Linear && b is BrushSpec.Linear) ||
            (a is BrushSpec.Radial && b is BrushSpec.Radial) ||
            (a is BrushSpec.Sweep && b is BrushSpec.Sweep)

    /** Promotes a solid color to a flat 2-stop gradient shaped like [like]. */
    private fun promoteSolid(solid: BrushSpec.Solid, like: BrushSpec): BrushSpec? = when (like) {
        is BrushSpec.Linear -> BrushSpec.Linear(
            colors = listOf(solid.color, solid.color),
            stops = listOf(0f, 1f),
            startX = like.startX, startY = like.startY,
            endX = like.endX, endY = like.endY,
            angleDegrees = like.angleDegrees,
        )
        is BrushSpec.Radial -> BrushSpec.Radial(
            colors = listOf(solid.color, solid.color),
            stops = listOf(0f, 1f),
            centerX = like.centerX, centerY = like.centerY,
            radius = like.radius,
        )
        is BrushSpec.Sweep -> BrushSpec.Sweep(
            colors = listOf(solid.color, solid.color),
            stops = listOf(0f, 1f),
            centerX = like.centerX, centerY = like.centerY,
        )
        else -> null
    }

    private fun lerpSameKind(a: BrushSpec, b: BrushSpec, t: Float, space: ColorInterpolationSpace): Brush =
        when {
            a is BrushSpec.Linear && b is BrushSpec.Linear -> {
                val (colors, stops) = lerpStops(a.colors, a.stops, b.colors, b.stops, t, space)
                val start: Offset
                val end: Offset
                if (a.angleDegrees != null && b.angleDegrees != null) {
                    val centerX = lerpFloat((a.startX + a.endX) * 0.5f, (b.startX + b.endX) * 0.5f, t)
                    val centerY = lerpFloat((a.startY + a.endY) * 0.5f, (b.startY + b.endY) * 0.5f, t)
                    val lengthA = kotlin.math.hypot(a.endX - a.startX, a.endY - a.startY)
                    val lengthB = kotlin.math.hypot(b.endX - b.startX, b.endY - b.startY)
                    val length = lerpFloat(lengthA, lengthB, t)
                    val radians = Math.toRadians(ColorInterpolator.lerpAngleDeg(a.angleDegrees, b.angleDegrees, t).toDouble())
                    val dx = kotlin.math.cos(radians).toFloat() * length * 0.5f
                    val dy = kotlin.math.sin(radians).toFloat() * length * 0.5f
                    start = Offset(centerX - dx, centerY - dy)
                    end = Offset(centerX + dx, centerY + dy)
                } else {
                    start = Offset(a.startX + (b.startX - a.startX) * t, a.startY + (b.startY - a.startY) * t)
                    end = Offset(a.endX + (b.endX - a.endX) * t, a.endY + (b.endY - a.endY) * t)
                }
                Brush.linearGradient(*stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(), start = start, end = end)
            }
            a is BrushSpec.Radial && b is BrushSpec.Radial -> {
                val (colors, stops) = lerpStops(a.colors, a.stops, b.colors, b.stops, t, space)
                Brush.radialGradient(
                    *stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(),
                    center = Offset(a.centerX + (b.centerX - a.centerX) * t, a.centerY + (b.centerY - a.centerY) * t),
                    radius = max(0.01f, a.radius + (b.radius - a.radius) * t),
                )
            }
            a is BrushSpec.Sweep && b is BrushSpec.Sweep -> {
                val (colors, stops) = lerpStops(a.colors, a.stops, b.colors, b.stops, t, space)
                Brush.sweepGradient(
                    *stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(),
                    center = Offset(a.centerX + (b.centerX - a.centerX) * t, a.centerY + (b.centerY - a.centerY) * t),
                )
            }
            else -> SolidColor(Color.Gray) // unreachable after compatibleKinds guard
        }

    // ------------------------------------------------------------------ stops

    internal fun lerpStops(
        colorsA: List<Color>,
        stopsA: List<Float>?,
        colorsB: List<Color>,
        stopsB: List<Float>?,
        t: Float,
        space: ColorInterpolationSpace,
    ): Pair<List<Color>, List<Float>> {
        val normA = normalizeStops(colorsA, stopsA)
        val normB = normalizeStops(colorsB, stopsB)

        val positions = sortedSetOf<Float>()
        normA.forEach { positions.add(it.first) }
        normB.forEach { positions.add(it.first) }

        val outColors = ArrayList<Color>(positions.size)
        val outStops = ArrayList<Float>(positions.size)
        var ia = 0
        var ib = 0
        for (p in positions) {
            while (ia < normA.size - 1 && normA[ia + 1].first <= p) ia++
            while (ib < normB.size - 1 && normB[ib + 1].first <= p) ib++
            val ca = sampleAt(normA, p, ia, space)
            val cb = sampleAt(normB, p, ib, space)
            outColors.add(ColorInterpolator.lerp(ca, cb, t, space))
            outStops.add(p)
        }
        return outColors to outStops
    }

    private fun normalizeStops(colors: List<Color>, stops: List<Float>?): List<Pair<Float, Color>> {
        return if (stops == null || stops.size != colors.size) {
            val n = colors.size
            colors.mapIndexed { i, c ->
                (if (n <= 1) 0f else i.toFloat() / (n - 1)) to c
            }
        } else {
            colors.mapIndexed { i, c -> stops[i].coerceIn(0f, 1f) to c }
        }
    }

    private fun sampleAt(stops: List<Pair<Float, Color>>, p: Float, idx: Int, space: ColorInterpolationSpace): Color {
        val s = stops[idx]
        if (s.first >= p || idx == stops.size - 1) return s.second
        val next = stops[idx + 1]
        val span = next.first - s.first
        if (span <= 0f) return next.second
        val f = (p - s.first) / span
        return ColorInterpolator.lerp(s.second, next.second, f, space)
    }

    // ------------------------------------------------------------------ brush building

    fun BrushSpec.buildBrush(): Brush = when (this) {
        is BrushSpec.Solid -> SolidColor(color)
        is BrushSpec.Linear -> if (stops == null) {
            Brush.linearGradient(colors, start = Offset(startX, startY), end = Offset(endX, endY))
        } else {
            Brush.linearGradient(*stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(), start = Offset(startX, startY), end = Offset(endX, endY))
        }
        is BrushSpec.Radial -> if (stops == null) {
            Brush.radialGradient(colors, center = Offset(centerX, centerY), radius = max(0.01f, radius))
        } else {
            Brush.radialGradient(*stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(), center = Offset(centerX, centerY), radius = max(0.01f, radius))
        }
        is BrushSpec.Sweep -> if (stops == null) {
            Brush.sweepGradient(colors, center = Offset(centerX, centerY))
        } else {
            Brush.sweepGradient(*stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(), center = Offset(centerX, centerY))
        }
        is BrushSpec.Unknown -> brush
    }

    private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun transparentBrush(): Brush = SolidColor(Color.Transparent)
}
