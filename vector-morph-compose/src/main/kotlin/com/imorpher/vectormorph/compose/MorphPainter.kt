package com.imorpher.vectormorph.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.transition.ContentTransition
import com.imorpher.vectormorph.core.transition.ContentTransitionResolver

/** Fallback layout size when a painter (or both painters) have no usable intrinsic size. */
private val DefaultContentSize = 24.dp

/**
 * Progress-driven **visual transition** between two opaque [Painter]s. This is not a geometry
 * morph: the painters are composed, faded, wiped, and optionally transformed; their internal
 * paths, pixels, and paint are never inspected. Use it for images, video frames, and custom
 * drawing that cannot expose matching geometry — for vector endpoints keep using [MorphVector]
 * or [MorphIcon].
 *
 * Progress is caller-owned and clamped to `0..1`. At `0` [from] renders exactly; at `1` [to]
 * does. Either endpoint may be `null` to fade content in or out against nothing. Both painters
 * remain drawn for the whole transition, so a `Painter` should be stable across recompositions
 * (`remember` an image-backed painter instead of allocating one per frame).
 *
 * The default [transition] is a through crossfade with no extra effects. [ContentScale] and
 * [alignment] resolve each painter inside the shared box, exactly like `Image`. When [width]
 * and [height] are omitted, the box takes the larger usable intrinsic size of the two painters
 * (falling back to `24.dp` per unspecified axis); an explicit `modifier.size(...)` takes
 * precedence.
 *
 * Accessibility: [motionPreference] applies the same contract as the vector entries. A `null`
 * value resolves from the system animator-duration scale. `REDUCED` drops layer transforms
 * (scale/translate/rotate) while keeping the compositing mode, `CROSSFADE_ONLY` falls back to a
 * plain crossfade, and `INSTANT` snaps progress to the nearer endpoint so no part-way frame is
 * ever painted — see `ContentTransition.reduced`.
 *
 * Semantics: [contentDescription] is applied to the container; both painters describe
 * themselves as decoration. An endpoint that is fully transparent or fully unrevealed is
 * marked invisible to accessibility services.
 */
@Composable
fun MorphPainter(
    from: Painter?,
    to: Painter?,
    progress: Float,
    modifier: Modifier = Modifier,
    transition: ContentTransition = ContentTransition.Default,
    contentDescription: String? = null,
    width: Dp? = null,
    height: Dp? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    motionPreference: MotionPreference? = null,
) {
    val preference = motionPreference ?: rememberMotionPreference()
    val resolved = remember(progress, transition, preference) {
        ContentTransitionResolver.resolve(progress, transition, preference)
    }
    val boxSize = rememberPainterSize(from, to, width, height)
    val boxModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Box(
        modifier = boxModifier.size(boxSize.first, boxSize.second),
        contentAlignment = alignment,
    ) {
        from?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .contentEndpoint(resolved.from),
                alignment = alignment,
                contentScale = contentScale,
            )
        }
        to?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .contentEndpoint(resolved.to, resolved.wipe, incoming = true),
                alignment = alignment,
                contentScale = contentScale,
            )
        }
    }
}

/**
 * Convenience [MorphPainter] for raster endpoints. The bitmaps are wrapped in remembered
 * [BitmapPainter]s, so passing a new [ImageBitmap] instance (a video frame, for example)
 * recomposes without re-allocating the painter. Bitmaps are never decoded here: the caller
 * owns loading, decoding, frame production, and disposal. [motionPreference] behaves exactly
 * as in [MorphPainter] and is forwarded unchanged.
 */
@Composable
fun MorphImage(
    from: ImageBitmap?,
    to: ImageBitmap?,
    progress: Float,
    modifier: Modifier = Modifier,
    transition: ContentTransition = ContentTransition.Default,
    contentDescription: String? = null,
    width: Dp? = null,
    height: Dp? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    motionPreference: MotionPreference? = null,
) {
    val fromPainter = remember(from) { from?.let { BitmapPainter(it) } }
    val toPainter = remember(to) { to?.let { BitmapPainter(it) } }
    MorphPainter(
        from = fromPainter,
        to = toPainter,
        progress = progress,
        modifier = modifier,
        transition = transition,
        contentDescription = contentDescription,
        width = width,
        height = height,
        alignment = alignment,
        contentScale = contentScale,
        motionPreference = motionPreference,
    )
}

/**
 * Shared box size: the larger usable intrinsic size per axis, converted at the current
 * density. `Painter.intrinsicSize` is `Size.Unspecified` (NaN) for painters that size to
 * their layout, in which case the `24.dp` fallback applies. Explicit [width]/[height] win.
 */
@Composable
private fun rememberPainterSize(
    from: Painter?,
    to: Painter?,
    width: Dp?,
    height: Dp?,
): Pair<Dp, Dp> {
    val density = LocalDensity.current
    return remember(from, to, width, height, density) {
        val fromSize = from?.intrinsicSize
        val toSize = to?.intrinsicSize
        val widthPx = usableExtent(fromSize?.width, toSize?.width)
        val heightPx = usableExtent(fromSize?.height, toSize?.height)
        with(density) {
            (width ?: widthPx?.toDp() ?: DefaultContentSize) to
                (height ?: heightPx?.toDp() ?: DefaultContentSize)
        }
    }
}

private fun usableExtent(first: Float?, second: Float?): Float? =
    listOfNotNull(first, second)
        .filter { it.isFinite() && it > 0f }
        .maxOrNull()
