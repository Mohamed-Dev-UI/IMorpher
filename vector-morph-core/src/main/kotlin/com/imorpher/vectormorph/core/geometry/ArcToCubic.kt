package com.imorpher.vectormorph.core.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts an SVG/PathNode elliptical arc (endpoint parameterization) into cubic Bézier segments
 * using the standard algorithm (W3C SVG spec appendix F.6.5, F.6.4). Up to four segments are
 * produced, each spanning ≤ 90°, which keeps the cubic approximation error negligible
 * (max radial error < 0.027% for 90° arcs).
 */
object ArcToCubic {

    /**
     * @param rx, ry    ellipse radii
     * @param thetaDeg  x-axis rotation of the ellipse in degrees
     * @param largeArc  choose the arc > 180° when true
     * @param sweep     positive-angle (clockwise in Y-down screens) direction when true
     * @param x0, y0    current point
     * @param x1, y1    end point
     */
    fun convert(
        rxIn: Float, ryIn: Float, thetaDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        x0: Float, y0: Float, x1: Float, y1: Float,
    ): List<Cubic> {
        val out = ArrayList<Cubic>(4)
        if (abs(x1 - x0) < 1e-9f && abs(y1 - y0) < 1e-9f) return out // zero-length arc

        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < 1e-9f || ry < 1e-9f) {
            out.add(Cubic.line(x0, y0, x1, y1))
            return out
        }

        val phi = Math.toRadians(thetaDeg.toDouble())
        val cosPhi = cos(phi).toFloat()
        val sinPhi = sin(phi).toFloat()

        val dx2 = (x0 - x1) / 2f
        val dy2 = (y0 - y1) / 2f

        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        // Scale up radii if the ellipse is too small (F.6.6)
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1f) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        val rx2 = rx * rx
        val ry2 = ry * ry
        val x1p2 = x1p * x1p
        val y1p2 = y1p * y1p

        var denom = rx2 * y1p2 + ry2 * x1p2
        if (denom < 1e-12f) {
            out.add(Cubic.line(x0, y0, x1, y1))
            return out
        }
        var num = rx2 * ry2 - denom
        if (num < 0f) num = 0f
        var co = sqrt(num / denom)
        if (largeArc == sweep) co = -co

        val cxp = co * rx * y1p / ry
        val cyp = -co * ry * x1p / rx

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2f
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2f

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry

        val uLen = sqrt(ux * ux + uy * uy).coerceAtLeast(1e-9f)
        val vLen = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-9f)

        // angle between u and v
        val dot = (ux * vx + uy * vy) / (uLen * vLen)
        val angleUv = acos(dot.coerceIn(-1f, 1f))
        var theta1 = angleOf(ux, uy)
        var deltaTheta = angleOf(vx, vy) - theta1
        if (!sweep && deltaTheta > 0f) {
            deltaTheta -= 2f * Math.PI.toFloat()
        } else if (sweep && deltaTheta < 0f) {
            deltaTheta += 2f * Math.PI.toFloat()
        }

        // Split into segments of ≤ 90°
        val segments = (kotlin.math.ceil(abs(deltaTheta) / (Math.PI / 2.0)).toInt()).coerceIn(1, 4)
        val delta = deltaTheta / segments
        val t = 4f / 3f * kotlin.math.tan(delta / 4f)

        var ex = 0f; var ey = 0f // derivative at segment start (in ellipse unit space)
        var startAngle = theta1
        var cosStart = cos(theta1).toFloat()
        var sinStart = sin(theta1).toFloat()
        // point on ellipse at theta1
        var px = cx + rx * cosPhi * cosStart - ry * sinPhi * sinStart
        var py = cy + rx * sinPhi * cosStart + ry * cosPhi * sinStart

        for (i in 0 until segments) {
            val endAngle = startAngle + delta
            val cosEnd = cos(endAngle).toFloat()
            val sinEnd = sin(endAngle).toFloat()

            val qx = cx + rx * cosPhi * cosEnd - ry * sinPhi * sinEnd
            val qy = cy + rx * sinPhi * cosEnd + ry * cosPhi * sinEnd

            // derivative at start/end: d/dθ (rx cosθ, ry sinθ) rotated by phi
            val dx1 = -rx * cosPhi * sinStart - ry * sinPhi * cosStart
            val dy1 = -rx * sinPhi * sinStart + ry * cosPhi * cosStart
            val dx2e = -rx * cosPhi * sinEnd - ry * sinPhi * cosEnd
            val dy2e = -rx * sinPhi * sinEnd + ry * cosPhi * cosEnd

            val c1x = px + t * dx1
            val c1y = py + t * dy1
            val c2x = qx - t * dx2e
            val c2y = qy - t * dy2e

            out.add(Cubic(px, py, c1x, c1y, c2x, c2y, qx, qy))

            px = qx; py = qy
            startAngle = endAngle
            cosStart = cosEnd; sinStart = sinEnd
        }
        return out
    }

    private fun angleOf(x: Float, y: Float): Float {
        val angle = kotlin.math.atan2(y, x)
        return if (angle < 0f) angle + 2f * Math.PI.toFloat() else angle
    }
}
