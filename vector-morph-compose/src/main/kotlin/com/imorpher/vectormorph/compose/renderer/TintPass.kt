package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.plan.MorphPlan
import kotlin.math.hypot
import kotlin.math.max

/**
 * Tint pass: recolors a frame by drawing the icon into an offscreen layer and repainting it
 * with the tint interpolated between the two endpoint values.
 *
 * The pass owns the layer paint and the endpoint-spec memo (reflective `BrushSpec` extraction
 * runs once per distinct value identity + draw size). Colors and brushes are accepted; anything
 * else disables its endpoint. Layer bounds are tight (icon rect + stroke/transform margin)
 * instead of a full viewport of padding, and a solid tint recolors the layer through a color
 * filter rather than a full-canvas `SrcIn` rect.
 */
internal class TintPass {

    /** Reused paint for the tint saveLayer (nothing allocated per frame). */
    private val layerPaint = Paint()

    // Endpoint specs are derived from caller values (Color/Brush); cache them so the
    // reflective BrushSpec extraction runs once per distinct (value, draw size) pair.

    /** Last from-endpoint tint value seen (identity comparison). */
    private var cachedFromSource: Any? = null

    /** Draw size the from-endpoint spec was resolved against (packed floats). */
    private var cachedFromSize: Long = 0L

    /** Resolved from-endpoint spec, reused while [cachedFromSource] and size are stable. */
    private var cachedFromSpec: BrushSpec? = null

    /** Last to-endpoint tint value seen (identity comparison). */
    private var cachedToSource: Any? = null

    /** Draw size the to-endpoint spec was resolved against (packed floats). */
    private var cachedToSize: Long = 0L

    /** Resolved to-endpoint spec, reused while [cachedToSource] and size are stable. */
    private var cachedToSpec: BrushSpec? = null

    /** Resolves both endpoints and interpolates them at [progress]; null disables tinting. */
    fun resultFor(
        tintFrom: Any?,
        tintTo: Any?,
        drawSize: Size,
        progress: Float,
        colorSpace: ColorInterpolationSpace,
    ): GradientInterpolator.Result? {
        val fromSpec = specOf(tintFrom, drawSize, fromEndpoint = true)
        val toSpec = specOf(tintTo, drawSize, fromEndpoint = false)
        return if (fromSpec != null || toSpec != null) {
            GradientInterpolator.interpolate(fromSpec, toSpec, progress, colorSpace)
        } else null
    }

    /**
     * Paints [drawContents] into a tinted offscreen layer on [drawScope].
     *
     * The layer is created before any transform is applied and the tint rects are drawn after
     * the contents restore the canvas matrix, so tint gradient coordinates resolve against the
     * icon's layout bounds — the same convention as `Modifier.background(brush)`.
     */
    fun paint(
        drawScope: DrawScope,
        result: GradientInterpolator.Result,
        plan: MorphPlan,
        contentWidth: Float,
        contentHeight: Float,
        uniformScale: Float,
        dx: Float,
        dy: Float,
        drawContents: () -> Unit,
    ) {
        val canvas = drawScope.drawContext.canvas
        // Tight layer bounds: the icon rect plus a margin for strokes and animated transforms,
        // instead of a full viewport of padding on every side. saveLayer cost scales with the
        // layer area, so this is the dominant tint optimization for large icon surfaces.
        val margin = max(contentWidth, contentHeight) * 0.5f + maxStrokeWidth(plan) * uniformScale
        val layerRect = Rect(
            dx - margin,
            dy - margin,
            dx + contentWidth + margin,
            dy + contentHeight + margin,
        )
        // Solid tints need no SrcIn repaint rect: the layer's color filter recolors every pixel
        // while keeping its alpha, exactly like the rect path but without a full-canvas draw.
        val solidTint = ((result as? GradientInterpolator.Result.Interpolated)?.brush as? SolidColor)
        if (solidTint != null) {
            layerPaint.colorFilter = ColorFilter.tint(solidTint.value, BlendMode.SrcIn)
        }
        canvas.saveLayer(layerRect, layerPaint)
        try {
            drawContents()
            when (result) {
                is GradientInterpolator.Result.Interpolated -> if (solidTint == null) drawScope.drawRect(
                    brush = result.brush,
                    topLeft = Offset.Zero,
                    size = drawScope.size,
                    alpha = result.alpha,
                    blendMode = BlendMode.SrcIn,
                )
                is GradientInterpolator.Result.Crossfade -> {
                    drawScope.drawRect(
                        brush = result.fromBrush,
                        topLeft = Offset.Zero,
                        size = drawScope.size,
                        blendMode = BlendMode.SrcIn,
                    )
                    drawScope.drawRect(
                        brush = result.toBrush,
                        topLeft = Offset.Zero,
                        size = drawScope.size,
                        alpha = result.progress,
                        blendMode = BlendMode.SrcAtop,
                    )
                }
                GradientInterpolator.Result.None -> Unit
            }
        } finally {
            canvas.restore()
            layerPaint.colorFilter = null
        }
    }

