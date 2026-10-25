package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.plan.PathPairPlan
import com.imorpher.vectormorph.core.plan.interpolatedClipContours
import com.imorpher.vectormorph.core.reveal.DirectionMath
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Clip pass: applies inherited group clips, directional/radial clip reveals, and the moving
 * masks that reveal fills.
 *
 * The pass owns its scratch masks ([maskPath], [wedgePath], [radialPath]) and borrows contour
 * geometry through [pathOf], so clip drawing never builds a path twice. Helpers take the
 * [DrawScope] explicitly (inline member extensions cannot be invoked on a dispatch receiver
 * from another class), and stay inline: the per-frame reveal lambdas must not allocate.
 */
internal class ClipPass(
    /** Immutable contour geometry as a Compose [Path] (the renderer's static cache). */
    private val pathOf: (ContourData) -> Path,
) {

    /** Mask for directional/sweep fill and clip reveals. */
    private val maskPath = Path()

    /** Clip wedge for directional clip reveals. */
    private val wedgePath = Path()

    /** Clip circle for radial clip reveals. */
    private val radialPath = Path()

    /** Tight (minX, minY, maxX, maxY) of the contours' control points; zeros when empty. */
    fun boundsOf(contours: List<ContourData>): FloatArray {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        contours.forEach { contour -> contour.segments.forEach { s ->
            minX = min(minX, min(min(s.x0, s.x1), min(s.x2, s.x3)))
            minY = min(minY, min(min(s.y0, s.y1), min(s.y2, s.y3)))
            maxX = max(maxX, max(max(s.x0, s.x1), max(s.x2, s.x3)))
            maxY = max(maxY, max(max(s.y0, s.y1), max(s.y2, s.y3)))
        } }
        if (!minX.isFinite()) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * The clip contours one morphing pair paints with at [morphT]. Aligned group clip masks
     * morph with the geometry instead of switching at the midpoint: endpoints keep the exact
     * source/target masks (pixel-identical resting frames) and misaligned or one-sided clips
     * keep the historical switch to the active endpoint. Group clips live in viewport space,
     * so the active endpoint is used while its contents morph.
     */
    fun contoursFor(
        pair: PathPairPlan,
        from: PreparedPath,
        to: PreparedPath,
        morphT: Float,
    ): List<ContourData> = when {
        pair.clipPairs.isNotEmpty() && morphT > 0f && morphT < 1f -> pair.interpolatedClipContours(morphT)
        morphT < 0.5f -> from.clipContours
        else -> to.clipContours
    }

    /**
     * Clips [block] to the pair's inherited group clip paths, optionally revealing the clip
     * itself along a directional moving edge when [progress] is mid-reveal. No contours or
     * full progress draw unclipped.
     */
    internal inline fun withClipReveal(
        drawScope: DrawScope,
        contours: List<ContourData>,
        progress: Float,
        direction: DrawDirection,
        block: DrawScope.() -> Unit,
    ) {
        if (contours.isEmpty()) { drawScope.block(); return }
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.003f) return
        val box = boundsOf(contours)
        maskPath.rewind()
        contours.forEach { maskPath.addPath(pathOf(it)) }
        drawScope.clipPath(maskPath) {
            if (p >= 0.997f) {
                block()
            } else {
                val axis = DirectionMath.axisFor(direction)
                val width = (box[2] - box[0]).coerceAtLeast(1e-3f)
                val height = (box[3] - box[1]).coerceAtLeast(1e-3f)
                val ax = box[0] + axis[0] * width; val ay = box[1] + axis[1] * height
                val bx = box[0] + axis[2] * width; val by = box[1] + axis[3] * height
                val ex = ax + (bx - ax) * p; val ey = ay + (by - ay) * p
                var nx = -(by - ay); var ny = bx - ax
                val normalLength = hypot(nx, ny).coerceAtLeast(1e-6f)
                val reach = hypot(width, height) * 4f
                nx = nx / normalLength * reach; ny = ny / normalLength * reach
                wedgePath.rewind()
                wedgePath.moveTo(ax + nx, ay + ny)
                wedgePath.lineTo(ex + nx, ey + ny)
                wedgePath.lineTo(ex - nx, ey - ny)
                wedgePath.lineTo(ax - nx, ay - ny)
                wedgePath.close()
                clipPath(wedgePath) { block() }
            }
        }
    }

    /**
     * Clips [block] to an expanding circle centered in the given bounds — the fill-mode
     * RADIAL and DrawMode.RADIAL reveal implementation.
     */
    internal inline fun withRadialClip(
        drawScope: DrawScope,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        progress: Float,
        block: DrawScope.() -> Unit,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.003f) return
        if (p >= 0.997f) { drawScope.block(); return }
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val radius = hypot(maxX - minX, maxY - minY) * 0.5f * p
        radialPath.rewind()
        radialPath.addOval(Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius))
        drawScope.clipPath(radialPath) { block() }
    }

    /**
     * Clips [block] to the portion of the path box already reached by a fill reveal at
     * [progress]: directional/sweep masks grow across the interpolated bounds, radial
     * grows from the center. Used when a stroke reveal owns the whole path — the fill
     * sweeps in behind the pen without touching the brush itself (gradients stay true).
     *
     * Behind-the-pen sweeps are hard-edged by design; feathering applies to standalone
     * fills, i.e. when the reveal is owned by a fill-reveal track rather than a pen:
     * those equivalence-for-DIRECTIONAL callers pass [feather] = true, which routes the
     * directional arm through [featheredSweep] (a gradient alpha ramp) instead of the clip.
     */
    internal inline fun withFillRevealClip(
        drawScope: DrawScope,
        progress: Float,
        mode: FillMode,
        direction: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        feather: Boolean = false,
        crossinline block: DrawScope.() -> Unit,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p >= 0.997f) { drawScope.block(); return }
        if (p <= 0.003f) return
        val w = (bMaxX - bMinX).coerceAtLeast(1e-3f)
        val h = (bMaxY - bMinY).coerceAtLeast(1e-3f)
        when {
            mode == FillMode.DIRECTIONAL && feather ->
                featheredSweep(drawScope, p, direction, bMinX, bMinY, bMaxX, bMaxY) { scope -> scope.block() }
            mode == FillMode.RADIAL -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = p * (hypot(w, h) / 2f)
                maskPath.rewind()
                maskPath.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                drawScope.clipPath(maskPath) { block() }
            }
            mode == FillMode.SWEEP -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = hypot(w, h)
                val clockwise = direction != DrawDirection.COUNTER_CLOCKWISE
                maskPath.rewind()
                maskPath.moveTo(cx, cy)
                maskPath.arcTo(
                    rect = Rect(cx - r, cy - r, cx + r, cy + r),
                    startAngleDegrees = DirectionMath.sweepStartAngle(direction),
                    sweepAngleDegrees = (if (clockwise) 1f else -1f) * 360f * p,
                    forceMoveTo = false,
                )
                maskPath.close()
                drawScope.clipPath(maskPath) { block() }
            }
            mode == FillMode.FADE -> drawScope.block()
            else -> {
                val axis = DirectionMath.axisFor(direction)
                val ax0 = bMinX + axis[0] * w; val ay0 = bMinY + axis[1] * h
                val ax1 = bMinX + axis[2] * w; val ay1 = bMinY + axis[3] * h
                val ex = ax0 + (ax1 - ax0) * p
                val ey = ay0 + (ay1 - ay0) * p
                var nx = -(ay1 - ay0); var ny = (ax1 - ax0)
                val nLen = hypot(nx, ny).coerceAtLeast(1e-6f)
                nx /= nLen; ny /= nLen
                val reach = hypot(w, h) * 2f
                maskPath.rewind()
                maskPath.moveTo(ex, ey)
                maskPath.lineTo(ex + nx * reach, ey + ny * reach)
                maskPath.lineTo(ex + (ax0 - ax1) * 4f + nx * reach, ey + (ay0 - ay1) * 4f + ny * reach)
                maskPath.lineTo(ex + (ax0 - ax1) * 4f - nx * reach, ey + (ay0 - ay1) * 4f - ny * reach)
                maskPath.lineTo(ex - nx * reach, ey - ny * reach)
                maskPath.close()
                drawScope.clipPath(maskPath) { block() }
            }
        }
    }

    /**
     * Paints [paintBlock] into an offscreen layer, then multiplies the destination alpha by a
     * linear ramp straddling the moving edge of a directional reveal: fully opaque behind the
     * edge (ramp fraction 0.5), a `0.15 × axis length` band ramping down in front, fully
     * transparent ahead. The layer composites back untouched everywhere else, and the
     * underlying brush is never a mask — gradients and paints stay exactly as authored.
     */
    internal fun featheredSweep(
        drawScope: DrawScope,
        progress: Float,
        direction: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        paintBlock: (DrawScope) -> Unit,
    ) {
        val w = (bMaxX - bMinX).coerceAtLeast(1e-3f)
        val h = (bMaxY - bMinY).coerceAtLeast(1e-3f)
        val axis = DirectionMath.axisFor(direction)
        val ax0 = bMinX + axis[0] * w; val ay0 = bMinY + axis[1] * h
        val ax1 = bMinX + axis[2] * w; val ay1 = bMinY + axis[3] * h
        val axisLen = hypot(ax1 - ax0, ay1 - ay0).coerceAtLeast(1e-3f)
        val band = 0.15f * axisLen
        val ux = (ax1 - ax0) / axisLen
        val uy = (ay1 - ay0) / axisLen
        val ex = ax0 + (ax1 - ax0) * progress
        val ey = ay0 + (ay1 - ay0) * progress
        val inflate = band.coerceAtLeast(2f)
        val layerBounds = Rect(bMinX - inflate, bMinY - inflate, bMaxX + inflate, bMaxY + inflate)
        drawScope.drawIntoCanvas { canvas ->
            canvas.saveLayer(layerBounds, Paint())
            paintBlock(drawScope)
            // Color is irrelevant to DstIn; only the gradient's alpha multiplies the layer.
            drawScope.drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Black,
                        0.5f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    start = Offset(ex - ux * band, ey - uy * band),
                    end = Offset(ex + ux * band, ey + uy * band),
                ),
                topLeft = Offset(layerBounds.left, layerBounds.top),
                size = Size(layerBounds.width, layerBounds.height),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}
