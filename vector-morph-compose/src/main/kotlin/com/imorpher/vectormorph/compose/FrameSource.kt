package com.imorpher.vectormorph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.transition.ContentTransition

/**
 * Availability of one host-owned [FrameSource].
 *
 * - [READY] — [FrameSource.frame] holds the frame to draw.
 * - [UNAVAILABLE] — nothing can be drawn right now (not decoded yet, a protected/undecodable
 *   frame, or an explicit failure). The endpoint renders as absent; it does not throw.
 * - [ENDED] — the source finished; the last frame is kept and drawn until the host replaces it.
 */
enum class FrameStatus { READY, UNAVAILABLE, ENDED }

/**
 * Single-slot, **latest-wins** buffer for one host-provided frame source.
 *
 * This is the video/frame boundary the library commits to: the host owns decoding, playback,
 * timestamps, and disposal, and pushes the frame it wants drawn into this slot. The library
 * owns only compositing, so no decoder, player, codec, or loader dependency is introduced.
 *
 * ### Contract
 *
 * - **Progress stays caller-owned.** [presentationTimeMillis] is metadata for host-side
 *   alignment only — playback speed never derives `progress`, and submitting a frame never
 *   moves it. One clock (the caller's) drives both endpoints.
 * - **Backpressure: one pending frame.** [submit] replaces whatever is pending, so a decoder
 *   that runs ahead of the UI can never queue up unbounded frames. A submit that overwrites a
 *   frame the renderer has not presented yet is counted in [droppedFrames].
 * - **Threading.** [submit] is safe from any thread (a decoder thread is the expected caller);
 *   all rendering reads happen on the UI thread. Frame state is Compose snapshot state, so a
 *   submit invalidates only the composables reading this slot.
 * - **Ownership.** The source never recycles, copies, or mutates a bitmap. [release] drops the
 *   reference so the host can dispose it; after `release` the slot is [UNAVAILABLE] again.
 *
 * Equal frames submitted twice are two submissions: the slot compares revisions, not pixels.
 *
 * @param initialFrame an already-decoded frame to show before the first [submit].
 */
@Stable
class FrameSource(initialFrame: ImageBitmap? = null) {

    /** The frame to draw, or null when the source has none. Snapshot state. */
    var frame: ImageBitmap? by mutableStateOf(initialFrame)
        private set

    /** Current availability. Snapshot state. */
    var status: FrameStatus by mutableStateOf(
        if (initialFrame != null) FrameStatus.READY else FrameStatus.UNAVAILABLE,
    )
        private set

    /**
     * Host presentation timestamp of [frame] in milliseconds, or null before the first submit.
     * Purely informational: use it to align this source with its pair, never to drive progress.
     */
    var presentationTimeMillis: Long? by mutableStateOf(null)
        private set

    /** Frames accepted by [submit] since construction. */
    val submittedFrames: Int get() = submitted

    /** Frames actually observed by a rendering [MorphFrames] composition. */
    val presentedFrames: Int get() = presented

    /** Submitted frames that were replaced before ever being presented (latest-wins drops). */
    val droppedFrames: Int get() = dropped

    @Volatile private var submitted = 0
    @Volatile private var presented = 0
    @Volatile private var dropped = 0
    @Volatile private var pendingPresentation = false

    /**
     * Publishes [newFrame] as the current frame, marking the source [FrameStatus.READY].
     * Replaces any pending frame (counting it as dropped if it was never presented) and stamps
     * [presentationTimeMillis] for host-side alignment.
     */
    fun submit(newFrame: ImageBitmap, presentationTimeMillis: Long? = null) {
        synchronized(this) {
            if (pendingPresentation) dropped++
            pendingPresentation = true
            submitted++
        }
        frame = newFrame
        this.presentationTimeMillis = presentationTimeMillis
        status = FrameStatus.READY
    }

    /**
     * Reports that no frame can be drawn (decode failure, protected content, no frame yet).
     * The stored frame is dropped so the endpoint composites as absent.
     */
    fun markUnavailable() {
        frame = null
        presentationTimeMillis = null
        status = FrameStatus.UNAVAILABLE
        synchronized(this) { pendingPresentation = false }
    }

    /**
     * Reports that the source has finished. The last frame is kept and keeps rendering — an
     * ended video freezes on its final frame instead of flashing to nothing.
     */
    fun markEnded() {
        status = FrameStatus.ENDED
        synchronized(this) { pendingPresentation = false }
    }

    /** Drops the frame reference so the host can dispose its bitmap; equivalent to [markUnavailable]. */
    fun release() {
        markUnavailable()
    }

    /** Called by [MorphFrames] after it has composed with the current frame. */
    internal fun markPresented() {
        synchronized(this) {
            if (pendingPresentation) {
                pendingPresentation = false
                presented++
            }
        }
    }
}

/**
 * Progress-driven transition between two host-owned [FrameSource]s.
 *
 * This is the raster/content transition path specialized for **changing** frames: [from] and
 * [to] are buffers the host keeps feeding while the caller owns [progress]. Both sources stay
 * observed for the whole transition, exactly like [MorphImage], and every transition, effect,
 * and [motionPreference] rule of [MorphPainter] applies unchanged.
 *
 * Each endpoint draws its source's [FrameSource.frame] when that source is
 * [FrameStatus.READY] or [FrameStatus.ENDED], and draws nothing while it is
 * [FrameStatus.UNAVAILABLE] — so a decoder that stalls leaves the other endpoint (or the
 * background) visible instead of throwing or freezing the whole layer. Frame state changes
 * invalidate only this composable; geometry is never planned or re-planned here.
 *
 * The box is sized by [width]/[height] (or the modifier) when given, otherwise by the larger
 * intrinsic size of the frames present at that moment; for video, pass an explicit size so the
 * layout does not jump when an endpoint has no frame yet.
 *
 * @param from outgoing frame source, visible at progress `0` (nullable for fade-in).
 * @param to incoming frame source, visible at progress `1` (nullable for fade-out).
 */
@Composable
fun MorphFrames(
    from: FrameSource?,
    to: FrameSource?,
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
    val fromFrame = from?.frame
    val toFrame = to?.frame
    MorphImage(
        from = fromFrame,
        to = toFrame,
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
    // Both slots were read this composition, so whatever is pending is now presented; the next
    // submit before the following pass is the one that counts as dropped.
    SideEffect {
        from?.markPresented()
        to?.markPresented()
    }
}
