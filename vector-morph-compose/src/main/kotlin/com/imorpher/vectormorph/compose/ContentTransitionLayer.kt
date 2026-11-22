package com.imorpher.vectormorph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.imorpher.vectormorph.core.transition.ResolvedContentLayer
import com.imorpher.vectormorph.core.transition.ResolvedWipe
import com.imorpher.vectormorph.core.transition.WipeDirection

/**
 * Shared drawing modifiers for content transitions: endpoint layer effects and the incoming
 * layer's wipe mask. Both are draw-only, so they never change measurement, layout, or the
 * semantics tree's structure.
 */

/**
 * Applies [layer]'s alpha/scale/translation/rotation to this endpoint, and hides the endpoint
 * from accessibility when it is fully transparent or fully unrevealed ([incoming] + a wipe at
 * fraction `0`). The wipe, when present, is applied **outside** the layer effects so it masks
 * the transformed endpoint in the container's coordinate space.
 */
internal fun Modifier.contentEndpoint(
    layer: ResolvedContentLayer,
    wipe: ResolvedWipe? = null,
    incoming: Boolean = false,
): Modifier {
    val hiddenFromAccessibility = layer.alpha <= 0f ||
        (incoming && wipe != null && wipe.revealedFraction <= 0f)
    return this
        .then(if (incoming) wipeMask(wipe) else Modifier)
        .graphicsLayer {
            alpha = layer.alpha
            scaleX = layer.scaleX
            scaleY = layer.scaleY
            translationX = layer.translationX * size.width
            translationY = layer.translationY * size.height
            rotationZ = layer.rotationDegrees
            transformOrigin = TransformOrigin.Center
        }
        .semantics { if (hiddenFromAccessibility) hideFromAccessibility() }
}

/**
 * Reveals this layer by [wipe], or leaves it untouched at fraction `1`. A fraction of `0`
 * draws nothing (the layer is still composed and keeps its state). Hard wipes use a clip
 * rectangle; feathered wipes draw the layer into a saveLayer and multiply its alpha by a
 * clamped linear gradient (DstIn), the same recipe the vector fill sweeps use.
 */
private fun Modifier.wipeMask(wipe: ResolvedWipe?): Modifier {
    if (wipe == null || wipe.revealedFraction >= 1f) return this
    if (wipe.revealedFraction <= 0f) return drawWithContent { /* fully unrevealed */ }

    return drawWithContent {
        if (wipe.feather <= 0f) {
            val clip = wipeClipBounds(wipe)
            clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                this@drawWithContent.drawContent()
            }
        } else {
            val layerBounds = Rect(Offset.Zero, size)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(layerBounds, Paint())
                this@drawWithContent.drawContent()
                // DstIn keeps the layer's color and multiplies its alpha by the mask's alpha.
                drawRect(
                    brush = wipeMaskBrush(wipe),
                    topLeft = Offset.Zero,
                    size = size,
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}

/** The visible rectangle of an incoming layer for a hard-edged wipe, in layer coordinates. */
private fun DrawScope.wipeClipBounds(wipe: ResolvedWipe): Rect {
    val width = size.width
    val height = size.height
    val revealed = wipe.revealedFraction
    return when (wipe.direction) {
        WipeDirection.LEFT_TO_RIGHT -> Rect(0f, 0f, width * revealed, height)
        WipeDirection.RIGHT_TO_LEFT -> Rect(width - width * revealed, 0f, width, height)
        WipeDirection.TOP_TO_BOTTOM -> Rect(0f, 0f, width, height * revealed)
        WipeDirection.BOTTOM_TO_TOP -> Rect(0f, height - height * revealed, width, height)
    }
}

/**
 * Linear alpha ramp for a feathered wipe: opaque on the revealed side of the moving edge,
 * transparent on the unrevealed side. The ramp is twice [ResolvedWipe.feather] wide in total,
 * centered on the edge; `Brush.linearGradient`'s clamped tiles keep the rest of the layer
 * fully opaque/transparent.
 */
private fun DrawScope.wipeMaskBrush(wipe: ResolvedWipe): Brush {
    val width = size.width
    val height = size.height
    val revealed = wipe.revealedFraction
    val stops = arrayOf(0f to Color.Black, 1f to Color.Transparent)
    return when (wipe.direction) {
        WipeDirection.LEFT_TO_RIGHT -> {
            val edge = width * revealed
            val half = width * wipe.feather / 2f
            Brush.linearGradient(colorStops = stops, start = Offset(edge - half, 0f), end = Offset(edge + half, 0f))
        }

        WipeDirection.RIGHT_TO_LEFT -> {
            val edge = width - width * revealed
            val half = width * wipe.feather / 2f
            Brush.linearGradient(colorStops = stops, start = Offset(edge + half, 0f), end = Offset(edge - half, 0f))
        }

        WipeDirection.TOP_TO_BOTTOM -> {
            val edge = height * revealed
            val half = height * wipe.feather / 2f
            Brush.linearGradient(colorStops = stops, start = Offset(0f, edge - half), end = Offset(0f, edge + half))
        }

        WipeDirection.BOTTOM_TO_TOP -> {
            val edge = height - height * revealed
            val half = height * wipe.feather / 2f
            Brush.linearGradient(colorStops = stops, start = Offset(0f, edge + half), end = Offset(0f, edge - half))
        }
    }
}