    /**
     * Resolves a caller tint endpoint ([Color], [Brush], or null) into an interpolatable spec.
     *
     * Compose callers routinely pass default-parameter gradient brushes whose coordinates are
     * sentinels — `Brush.linearGradient(colors)` ends at [Offset.Infinite],
     * `Brush.radialGradient(colors)` centers at [Offset.Unspecified] (NaN) with an infinite
     * radius. Interpolating those raw values lerps Inf→NaN and crashes
     * `LinearGradient.nativeCreate`, so every non-finite coordinate is resolved against the
     * draw size here, before caching and interpolation.
     */
    private fun specOf(value: Any?, drawSize: Size, fromEndpoint: Boolean): BrushSpec? {
        val sizeKey = drawSize.packedValue
        if (fromEndpoint) {
            if (value === cachedFromSource && sizeKey == cachedFromSize) return cachedFromSpec
            cachedFromSource = value
            cachedFromSize = sizeKey
            cachedFromSpec = resolveSpec(value, drawSize)
            return cachedFromSpec
        }
        if (value === cachedToSource && sizeKey == cachedToSize) return cachedToSpec
        cachedToSource = value
        cachedToSize = sizeKey
        cachedToSpec = resolveSpec(value, drawSize)
        return cachedToSpec
    }

    /** Maps a tint endpoint value to a spec: colors/brushes interpolate, anything else is ignored. */
    private fun resolveSpec(value: Any?, drawSize: Size): BrushSpec? = when (value) {
        null -> null
        is Color -> BrushSpec.Solid(value)
        is Brush -> BrushSpec.from(value)?.let { resolveFiniteCoordinates(it, drawSize) }
        else -> null
    }

    /**
     * Substitutes non-finite gradient geometry with layout-sized values, axis by axis and
     * direction preserving: a lone infinite endpoint extends the other by one full span, and
     * two infinite endpoints on an axis span the area (0 → w/h), mirroring how Compose itself
     * resolves `Offset.Infinite` defaults when painting.
     */
    private fun resolveFiniteCoordinates(spec: BrushSpec, drawSize: Size): BrushSpec {
        val w = drawSize.width
        val h = drawSize.height
        return when (spec) {
            is BrushSpec.Linear -> {
                val startX = resolveAxis(spec.startX, spec.endX, w)
                val endX = resolveAxis(spec.endX, spec.startX, w)
                val startY = resolveAxis(spec.startY, spec.endY, h)
                val endY = resolveAxis(spec.endY, spec.startY, h)
                spec.copy(startX = startX, endX = endX, startY = startY, endY = endY)
            }
            is BrushSpec.Radial -> spec.copy(
                centerX = spec.centerX.takeIf { it.isFinite() } ?: w * 0.5f,
                centerY = spec.centerY.takeIf { it.isFinite() } ?: h * 0.5f,
                radius = spec.radius.takeIf { it.isFinite() && it > 0f } ?: (hypot(w, h) * 0.5f),
            )
            is BrushSpec.Sweep -> spec.copy(
                centerX = spec.centerX.takeIf { it.isFinite() } ?: w * 0.5f,
                centerY = spec.centerY.takeIf { it.isFinite() } ?: h * 0.5f,
            )
            else -> spec
        }
    }

    /**
     * Resolves one gradient axis: the coordinate [primary] is non-finite while the opposite
     * endpoint [other] may be finite or not. Both non-finite span `0..span`; a lone non-finite
     * endpoint extends its finite partner by one full span, preserving the gradient direction.
     */
    private fun resolveAxis(primary: Float, other: Float, span: Float): Float {
        if (primary.isFinite()) return primary
        return if (other.isFinite()) other + span else span
    }

    /** Largest authored stroke width in [plan]'s viewport units (tint layer padding). */
    private fun maxStrokeWidth(plan: MorphPlan): Float {
        var widest = 0f
        for (pair in plan.pairs) {
            pair.fromPath?.let { widest = max(widest, it.strokeWidth) }
            pair.toPath?.let { widest = max(widest, it.strokeWidth) }
        }
        return widest
    }
}
