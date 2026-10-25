package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.imorpher.vectormorph.core.animation.PathFrameValues
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.plan.ContourPairPlan
import com.imorpher.vectormorph.core.plan.PathPairPlan
import com.imorpher.vectormorph.core.reveal.DirectionMath
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import kotlin.math.hypot
import kotlin.math.max

/**
 * Fill pass: paints filled geometry, including every fill-reveal mode (fade, radial, linear,
 * directional, sweep, and stroke-driven path traversal) and the two-leg asymmetric composite
 * that keeps fill-rule holes intact while geometry morphs.
 *
 * Reveal masks are clip operations on scratch [maskPath], so the underlying brush stays a real
 * (possibly gradient) brush and reveals correctly. The pass owns the fill scratch paths and the
 * per-pair composite cache, borrows static contour geometry through [pathOf], interpolated
 * contour geometry through [morphOf], and stroke reveals through [strokes].
 */
internal class FillPass(
    private val clips: ClipPass,
    /** Immutable contour geometry as a Compose [Path] (the renderer's static cache). */
    private val pathOf: (ContourData) -> Path,
    /** Interpolated contour geometry of one morphing pair, from the renderer's shared cache. */
    private val morphOf: MorphContourPathOf,
    private val strokes: StrokePass,
) {

    /** Mask for radial/directional/sweep fill reveals. */
    private val maskPath = Path()

    /** Combined fill geometry for the frame currently being painted. */
    private val fillPath = Path()

    /** One leg of the asymmetric composite fill per (pair, from/to slot). */
    private val combinedFillPathCache = HashMap<Int, Path>()

    /** Drops the per-plan composite cache; called when the renderer switches plans. */
    internal fun onPlanChanged() {
        combinedFillPathCache.clear()
    }

    /**
     * Builds one side's fill geometry for a morphing path as one combined path: every
     * morphing contour plus [extras] (that side's paintable unmatched contours), carrying
     * the side's fill type so its fill rule can punch holes (see [drawMorph]).
     * Contours are appended in pair order, keeping each contour's winding sign intact.
     */
    private fun combinedMorphFillPath(
        pair: PathPairPlan,
        morphT: Float,
        fillType: PathFillType,
        slot: Int,
        extras: List<ContourData>,
    ): Path {
        val path = combinedFillPathCache.getOrPut(pair.index * 2 + slot) { Path() }
        path.rewind()
        path.fillType = fillType
        for ((ci, cp) in pair.contourPairs.withIndex()) {
            path.addPath(morphOf(pair.index, ci, cp, morphT, false))
        }
        extras.forEach { path.addPath(pathOf(it)) }
        return path
    }

    /**
     * One fill path for every contour of a static side so fill-rule holes survive: solid
     * icons encode their inner symbols as opposite-winding contours that only cancel when
     * filled together.
     */
    private fun staticFillPath(side: PreparedPath): Path =
        contoursFillPath(side.contours, side.pathFillType)

    /** One fill path for a set of standalone contours so fill-rule holes survive. */
    private fun contoursFillPath(contours: List<ContourData>, fillType: PathFillType): Path {
        fillPath.rewind()
        fillPath.fillType = fillType
        contours.forEach { fillPath.addPath(pathOf(it)) }
        return fillPath
    }

    /**
     * Paints the fill of one morphing pair at [morphT], inside the caller's already
     * transformed [drawScope].
     *
     * Fill-rule correctness needs each side's COMPLETE contour set in one path: solid icons
     * encode inner symbols as contours winding opposite to their enclosing shape, and unmatched
     * contours belong to exactly one side. The fill is therefore painted as two complementary
     * legs of the same interpolated geometry — the from leg carries the from side's full set
     * (paired geometry + its unmatched contours) at (1 - t) alpha, the to leg carries the to
     * side's full set at t alpha. Each leg's fill rule cancels its own holes, so both endpoints
     * render their icons exactly, unmatched shapes dissolve in place, and shared regions stay
     * fully covered because the leg alphas sum to one. Crossfading brush results fade through
     * their own progress instead.
     *
     * [fillMode]/[fillDir] are the frame's resolved reveal settings (track first, configuration
     * default otherwise), [drawProgress]/[fillProgress] the raw NaN-filtered timeline properties,
     * and [warnings] collects what a reveal could not resolve.
     */
    fun drawMorph(
        drawScope: DrawScope,
        pair: PathPairPlan,
        f: PreparedPath,
        t: PreparedPath,
        morphT: Float,
        result: GradientInterpolator.Result,
        alpha: Float,
        strokeWidth: Float,
        props: PathFrameValues,
        fillMode: FillMode,
        fillDir: DrawDirection,
        drawProgress: Float?,
        fillProgress: Float?,
        clipContours: List<ContourData>,
        clipProgress: Float,
        clipDirection: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        fromExtras: List<ContourData>,
        toExtras: List<ContourData>,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        if (result is GradientInterpolator.Result.None || alpha <= 0.003f) return
        // A stroke reveal with no explicit fill track owns the WHOLE path: the fill
        // follows the same reveal progress, otherwise a filled icon (AdditemSolid)
        // stays fully painted at draw progress 0 — the pen draws the outline of a
        // shape that should still be invisible.
        val effectiveFill = fillProgress
            ?: when {
                // StrokeFillStrategy.MORPH: the fill grows with the morph while the stroke
                // shrinks, sweeping the shape in behind the vanishing outline.
                pair.strokeFillMorph -> morphT.coerceIn(0f, 1f)
                pair.strokeFillDraw -> ((morphT - 0.45f) / 0.55f).coerceIn(0f, 1f)
                drawProgress != null -> drawProgress
                else -> 1f
            }

        val customFillContours = props.customOverrides?.fillContours
        if (customFillContours != null) {
            val customBox = clips.boundsOf(customFillContours)
            // Custom replacement geometry is also filled as one combined path so a
            // custom contour that punches a hole keeps working (same fill rule).
            val combined = contoursFillPath(customFillContours, t.pathFillType)
            clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                paintFillWithReveal(
                    drawScope,
                    combined, result, alpha, effectiveFill, fillMode, fillDir,
                    customBox[0], customBox[1], customBox[2], customBox[3], strokeWidth,
                    t, props,
                    warnings = warnings,
                )
            }
            return
        }
        val isCrossfade = result is GradientInterpolator.Result.Crossfade
        val componentFrom =
            if (result is GradientInterpolator.Result.Crossfade) 1f - result.progress else 1f
        val componentTo =
            if (result is GradientInterpolator.Result.Crossfade) result.progress else 1f
        // FADE reveals the fill by alpha, not by a clipping mask (the clip
        // helpers cannot express it), so the legs carry the progress directly.
        val fadeAlpha = if (fillMode == FillMode.FADE) effectiveFill.coerceIn(0f, 1f) else 1f
        val fromLegAlpha = (alpha * componentFrom * fadeAlpha * if (isCrossfade) 1f else 1f - morphT).coerceIn(0f, 1f)
        val toLegAlpha = (alpha * componentTo * fadeAlpha * if (isCrossfade) 1f else morphT).coerceIn(0f, 1f)
        val revealActive = effectiveFill < 0.997f
        clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
            // A leg paints whenever its side has ANY geometry: paired contours,
            // unmatched contours, or both (fully unmatched pairs fade entirely
            // through the legs).
            if (fromLegAlpha > 0.003f && (pair.contourPairs.isNotEmpty() || fromExtras.isNotEmpty())) {
                val leg = combinedMorphFillPath(pair, morphT, f.pathFillType, slot = 0, extras = fromExtras)
                if (revealActive) {
                    // The pen owns the whole path: the fill sweeps in with the
                    // same reveal (directional clip keeps fill-rule holes safe).
                    clips.withFillRevealClip(this, effectiveFill, fillMode, fillDir, bMinX, bMinY, bMaxX, bMaxY) {
                        paintFillLegDirect(leg, result, fromLeg = true, alpha = fromLegAlpha)
                    }
                } else {
                    paintFillLegDirect(leg, result, fromLeg = true, alpha = fromLegAlpha)
                }
            }
            if (toLegAlpha > 0.003f && (pair.contourPairs.isNotEmpty() || toExtras.isNotEmpty())) {
                val leg = combinedMorphFillPath(pair, morphT, t.pathFillType, slot = 1, extras = toExtras)
                if (revealActive) {
                    clips.withFillRevealClip(this, effectiveFill, fillMode, fillDir, bMinX, bMinY, bMaxX, bMaxY) {
                        paintFillLegDirect(leg, result, fromLeg = false, alpha = toLegAlpha)
                    }
                } else {
                    paintFillLegDirect(leg, result, fromLeg = false, alpha = toLegAlpha)
                }
            }
        }
    }

    /**
     * Fills one unchanged endpoint side: the whole path in one pass, with no reveal masking
     * (static sides are painted at their resting geometry and full progress).
     */
    fun paintStaticSide(
        drawScope: DrawScope,
        side: PreparedPath,
        result: GradientInterpolator.Result,
        alpha: Float,
        fillAlphaOverride: Float = 1f,
    ) {
        if (result is GradientInterpolator.Result.None || side.fillAlpha <= 0f) return
        // One fill path for every contour: solid icons rely on the fill rule to punch
        // their inner symbol out of the fill (the plus inside AddCircleSolid is a hole,
        // not painted geometry), which only works when contours are filled together.
        drawScope.paintFillDirect(staticFillPath(side), result, alpha * side.fillAlpha * fillAlphaOverride)
    }

    /**
     * Fills one single-sided path that appears or disappears mid-morph, sweeping in behind
     * the pen through [paintFillWithReveal]; [clipProgress]/[clipDirection] carry the path's
     * own inherited group clip and [alpha] is the path-level alpha before the side's own.
     */
    fun drawMissingSide(
        drawScope: DrawScope,
        side: PreparedPath,
        result: GradientInterpolator.Result,
        alpha: Float,
        fillProgress: Float,
        fillMode: FillMode,
        fillDir: DrawDirection,
        clipProgress: Float,
        clipDirection: DrawDirection,
        props: PathFrameValues,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        if (result is GradientInterpolator.Result.None || side.fillAlpha <= 0f) return
        // The fill covers the WHOLE side (all contours in one path so fill-rule
        // holes survive): paint it exactly once, outside the per-contour stroke loop.
        clips.withClipReveal(drawScope, side.clipContours, clipProgress, clipDirection) {
            paintFillWithReveal(
                drawScope,
                staticFillPath(side),
                result,
                alpha * side.fillAlpha,
                fillProgress,
                fillMode,
                fillDir,
                side.bounds[0], side.bounds[1], side.bounds[2], side.bounds[3],
                side.strokeWidth,
                side,
                props,
                warnings = warnings,
            )
        }
    }

    /**
     * Fills caller-supplied custom contours as one combined path, so fill-rule holes (the inner
     * symbols of solid icons) survive even when a callback replaced the geometry.
     *
     * [reveal] false means the callback named its own fill contours and they paint directly;
     * true runs them through the frame's fill reveal mode, masked by the contours' own tight
     * bounds rather than the pair's.
     */
    fun drawCustomFill(
        drawScope: DrawScope,
        contours: List<ContourData>,
        side: PreparedPath,
        result: GradientInterpolator.Result,
        alpha: Float,
        strokeWidth: Float,
        props: PathFrameValues,
        reveal: Boolean,
        fillProgress: Float,
        fillMode: FillMode,
        fillDir: DrawDirection,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        val box = clips.boundsOf(contours)
        val combined = contoursFillPath(contours, side.pathFillType)
        if (!reveal) {
            drawScope.paintFillDirect(combined, result, alpha)
            return
        }
        paintFillWithReveal(
            drawScope,
            combined, result, alpha, fillProgress, fillMode, fillDir,
            box[0], box[1], box[2], box[3], strokeWidth, side, props,
            warnings = warnings,
        )
    }

    /**
     * Fills [path] honoring the fill reveal mode. Masking uses clip ops — the underlying
     * brush stays a real (possibly gradient) brush, so gradients reveal correctly.
     */
    private fun paintFillWithReveal(
        drawScope: DrawScope,
        path: Path,
        result: GradientInterpolator.Result,
        alpha: Float,
        fillProgress: Float,
        mode: FillMode,
        direction: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        strokeWidth: Float,
        t: PreparedPath,
        props: PathFrameValues,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        val prog = fillProgress.coerceIn(0f, 1f)
        if (prog <= 0.003f) return
        if (alpha <= 0.003f) return

        if (prog >= 1f) {
            drawScope.paintFillDirect(path, result, alpha)
            return
        }

        val w = (bMaxX - bMinX).coerceAtLeast(1e-3f)
        val h = (bMaxY - bMinY).coerceAtLeast(1e-3f)

        when (mode) {
            FillMode.FADE -> drawScope.paintFillDirect(path, result, alpha * prog)

            FillMode.RADIAL -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = prog * (hypot(w, h) / 2f)
                maskPath.rewind()
                maskPath.addOval(
                    Rect(
                        left = cx - r, top = cy - r, right = cx + r, bottom = cy + r,
                    )
                )
                drawScope.clipPath(maskPath) { paintFillDirect(path, result, alpha) }
            }

            FillMode.LINEAR, FillMode.DIRECTIONAL -> {
                val axis = DirectionMath.axisFor(direction)
                val ax0 = bMinX + axis[0] * w; val ay0 = bMinY + axis[1] * h
                val ax1 = bMinX + axis[2] * w; val ay1 = bMinY + axis[3] * h
                // edge position at prog along the axis, expanded far enough to cover the shape
                val ex = ax0 + (ax1 - ax0) * prog
                val ey = ay0 + (ay1 - ay0) * prog
                // normal direction of the axis
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
                drawScope.clipPath(maskPath) { paintFillDirect(path, result, alpha) }
            }

            FillMode.SWEEP -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = hypot(w, h)
                val clockwise = direction != DrawDirection.COUNTER_CLOCKWISE
                val startAngle = when (direction) {
                    DrawDirection.CLOCKWISE, DrawDirection.COUNTER_CLOCKWISE -> -90f
                    else -> {
                        val anchor = DirectionMath.anchorFor(direction)
                        Math.toDegrees(kotlin.math.atan2(anchor[1] - 0.5, anchor[0] - 0.5)).toFloat()
                    }
                }
                maskPath.rewind()
                maskPath.moveTo(cx, cy)
                maskPath.arcTo(
                    rect = Rect(cx - r, cy - r, cx + r, cy + r),
                    startAngleDegrees = startAngle,
                    sweepAngleDegrees = (if (clockwise) 1f else -1f) * 360f * prog,
                    forceMoveTo = false,
                )
                maskPath.close()
                drawScope.clipPath(maskPath) { paintFillDirect(path, result, alpha) }
            }

            FillMode.PATH_TRAVERSAL -> {
                // fill follows the stroke traversal: paint the *revealed* stroke path with a
                // very wide brush in the fill color. This reads the STROKE slot (the
                // DRAW_PROGRESS property) even while painting a fill: fill traversal is
                // choreography-steered by the stroke's mode/start/direction.
                val strokeDraw = props.strokeReveal
                val drawMode = strokeDraw?.mode ?: DrawMode.FORWARD
                val drawDirection = strokeDraw?.direction ?: direction
                val width = max(w, h) * 1.2f + strokeWidth
                fun paintTraversal(revealed: Path) {
                    if (revealed.isEmpty) return
                    when (result) {
                        is GradientInterpolator.Result.Interpolated ->
                            drawScope.drawPath(revealed, result.brush, alpha.coerceIn(0f, 1f), style = Stroke(width = width))
                        is GradientInterpolator.Result.Crossfade -> {
                            drawScope.drawPath(revealed, result.fromBrush, (alpha * (1f - result.progress)).coerceIn(0f, 1f), style = Stroke(width = width))
                            drawScope.drawPath(revealed, result.toBrush, (alpha * result.progress).coerceIn(0f, 1f), style = Stroke(width = width))
                        }
                        GradientInterpolator.Result.None -> Unit
                    }
                }
                when {
                    drawMode == DrawMode.RADIAL ->
                        clips.withRadialClip(drawScope, bMinX, bMinY, bMaxX, bMaxY, prog) { paintTraversal(path) }
                    drawMode == DrawMode.CUSTOM || drawDirection == DrawDirection.CUSTOM -> {
                        val customContours = props.customOverrides?.fillContours
                            ?: props.customOverrides?.strokeRevealContours
                            ?: error("Custom path-traversal fill requires custom fillContours or strokeRevealContours")
                        customContours.forEach { paintTraversal(pathOf(it)) }
                    }
                    else -> paintTraversal(
                        // Fill-sweep reuse of the reveal machinery, not a pen anchor: no
                        // debug anchor collector here (rule 7 covers stroke reveals), but
                        // the warnings still land in the frame collector.
                        strokes.buildRevealedStrokePath(
                            path, prog, drawMode,
                            strokeDraw?.start ?: PathStart.AUTO,
                            drawDirection,
                            null,
                            closed = t.contours.all { it.closed },
                            warnings = warnings,
                        )
                    )
                }
            }
        }
    }
}

