package com.imorpher.vectormorph.core.gradients

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.RadialGradient
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.SweepGradient
import androidx.compose.ui.geometry.Offset

/**
 * A brush described in an animatable, interpolatable form.
 *
 * `ImageVector` paths carry Compose [Brush]es (SolidColor, LinearGradient, RadialGradient,
 * SweepGradient). Those are rendered objects; to animate them the engine extracts their
 * parameters into a [BrushSpec], interpolates specs, and rebuilds a Brush at render time.
 */
sealed class BrushSpec {

    data class Solid(val color: Color) : BrushSpec()

    data class Linear(
        val colors: List<Color>,
        val stops: List<Float>?,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        /** Optional angle-parameterized gradient; interpolated over the shortest angular arc. */
        val angleDegrees: Float? = null,
    ) : BrushSpec()

    data class Radial(
        val colors: List<Color>,
        val stops: List<Float>?,
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
    ) : BrushSpec()

    data class Sweep(
        val colors: List<Color>,
        val stops: List<Float>?,
        val centerX: Float,
        val centerY: Float,
    ) : BrushSpec()

    /**
     * A brush the engine cannot parametrize (custom Brush subclasses). It still renders, but
     * pairs involving it degrade to a crossfade instead of parameter interpolation.
     */
    data class Unknown(val brush: Brush) : BrushSpec()

    val isUnknown: Boolean get() = this is Unknown

    companion object {
        /** Extracts an animatable spec from a rendered [Brush]; null when [brush] is null. */
        fun from(brush: Brush?): BrushSpec? = when (brush) {
            null -> null
            is SolidColor -> Solid(brush.value)
            is LinearGradient -> {
                val colors = readField<List<Color>>(brush, "colors")
                val start = readOffset(brush, "start")
                val end = readOffset(brush, "end")
                if (colors == null || start == null || end == null) Unknown(brush) else Linear(
                    colors = colors.toList(),
                    stops = readField<List<Float>>(brush, "stops")?.toList(),
                    startX = start.x, startY = start.y,
                    endX = end.x, endY = end.y,
                )
            }
            is RadialGradient -> {
                val colors = readField<List<Color>>(brush, "colors")
                val center = readOffset(brush, "center")
                val radius = readField<Number>(brush, "radius")?.toFloat()
                if (colors == null || center == null || radius == null) Unknown(brush) else Radial(
                    colors = colors.toList(),
                    stops = readField<List<Float>>(brush, "stops")?.toList(),
                    centerX = center.x, centerY = center.y,
                    radius = radius,
                )
            }
            is SweepGradient -> {
                val colors = readField<List<Color>>(brush, "colors")
                val center = readOffset(brush, "center")
                if (colors == null || center == null) Unknown(brush) else Sweep(
                    colors = colors.toList(),
                    stops = readField<List<Float>>(brush, "stops")?.toList(),
                    centerX = center.x, centerY = center.y,
                )
            }
            else -> Unknown(brush)
        }

        /** Treats a solid color as a two-stop gradient so it can lerp against real gradients. */
        fun asGradientOrNull(spec: BrushSpec?): BrushSpec? = when (spec) {
            null -> null
            is Solid -> Linear(
                colors = listOf(spec.color, spec.color),
                stops = listOf(0f, 1f),
                startX = 0f, startY = 0f, endX = 1f, endY = 0f,
            )
            else -> spec
        }

        /** Compose intentionally exposes gradient construction but keeps shader parameters internal. */
        private fun <T> readField(instance: Any, name: String): T? {
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                try {
                    val field = type.getDeclaredField(name)
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    return field.get(instance) as? T
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                } catch (_: ReflectiveOperationException) {
                    return null
                } catch (_: SecurityException) {
                    return null
                }
            }
            return null
        }

        private fun readOffset(instance: Any, name: String): Offset? = when (val packed = readField<Any>(instance, name)) {
            is Offset -> packed
            is Number -> {
                val value = packed.toLong()
                Offset(
                    Float.fromBits((value shr 32).toInt()),
                    Float.fromBits(value.toInt()),
                )
            }
            else -> null
        }
    }
}

/** A single animated gradient stop. */
data class GradientStop(val color: Color, val position: Float)

/** Convenience factory mirroring the spec's `LinearGradient(colors = ...)` pseudo-API. */
fun linearGradientSpec(colors: List<Color>, startX: Float = 0f, startY: Float = 0f, endX: Float = 1f, endY: Float = 0f) =
    BrushSpec.Linear(colors = colors, stops = null, startX = startX, startY = startY, endX = endX, endY = endY)

/** Creates a viewport-coordinate linear gradient from an angle in degrees (positive clockwise). */
fun linearGradientSpecAtAngle(
    colors: List<Color>,
    angleDegrees: Float,
    length: Float = 1f,
    centerX: Float = 0.5f,
    centerY: Float = 0.5f,
    stops: List<Float>? = null,
): BrushSpec.Linear {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val dx = kotlin.math.cos(radians).toFloat() * length / 2f
    val dy = kotlin.math.sin(radians).toFloat() * length / 2f
    return BrushSpec.Linear(
        colors, stops, centerX - dx, centerY - dy, centerX + dx, centerY + dy,
        angleDegrees = angleDegrees,
    )
}

fun radialGradientSpec(colors: List<Color>, centerX: Float = 0.5f, centerY: Float = 0.5f, radius: Float = 0.7f) =
    BrushSpec.Radial(colors = colors, stops = null, centerX = centerX, centerY = centerY, radius = radius)

fun sweepGradientSpec(colors: List<Color>, centerX: Float = 0.5f, centerY: Float = 0.5f) =
    BrushSpec.Sweep(colors = colors, stops = null, centerX = centerX, centerY = centerY)
