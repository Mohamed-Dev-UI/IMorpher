package com.imorpher.vectormorph.core.animation

import androidx.compose.animation.core.Easing
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A spring converted into an [Easing]: the spring is numerically integrated once at
 * construction and baked into a sampled curve, so per-frame evaluation stays a pure
 * function of progress (deterministic rendering, no simulation state).
 *
 * Physical parameters mirror Compose's spring: mass = 1, [stiffness] in N/m,
 * [dampingRatio] where 1 = critically damped, < 1 underdamped (bouncy), > 1 overdamped.
 */
class SpringEasing(
    val dampingRatio: Float = 1f,
    val stiffness: Float = 400f,
    /** Visibility threshold that ends the simulation (fraction of the range). */
    visibilityThreshold: Float = 0.01f,
    samples: Int = 256,
) : Easing {

    private val table = FloatArray(samples + 1)
    /** Normalized "settling time" of the baked spring. */
    val settleDurationSeconds: Float

    init {
        val omega = sqrt(stiffness) // omega0, mass = 1
        val zeta = dampingRatio
        val dt = 1f / 480f

        var x = 0f          // position (target = 1)
        var v = 0f          // velocity
        var t = 0f
        val maxT = 10f      // hard safety cap on simulation time

        while (t < maxT) {
            // semi-implicit Euler is plenty stable for these stiffness ranges
            val a = -2f * zeta * omega * v - omega * omega * (x - 1f)
            v += a * dt
            x += v * dt
            t += dt
            if (kotlin.math.abs(1f - x) < visibilityThreshold && kotlin.math.abs(v) < visibilityThreshold * omega) {
                break
            }
        }
        settleDurationSeconds = t

        // Bake normalized time → value. Time is normalized by settle duration so the easing
        // completes (reaching ~1) at u = 1 regardless of stiffness.
        for (i in 0..samples) {
            val u = i.toFloat() / samples
            val simT = u * t
            val value = springValueAt(simT, omega, zeta, samples)
            table[i] = value
        }
    }

    /** Analytic solution of the damped harmonic oscillator towards 1. */
    private fun springValueAt(time: Float, omega: Float, zeta: Float, maxIter: Int): Float {
        val x = omega * zeta * time
        return when {
            zeta < 1f -> {
                // underdamped
                val omegaD = omega * sqrt(1f - zeta * zeta)
                1f - (kotlin.math.cos(omegaD * time) + (zeta * omega / omegaD) * kotlin.math.sin(omegaD * time)) *
                    kotlin.math.exp(-x)
            }
            zeta > 1f -> {
                // overdamped
                val s = omega * sqrt(zeta * zeta - 1f)
                val a = (s + zeta * omega) / (2f * s)
                val b = (s - zeta * omega) / (2f * s)
                1f - a * kotlin.math.exp(-(zeta * omega - s) * time) - b * kotlin.math.exp(-(zeta * omega + s) * time)
            }
            else -> {
                // critically damped
                1f - (1f + omega * time) * kotlin.math.exp(-omega * time)
            }
        }
    }

    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val pos = fraction * (table.size - 1)
        val i = pos.toInt()
        val f = pos - i
        val a = table[i]
        val b = table[(i + 1).coerceAtMost(table.size - 1)]
        return a + (b - a) * f
    }

    companion object {
        /** Matches `spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)`. */
        val MediumBouncy: SpringEasing = SpringEasing(0.5f, 1500f / 50f)

        val LowBouncy: SpringEasing = SpringEasing(0.75f, 400f)

        val NoBouncy: SpringEasing = SpringEasing(1f, 400f)

        fun of(dampingRatio: Float, stiffness: Float): SpringEasing =
            SpringEasing(dampingRatio, stiffness)
    }
}