/** Fills [path] with the resolved paint (one draw for interpolated, two for a crossfade). */
private fun DrawScope.paintFillDirect(path: Path, result: GradientInterpolator.Result, alpha: Float) {
    when (result) {
        is GradientInterpolator.Result.Interpolated ->
            drawPath(path, result.brush, alpha = (alpha * result.alpha).coerceIn(0f, 1f))
        is GradientInterpolator.Result.Crossfade -> {
            drawPath(path, result.fromBrush, alpha = (alpha * (1f - result.progress)).coerceIn(0f, 1f))
            drawPath(path, result.toBrush, alpha = (alpha * result.progress).coerceIn(0f, 1f))
        }
        GradientInterpolator.Result.None -> Unit
    }
}

/**
 * Direct (no reveal) fill paint for one leg of the asymmetric composite. The caller's
 * [alpha] already carries the component weight ([GradientInterpolator.Result]
 * interpolation/crossfade progress), so the brush is drawn as-is.
 */
private fun DrawScope.paintFillLegDirect(
    path: Path,
    result: GradientInterpolator.Result,
    fromLeg: Boolean,
    alpha: Float,
) {
    if (alpha <= 0.003f) return
    when (result) {
        is GradientInterpolator.Result.Interpolated ->
            drawPath(path, result.brush, alpha = alpha)
        is GradientInterpolator.Result.Crossfade ->
            drawPath(path, if (fromLeg) result.fromBrush else result.toBrush, alpha = alpha)
        GradientInterpolator.Result.None -> Unit
    }
}

/**
 * Resolves the effective paint for one pass: path-level brush overrides win over
 * vector-level ones; when neither exists the caller's interpolated fallback is used.
 */
internal fun animatedBrushResult(
    pathMode: Int,
    pathPrimary: Brush?,
    pathSecondary: Brush?,
    pathProgress: Float,
    vectorMode: Int,
    vectorPrimary: Brush?,
    vectorSecondary: Brush?,
    vectorProgress: Float,
    fallback: GradientInterpolator.Result,
): GradientInterpolator.Result {
    val mode = if (pathMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) pathMode else vectorMode
    val primary = if (pathMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) pathPrimary else vectorPrimary
    val secondary = if (pathMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) pathSecondary else vectorSecondary
    val progress = if (pathMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) pathProgress else vectorProgress
    return when {
        mode == com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_INTERPOLATED && primary != null ->
            GradientInterpolator.Result.Interpolated(primary, 1f)
        mode == com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_CROSSFADE && primary != null && secondary != null ->
            GradientInterpolator.Result.Crossfade(primary, secondary, progress)
        else -> fallback
    }
}
