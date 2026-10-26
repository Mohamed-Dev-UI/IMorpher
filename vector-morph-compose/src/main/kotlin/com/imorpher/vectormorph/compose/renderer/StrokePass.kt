package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.imorpher.vectormorph.core.animation.PathFrameValues
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.interpolation.GeometryInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.ContourPairPlan
import com.imorpher.vectormorph.core.plan.PathPairPlan
import com.imorpher.vectormorph.core.reveal.DirectionMath
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import kotlin.math.hypot
import kotlin.math.max

/**
 * Stroke pass: paints strokes and extracts the visible arc of the pen-drawing reveal.
 *
 * The pass owns the reveal scratch [strokePath] and [measure] and the reversed-contour cache.
 * It borrows static contour geometry through [pathOf], interpolated geometry through [morphOf]
 * (the renderer caches both, shared with the fill pass), and reveal clipping through [clips].
 * Stroke painting itself is stateless and lives in top-level [DrawScope] extensions.
 */
internal class StrokePass(
    /** Reveal clipping. */
    private val clips: ClipPass,
    /** Immutable contour geometry as a Compose [Path] (the renderer's static cache). */
    private val pathOf: (ContourData) -> Path,
    /** Interpolated contour geometry of one morphing pair, from the renderer's shared cache. */
    private val morphOf: MorphContourPathOf,
) {

    /** Revealed stroke geometry for the segment currently being extracted. */
    private val strokePath = Path()

    /** Arc-length engine behind every stroke reveal. */
    private val measure = PathMeasure()

    /** Reversed contours for direction-ordered reveals, built once per plan. */
    private val reversedContourCache = HashMap<ContourData, ContourData>()

    /**
     * Paints the stroke of one morphing pair at [morphT], inside the caller's already
     * transformed [drawScope] — the pen effect.
     *
     * Custom reveal contours replace the traversal entirely; otherwise every paired contour is
     * revealed through [buildRevealedStrokePath] (radial reveals go through a clip instead), and
     * each side's unmatched contours fade in and out with the pair's own alphas.
     *
     * [timing]/[honorDirection] carry the configuration's traversal policy, [customStart] the
     * resolved `PathStart.CUSTOM` anchor, and [anchorCollector] the debug overlay's sink (null
     * unless anchors are actually being collected, so a debug-free frame resolves geometry
     * exactly as before).
     */
    internal fun drawMorph(
        drawScope: DrawScope,
        pair: PathPairPlan,
        f: PreparedPath,
        t: PreparedPath,
        morphT: Float,
        result: GradientInterpolator.Result,
        alpha: Float,
        strokeWidth: Float,
        strokeWidthScale: Float? = null,
        props: PathFrameValues,
        drawCfg: VectorAnimation.DrawConfig?,
        clipContours: List<ContourData>,
        clipProgress: Float,
        clipDirection: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        drawProgress: Float?,
        customStart: Float,
        timing: TimingMode,
        honorDirection: Boolean,
        anchorCollector: ((anchorX: Float, anchorY: Float, travelLength: Float) -> Unit)?,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        // StrokeFillStrategy.MORPH: the pair's stroke shrinks as the fill grows with the
        // morph. The scale arrives null for every pair without the choreography.
        val effectiveWidth = strokeWidth * (strokeWidthScale ?: 1f)
        if (result is GradientInterpolator.Result.None || alpha <= 0.003f || effectiveWidth <= 0f) return
        val style = Stroke(
            width = effectiveWidth,
            cap = t.strokeLineCap,
            join = t.strokeLineJoin,
            miter = t.strokeLineMiter,
        )
        val customStrokeContours = props.customOverrides?.strokeRevealContours
        if (customStrokeContours != null) {
            customStrokeContours.forEach { contour ->
                clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                    paintStroke(pathOf(contour), result, alpha, style)
                }
            }
            return
        }
        val mode = drawCfg?.mode ?: DrawMode.FORWARD
        val start = drawCfg?.start ?: PathStart.AUTO
        val direction = drawCfg?.direction ?: DrawDirection.AUTO
        for ((ci, cp) in pair.contourPairs.withIndex()) {
            val path = morphOf(pair.index, ci, cp, morphT, false)
            if (drawProgress != null && drawProgress <= 0f) continue
            if (drawProgress != null && drawProgress < 1f) {
                if (mode == DrawMode.RADIAL) {
                    clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                        clips.withRadialClip(this, bMinX, bMinY, bMaxX, bMaxY, drawProgress) {
                            paintStroke(path, result, alpha, style)
                        }
                    }
                } else {
                    val strokeGeometry = if (cp.from.closed && shouldReverseWinding(cp.from.windingSign, direction))
                        morphOf(pair.index, ci, cp, morphT, true) else path
                    val revealed = buildRevealedStrokePath(
                        strokeGeometry, drawProgress, mode, start, direction, cp, customStart,
                        timing, ci, pair.contourPairs.size, max(f.totalLength, t.totalLength),
                        closed = cp.from.closed && cp.to.closed,
                        honorDirection = honorDirection,
                        warnings = warnings,
                        anchorCollector = anchorCollector,
                    )
                    if (revealed.isEmpty) continue
                    clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                        paintStroke(revealed, result, alpha, style)
                    }
                }
            } else {
                clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                    paintStroke(path, result, alpha, style)
                }
            }
        }
        pair.fromOnlyContours.forEach { c ->
            clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                paintStroke(pathOf(c), result, alpha * (1f - morphT), style)
            }
        }
        pair.toOnlyContours.forEach { c ->
            clips.withClipReveal(drawScope, clipContours, clipProgress, clipDirection) {
                paintStroke(pathOf(c), result, alpha * morphT, style)
            }
        }
    }

    /**
     * Strokes one unchanged endpoint side, contour by contour, with the side's own style.
     * [strokeWidthOverride] replaces the authored width when the timeline animates it.
     */
    internal fun paintStaticSide(
        drawScope: DrawScope,
        side: PreparedPath,
        result: GradientInterpolator.Result,
        alpha: Float,
        strokeWidthOverride: Float?,
        strokeAlphaOverride: Float = 1f,
    ) {
        val width = strokeWidthOverride ?: side.strokeWidth
        if (result is GradientInterpolator.Result.None || side.strokeAlpha <= 0f || width <= 0f) return
        val strokeAlpha = alpha * side.strokeAlpha * strokeAlphaOverride
        val style = Stroke(
            width = width,
            cap = side.strokeLineCap,
            join = side.strokeLineJoin,
            miter = side.strokeLineMiter,
        )
        side.contours.forEach { drawScope.paintStroke(pathOf(it), result, strokeAlpha, style) }
    }

    /**
     * Draws one single-sided path on contour by contour with the pen effect, winding-ordered
     * per [drawCfg]; [clipProgress]/[clipDirection] reveal the path's own inherited group clip.
     * [anchorCollector] feeds the debug overlay and is null unless anchors are collected.
     */
    internal fun drawMissingSide(
        drawScope: DrawScope,
        side: PreparedPath,
        result: GradientInterpolator.Result,
        alpha: Float,
        drawProgress: Float,
        drawCfg: VectorAnimation.DrawConfig?,
        clipProgress: Float,
        clipDirection: DrawDirection,
        customStart: Float,
        timing: TimingMode,
        honorDirection: Boolean = false,
        anchorCollector: ((anchorX: Float, anchorY: Float, travelLength: Float) -> Unit)?,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        val strokeMode = drawCfg?.mode ?: DrawMode.FORWARD
        val strokeDirection = drawCfg?.direction ?: DrawDirection.AUTO
        side.contours.forEachIndexed { contourIndex, contour ->
            val path = pathOf(contour)
            if (result is GradientInterpolator.Result.None || side.strokeWidth <= 0f) return@forEachIndexed
            val winding = side.windingSigns.getOrNull(contourIndex) ?: 0
            val strokeSource = if (contour.closed && shouldReverseWinding(winding, strokeDirection))
                pathOf(reversedContour(contour)) else path
            val style = Stroke(
                width = side.strokeWidth,
                cap = side.strokeLineCap,
                join = side.strokeLineJoin,
                miter = side.strokeLineMiter,
            )
            val alphaTotal = alpha * side.strokeAlpha
            if (strokeMode == DrawMode.RADIAL) {
                clips.withClipReveal(drawScope, side.clipContours, clipProgress, clipDirection) {
                    clips.withRadialClip(this, side.bounds[0], side.bounds[1], side.bounds[2], side.bounds[3], drawProgress) {
                        drawScope.paintStroke(path, result, alphaTotal, style)
                    }
                }
            } else {
                val revealed = buildRevealedStrokePath(
                    strokeSource,
                    drawProgress,
                    strokeMode,
                    drawCfg?.start ?: PathStart.AUTO,
                    strokeDirection,
                    null,
                    customStart,
                    timing,
                    contourIndex,
                    side.contours.size,
                    side.totalLength,
                    side.arcLengths.getOrNull(contourIndex),
                    closed = contour.closed,
                    warnings = warnings,
                    honorDirection = honorDirection,
                    anchorCollector = anchorCollector,
                )
                clips.withClipReveal(drawScope, side.clipContours, clipProgress, clipDirection) {
                    paintStroke(revealed, result, alphaTotal, style)
                }
            }
        }
    }

    /**
     * Extracts the visible portion of [source] according to [progress] and the draw mode,
     * using arc-length measurement — constant visual speed, exact geometry. The
     * start/direction/mode arbitration happens in [DirectionMath.resolve] inside
     * [resolveStartFraction]; any [DrawResolutionWarning] it produces reaches [warnings]
     * (the frame collector's callback by default).
     *
     * [honorDirection] carries `MorphConfiguration.directionTraversal == HONOR_DIRECTION`
     * (plan rule 4): a `DrawMode.FORWARD` reveal on an **open** contour whose direction
     * claims a travel axis ([DirectionMath.isSpatial]) samples the arc chord leaving the
     * resolved anchor and flips to the existing `REVERSE` arm when the arc travels against
     * that axis — the anchor is resolved once, only the traversal direction changes.
     * Closed contours never flip here: CW/CCW stays their traversal control.
     *
     * [anchorCollector] feeds the debug overlay (plan rule 7) the resolved anchor — the
     * exact point [resolveStartFraction] selected, as PathMeasure.getPosition reports it —
     * plus a travel-arrow length sized to ≈10% of the measured contour's bounds diagonal.
     * It is consulted only for mid-reveal windows and only when the callers supply it
     * (debug toggles on), so resolution behavior and default pixel output are untouched.
     */
    internal fun buildRevealedStrokePath(
        source: Path,
        progress: Float,
        mode: DrawMode,
        start: PathStart,
        direction: DrawDirection,
        cp: ContourPairPlan?,
        customStartFraction: Float = 0f,
        timingMode: TimingMode = TimingMode.UNIFORM,
        contourIndex: Int = 0,
        contourCount: Int = 1,
        totalPathLength: Float = cp?.from?.totalLength ?: 0f,
        staticArc: com.imorpher.vectormorph.core.geometry.ArcLengthData? = null,
        closed: Boolean = true,
        warnings: (List<DrawResolutionWarning>) -> Unit = {},
        honorDirection: Boolean = false,
        anchorCollector: ((anchorX: Float, anchorY: Float, travelLength: Float) -> Unit)? = null,
    ): Path {
        measure.setPath(source, false)
        val len = measure.length
        strokePath.rewind()
        if (len <= 0f) return strokePath

        val rawProgress = progress.coerceIn(0f, 1f)
        val arc = cp?.from?.arcLength ?: staticArc
        val p = when (timingMode) {
            TimingMode.BY_COMMAND -> if (arc == null) rawProgress else
                GeometryInterpolator.mapDrawProgress(rawProgress, timingMode, arc, contourIndex, contourCount)
            TimingMode.BY_PATH -> if (arc == null) rawProgress else
                GeometryInterpolator.mapDrawProgress(rawProgress, timingMode, arc, contourIndex, contourCount)
            TimingMode.BY_PATH_LENGTH -> if (arc == null || arc.totalLength <= 1e-5f || totalPathLength <= 1e-5f) rawProgress
                else (rawProgress * totalPathLength / arc.totalLength).coerceIn(0f, 1f)
            TimingMode.UNIFORM -> rawProgress
        }
        if (p >= 1f) return source

        // starting arc fraction: precomputed anchor fractions live on the prepared paths;
        // for morph contours we resolve the anchor from the interpolated geometry.
        val startFrac = resolveStartFraction(
            source, measure, len, start, direction, mode, customStartFraction, warnings,
        )
        val s = startFrac * len

        // Debug overlay collection (start/direction coordination plan rule 7): measure the
        // resolved anchor point and an arrow length from the contour's own bounds — once,
        // before any traversal-sign decision, since the anchor is independent of the sign.
        anchorCollector?.let { collector ->
            val position = measure.getPosition(s)
            if (position.x.isFinite() && position.y.isFinite()) {
                var minX = position.x; var minY = position.y
                var maxX = position.x; var maxY = position.y
                val samples = 48
                for (i in 0..samples) {
                    val sample = measure.getPosition(len * i / samples)
                    if (!sample.x.isFinite() || !sample.y.isFinite()) continue
                    if (sample.x < minX) minX = sample.x
                    if (sample.y < minY) minY = sample.y
                    if (sample.x > maxX) maxX = sample.x
                    if (sample.y > maxY) maxY = sample.y
                }
                collector(position.x, position.y, hypot(maxX - minX, maxY - minY) * 0.1f)
            }
        }

        // HONOR_DIRECTION (start/direction coordination plan rule 4): under LEGACY a
        // FORWARD reveal follows the contour's own point order, which can run against
        // the requested travel axis. Sample the arc chord leaving the anchor — one eighth
        // of the contour length, taken to the contour end without wrapping open contours —
        // and dot it against the axis' travel vector (axis[2]-axis[0], axis[3]-axis[1]).
        // Negative dot ⇒ reveal through the existing REVERSE arm's segment machinery
        // (same anchor, same windowing); positive/zero (including the degenerate chord at
        // an anchor sitting on the contour's end) keep FORWARD as today. Winding-addressed
        // directions never reach this branch (DirectionMath.isSpatial excludes them), so
        // shouldReverseWinding's outcome is untouched — LEGACY and every non-FORWARD mode
        // ignore the sign entirely. Open contours only: rule 4 scopes traversal-honoring
        // to open contours; on closed contours CW/CCW remains the traversal control.
        val effectiveMode = if (
            honorDirection && mode == DrawMode.FORWARD &&
            DirectionMath.isSpatial(direction) && !closed
        ) {
            val axis = DirectionMath.axisFor(direction)
            val travelX = axis[2] - axis[0]
            val travelY = axis[3] - axis[1]
            val from = measure.getPosition(s)
            val to = measure.getPosition((s + len / 8f).coerceAtMost(len))
            if ((to.x - from.x) * travelX + (to.y - from.y) * travelY < 0f) {
                DrawMode.REVERSE
            } else {
                DrawMode.FORWARD
            }
        } else mode

        when (effectiveMode) {
            DrawMode.FORWARD -> {
                val end = s + p * len
                if (end <= len) {
                    measure.getSegment(s, end, strokePath, true)
                } else if (s >= len - 1e-3f) {
                    // Anchor sits at the contour end: only the wrap leg exists. Calling
                    // getSegment with a zero-length range can still emit a stray moveTo,
                    // so skip straight to the wrap leg with its own moveTo.
                    measure.getSegment(0f, end - len, strokePath, true)
                } else {
                    // When the anchor sits at the contour end the first leg produces nothing;
                    // the follow-up leg must then open its own contour or Android implicitly
                    // starts it at (0,0) and paints a stray line from the canvas origin.
                    val opened = measure.getSegment(s, len, strokePath, true)
                    if (opened) {
                        // Legs may continue each other only on a CLOSED contour: on an
                        // open one the continuation draws a straight connector between
                        // the two open ends (e.g. linking a chevron's feet), inventing
                        // geometry the icon never had.
                        measure.getSegment(0f, end - len, strokePath, !opened || !closed)
                    } else {
                        measure.getSegment(0f, end - len, strokePath, true)
                    }
                }
            }
            DrawMode.REVERSE -> {
                // draws from the start anchor backwards
                val begin = s - p * len
                if (begin >= 0f) {
                    measure.getSegment(begin, s, strokePath, true)
                } else if (s <= 1e-3f) {
                    // Anchor sits at the contour start: only the wrap leg exists.
                    measure.getSegment(len + begin, len, strokePath, true)
                } else {
                    // The visible arc walks backwards from the anchor: [0..s], then through
                    // the wrap, [len+begin..len]. Drawing the wrap leg FIRST lets the second
                    // leg continue from the wrap point (len == contour start) with no moveTo
                    // on a CLOSED contour — appending it after [0..s] joins the legs in path
                    // order and paints a straight chord across the gap (verified by pixel
                    // probe: ~500 interior pixels on a closed circle at p=0.55). Open
                    // contours keep the two arcs as separate sub-paths instead.
                    val wrapped = measure.getSegment(len + begin, len, strokePath, true)
                    measure.getSegment(0f, s, strokePath, !wrapped || !closed)
                }
            }
            DrawMode.CENTER_OUT -> {
                // grows from the start anchor outwards in both directions
                val half = p * len / 2f
                if (half * 2f >= len) return source
                val lo = s - half
                val hi = s + half
                when {
                    hi <= lo -> Unit // degenerate window: nothing to draw yet
                    lo >= 0f && hi <= len -> measure.getSegment(lo, hi, strokePath, true)
                    lo < 0f -> {
                        // wraps the contour start: [len+lo..len] then [0..hi], joined at the wrap
                        val opened = if (len + lo >= len - 1e-3f) false else
                            measure.getSegment(len + lo, len, strokePath, true)
                        if (hi > 1e-3f) measure.getSegment(0f, hi, strokePath, !opened || !closed)
                    }
                    else -> {
                        // wraps the contour end: [lo..len] then [0..hi-len], joined at the wrap
                        val opened = if (lo >= len - 1e-3f) false else
                            measure.getSegment(lo, len, strokePath, true)
                        if (hi - len > 1e-3f) measure.getSegment(0f, hi - len, strokePath, !opened || !closed)
                    }
                }
            }
            DrawMode.OUTSIDE_IN -> {
                // both ends draw towards the anchor; the two arcs stay separate contours
                val quarter = p * len / 2f
                measure.getSegment(0f, quarter, strokePath, true)
                measure.getSegment(len - quarter, len, strokePath, true)
            }
            DrawMode.RADIAL -> error("Radial stroke reveals must be rendered through a radial clip")
            DrawMode.CUSTOM -> error("Custom stroke reveals require CustomVectorPathOverrides.strokeRevealContours")
        }
        return strokePath
    }

    /**
     * Resolves where the "pen" starts: the arc fraction nearest the resolved anchor (or the
     * literal `0`/`1`/`0.5` values for START/END/CENTER). Anchor arbitration is delegated to
     * [DirectionMath.resolve] so `start` and `direction` follow one precedence contract:
     * an explicit [PathStart] wins over the direction, `AUTO` derives from the direction
     * table, and any contradiction lands in the frame's warning collector via [warnings].
     */
    private fun resolveStartFraction(
        source: Path,
        measure: PathMeasure,
        len: Float,
        start: PathStart,
        direction: DrawDirection,
        mode: DrawMode,
        customStartFraction: Float,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ): Float {
        if (start == PathStart.CUSTOM) return customStartFraction.coerceIn(0f, 1f)
        val resolved = DirectionMath.resolve(start, direction, mode)
        warnings(resolved.warnings)
        val resolvedStart = resolved.anchor
        // Target Contract rule 3 (start/direction coordination plan): an explicit START
        // anchors literal arc position 0 of the normalized contour regardless of direction.
        // The old direction-derived behavior of the START default is reachable through the
        // AUTO sentinel only (e.g. AUTO direction → TOP_LEFT anchor).
        if (resolvedStart == PathStart.START) return 0f
        if (resolvedStart == PathStart.END) return 1f
        if (resolvedStart == PathStart.CENTER) return 0.5f

        // Approximate the requested geometric anchor on the measured, normalized path.
        val samples = 48
        var best = 0f
        var bestD = Float.MAX_VALUE

        // anchor in path space comes from direction (TOP_RIGHT_TO_BOTTOM_LEFT etc.) —
        // resolve against the measured path bounds
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0..samples) {
            val position = measure.getPosition(len * i / samples)
            if (!position.x.isFinite() || !position.y.isFinite()) continue
            if (position.x < minX) minX = position.x
            if (position.y < minY) minY = position.y
            if (position.x > maxX) maxX = position.x
            if (position.y > maxY) maxY = position.y
        }
        if (minX == Float.MAX_VALUE) return 0f

        val ax: Float; val ay: Float
        when (resolvedStart) {
            PathStart.TOP -> { ax = (minX + maxX) / 2f; ay = minY }
            PathStart.RIGHT -> { ax = maxX; ay = (minY + maxY) / 2f }
            PathStart.BOTTOM -> { ax = (minX + maxX) / 2f; ay = maxY }
            PathStart.LEFT -> { ax = minX; ay = (minY + maxY) / 2f }
            PathStart.TOP_LEFT -> { ax = minX; ay = minY }
            PathStart.TOP_RIGHT -> { ax = maxX; ay = minY }
            PathStart.BOTTOM_LEFT -> { ax = minX; ay = maxY }
            PathStart.BOTTOM_RIGHT -> { ax = maxX; ay = maxY }
            PathStart.END -> return 1f
            PathStart.CENTER -> return 0.5f
            else -> { ax = (minX + maxX) / 2f; ay = (minY + maxY) / 2f }
        }

        for (i in 0..samples) {
            val position = measure.getPosition(len * i / samples)
            if (!position.x.isFinite() || !position.y.isFinite()) continue
            val d = (position.x - ax) * (position.x - ax) + (position.y - ay) * (position.y - ay)
            if (d < bestD) {
                bestD = d
                best = i.toFloat() / samples
            }
        }
        return best
    }

    /**
     * Strokes caller-supplied custom contours, in order. [reveal] false means the callback
     * already produced the geometry to show and it is painted as-is; true runs each contour
     * through [buildRevealedStrokePath]. Contours with nothing to show, or a zero width, skip
     * the draw entirely.
     */
    internal fun drawCustomContours(
        drawScope: DrawScope,
        contours: List<ContourData>,
        result: GradientInterpolator.Result,
        alpha: Float,
        strokeWidth: Float,
        side: PreparedPath,
        reveal: Boolean,
        drawCfg: VectorAnimation.DrawConfig?,
        drawProgress: Float,
        customStart: Float,
        timing: TimingMode,
        honorDirection: Boolean = false,
        anchorCollector: ((anchorX: Float, anchorY: Float, travelLength: Float) -> Unit)?,
        warnings: (List<DrawResolutionWarning>) -> Unit,
    ) {
        val style = Stroke(
            width = strokeWidth,
            cap = side.strokeLineCap,
            join = side.strokeLineJoin,
            miter = side.strokeLineMiter,
        )
        val direction = drawCfg?.direction ?: DrawDirection.AUTO
        for (contour in contours) {
            val path = pathOf(contour)
            val shown = if (!reveal) path else buildRevealedStrokePath(
                path, drawProgress, drawCfg?.mode ?: DrawMode.FORWARD, drawCfg?.start ?: PathStart.AUTO,
                direction, null, customStart, timing,
                warnings = warnings,
                honorDirection = honorDirection,
                anchorCollector = anchorCollector,
            )
            if (shown.isEmpty || strokeWidth <= 0f) continue
            drawScope.paintStroke(shown, result, alpha, style)
        }
    }

    /** The contour traversed end→start, cached so repeated reveals share one instance. */
    internal fun reversedContour(contour: ContourData): ContourData =
        reversedContourCache.getOrPut(contour) { contour.reversed() }

    /** Whether a contour must run backwards to honor a CLOCKWISE/COUNTER_CLOCKWISE reveal. */
    internal fun shouldReverseWinding(windingSign: Int, direction: DrawDirection): Boolean = when (direction) {
        DrawDirection.CLOCKWISE -> windingSign < 0
        DrawDirection.COUNTER_CLOCKWISE -> windingSign > 0
        else -> false
    }
}

/** Strokes [path] with the resolved paint (one draw for interpolated, two for a crossfade). */
private fun DrawScope.paintStroke(
    path: Path,
    result: GradientInterpolator.Result,
    alpha: Float,
    style: Stroke,
) {
    when (result) {
        is GradientInterpolator.Result.Interpolated ->
            drawPathP(path, result.brush, alpha * result.alpha, style)
        is GradientInterpolator.Result.Crossfade -> {
            drawPathP(path, result.fromBrush, alpha * (1f - result.progress), style)
            drawPathP(path, result.toBrush, alpha * result.progress, style)
        }
        GradientInterpolator.Result.None -> Unit
    }
}

/** Alpha-guarded stroke draw: alphas at or below the visibility floor skip the op. */
private fun DrawScope.drawPathP(path: Path, brush: Brush, alpha: Float, style: Stroke) {
    if (alpha <= 0.003f) return
    drawPath(path, brush, alpha = alpha.coerceIn(0f, 1f), style = style)
}

/**
 * Resolves the `PathStart.CUSTOM` draw anchor for one stroke-reveal site:
 * [MorphConfiguration.customDrawAnchors] first (the new per-path draw anchor map), then
 * [MorphConfiguration.customStartOffsets] (legacy fallback for one release), with the same
 * `from`-then-`to` name precedence at every site. One definition for all three call sites
 * fixes the plan's C4 inconsistency (the pair pass used `from ?: to`, the static sites only
 * looked at their own name); the static single-path sites pass their own path name for
 * both legs.
 */
@Suppress("DEPRECATION") // customStartOffsets is the documented legacy draw-anchor fallback
internal fun drawAnchorFor(config: MorphConfiguration, fromName: String, toName: String): Float =
    config.customDrawAnchors[fromName]
        ?: config.customDrawAnchors[toName]
        ?: config.customStartOffsets[fromName]
        ?: config.customStartOffsets[toName]
        ?: 0f
