package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.gradients.BrushSpec
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.PropKey
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.CustomVectorPathOverrides
import com.imorpher.vectormorph.core.animation.CustomVectorPathState
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.interpolation.GeometryInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator.buildBrush
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.ContourPairPlan
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.PairKind
import com.imorpher.vectormorph.core.plan.PathPairPlan
import com.imorpher.vectormorph.core.reveal.DirectionMath
import com.imorpher.vectormorph.core.reveal.DrawOrder
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a [MorphPlan] at a given progress — the ONLY per-frame hot path.
 *
 * Scratch paths, path measures, and coordinate buffers are reused. Matching, normalization,
 * subdivision, and path measurements are all complete before drawing; animated brush values are
 * rebuilt from their immutable specs as progress changes.
 *
 * Rendering happens in viewport coordinates: the scope is transformed so that
 * (0..viewportWidth, 0..viewportHeight) maps into the layout bounds, aspect-preserved and
 * centered — icons never change visual scale regardless of layout size.
 */
class MorphRenderer {

    /** Reused packed-coordinate buffer for the per-frame control-point lerp. */
    private var coords = FloatArray(512)

    // scratch paths (never escape this class)

    /** Combined fill geometry for the frame currently being painted. */
    private val fillPath = Path()

    /** Revealed stroke geometry for the segment currently being extracted. */
    private val strokePath = Path()

    /** Clip mask for directional/radial/sweep fill reveals. */
    private val maskPath = Path()

    // per-frame draw-resolution diagnostics (cleared at the start of every render)
    /** Draw-resolution warnings collected while rendering the current frame. */
    private val drawWarnings = ArrayList<DrawResolutionWarning>()

    /**
     * Snapshot of the draw-resolution warnings collected during the last render; the list
     * clears at the start of each render. Read-only seam for the debug overlay (Task 7 of
     * the start/direction coordination plan) and for test probes — deliberately not a
     * configuration knob.
     */
    internal fun frameDrawWarnings(): List<DrawResolutionWarning> = drawWarnings.toList()

    /**
     * Resolved draw anchors recorded for the debug overlay (start/direction coordination
     * plan rule 7): one entry per revealed contour that fed a collector this frame, cleared
     * at the start of every render. Only filled when the debug overlay actually consumes it
     * ([collectDrawAnchors]), so a debug-free frame resolves geometry exactly as before.
     */
    private val drawAnchors = ArrayList<ResolvedDrawAnchor>()

    /** Whether [drawAnchors] is being collected for the frame currently rendering. */
    private var collectDrawAnchors = false

    /** Clip wedge for directional clip reveals. */
    private val wedgePath = Path()

    /** Scratch triangle for the debug resolution-warning indicator. */
    private val warningPath = Path()

    /** Clip circle for radial clip reveals. */
    private val radialPath = Path()
    /** Arc-length engine behind every stroke reveal. */
    private val measure = PathMeasure()

    // per-contour rebuilt-from-scratch caches keyed by pairIndex*contour slot

    /** Interpolated contour geometry per (pair, contour); rewritten each frame. */
    private val contourPathCache = HashMap<Long, Path>()

    /** One leg of the asymmetric composite fill per (pair, from/to slot). */
    private val combinedFillPathCache = HashMap<Int, Path>()

    /** Immutable source/target contour geometry built once per plan. */
    private val staticContourCache = HashMap<ContourData, Path>()

    /** Reversed contours for direction-ordered reveals, built once per plan. */
    private val reversedContourCache = HashMap<ContourData, ContourData>()

    /** Plan whose per-plan scratch state (below) is currently built. */
    private var scratchPlan: MorphPlan? = null

    /** Distinct named-group scopes per pair, in outermost-first order. */
    private var pairGroupNames: Array<List<String>> = emptyArray()

    /** Per-pair scratch values merging path + group overrides for the current frame. */
    private var mergedPathProps: Array<com.imorpher.vectormorph.core.animation.PathFrameValues> = emptyArray()

    // render-order memo: recomputed only when the plan or the order configuration changes

    /** Plan the cached order was computed for (identity comparison). */
    private var cachedRenderOrderPlan: MorphPlan? = null

    /** Draw-order strategy the cached order was computed for. */
    private var cachedRenderOrderStrategy = configOrderDefault()

    /** Custom order list the cached order was computed for. */
    private var cachedRenderOrderCustom: List<String> = emptyList()

    /** keepRenderOrder value the cached order was computed for. */
    private var cachedRenderOrderKeep = true

    /** The memoized pair draw order; see [renderOrder]. */
    private var cachedRenderOrder: IntArray = IntArray(0)

    /** Reused paint for the tint saveLayer (kept here so tinting allocates nothing per frame). */
    private val tintLayerPaint = Paint()

    /** Layer paint for the per-path isolation saveLayer used by tint compositing. */
    private val isolatePaint = Paint()

    // Per-pair unmatched contours per side (rebuilt with plan scratch state): each side's
    // leg path carries its own extras so fill-rule holes cancel within the complete set.
    /** Per-pair unmatched source contours (the from leg's extra geometry). */
    private var fromExtras: Array<List<ContourData>> = emptyArray()

    /** Per-pair unmatched target contours (the to leg's extra geometry). */
    private var toExtras: Array<List<ContourData>> = emptyArray()

    // Tint endpoint specs are derived from caller values (Color/Brush); cache them so the
    // reflective BrushSpec extraction runs once per distinct (value, draw size) pair.

    /** Last from-endpoint tint value seen (identity comparison). */
    private var cachedTintFromSource: Any? = null

    /** Draw size the from-endpoint spec was resolved against (packed floats). */
    private var cachedTintFromSize: Long = 0L

    /** Resolved from-endpoint spec, reused while [cachedTintFromSource] and size are stable. */
    private var cachedTintFromSpec: BrushSpec? = null

    /** Last to-endpoint tint value seen (identity comparison). */
    private var cachedTintToSource: Any? = null

    /** Draw size the to-endpoint spec was resolved against (packed floats). */
    private var cachedTintToSize: Long = 0L

    /** Resolved to-endpoint spec, reused while [cachedTintToSource] and size are stable. */
    private var cachedTintToSpec: BrushSpec? = null

    /**
     * Draws one frame.
     *
     * When endpoint tints are present ([tintFrom]/[tintTo]: [Color] or [Brush] values, null
     * disables that endpoint), the icon is drawn into an offscreen layer and repainted with the
     * tint interpolated between the endpoints at the current progress: every painted pixel takes
     * the tint color/brush while the icon's own alpha (fades, crossfades, reveals, antialiased
     * edges) is preserved. Tint gradient coordinates resolve against the icon's layout bounds,
     * matching `Modifier.background(brush)`.
     */
    fun render(
        drawScope: DrawScope,
        plan: MorphPlan,
        frame: EvaluatedFrame,
        progress: Float,
        config: MorphConfiguration,
        tintFrom: Any? = null,
        tintTo: Any? = null,
    ) {
        drawWarnings.clear()
        drawAnchors.clear()
        collectDrawAnchors = config.debug.let { debug ->
            debug != null && (debug.showStartPoints || debug.showDirection)
        }
        val p = progress.coerceIn(0f, 1f)
        ensurePlanScratch(plan)

        val vw = if (plan.from.viewportWidth > 0f) plan.from.viewportWidth else 24f
        val vh = if (plan.from.viewportHeight > 0f) plan.from.viewportHeight else 24f

        val scaleX = drawScope.size.width / vw
        val scaleY = drawScope.size.height / vh
        val uniform = min(scaleX, scaleY)
        val dx = (drawScope.size.width - vw * uniform) / 2f
        val dy = (drawScope.size.height - vh * uniform) / 2f

        val vAlpha = frame.vectorProps[PropKey.ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
        val tintFromSpec = tintSpecOf(tintFrom, drawScope.size, fromEndpoint = true)
        val tintToSpec = tintSpecOf(tintTo, drawScope.size, fromEndpoint = false)
        val tintResult = if (tintFromSpec != null || tintToSpec != null) {
            GradientInterpolator.interpolate(tintFromSpec, tintToSpec, p, config.colorSpace)
        } else null
        val order = renderOrder(plan, config)

        fun drawIconContents() {
            drawScope.withTransform({
                translate(dx, dy)
                scale(uniform, uniform, pivot = Offset.Zero)
            }) {
                applyVectorTransform(frame, vw, vh) {
                    for (pairIndex in order) {
                        drawPair(plan.pairs[pairIndex], plan, frame, p, config, vAlpha)
                    }
                }
            }
        }

        fun drawDebug() {
            val debug = config.debug ?: return
            drawScope.withTransform({
                translate(dx, dy)
                scale(uniform, uniform, pivot = Offset.Zero)
            }) {
                applyVectorTransform(frame, vw, vh) { drawDebugOverlay(plan, debug) }
            }
        }

        if (tintResult == null) {
            drawIconContents()
            drawDebug()
            return
        }

        // Offscreen layer: draw the icon, then repaint it with the tint while preserving
        // per-pixel alpha, so fades, reveals, and edges survive. The layer is created before
        // any transform is applied and the tint rects are drawn after withTransform has
        // restored the canvas matrix, so tint gradient coordinates resolve against the icon's
        // layout bounds — the same convention as Modifier.background(brush). Padding covers
        // strokes/group transforms that leave the viewport.
        //
        // Crossfading endpoint tints (mismatched gradient kinds, custom brushes, or a null
        // endpoint) uses SrcIn for the from-tint and SrcAtop for the to-tint so the two legs
        // premultiply exactly (mix of colors at the icon's own alpha) instead of the
        // double-attenuation two SrcIn passes would produce.
        val canvas = drawScope.drawContext.canvas
        val layerRect = Rect(
            -dx - uniform * vw,
            -dy - uniform * vh,
            drawScope.size.width + uniform * vw,
            drawScope.size.height + uniform * vh,
        )
        canvas.saveLayer(layerRect, tintLayerPaint)
        try {
            drawIconContents()
            when (tintResult) {
                is GradientInterpolator.Result.Interpolated -> drawScope.drawRect(
                    brush = tintResult.brush,
                    topLeft = Offset.Zero,
                    size = drawScope.size,
                    alpha = tintResult.alpha,
                    blendMode = BlendMode.SrcIn,
                )
                is GradientInterpolator.Result.Crossfade -> {
                    drawScope.drawRect(
                        brush = tintResult.fromBrush,
                        topLeft = Offset.Zero,
                        size = drawScope.size,
                        blendMode = BlendMode.SrcIn,
                    )
                    drawScope.drawRect(
                        brush = tintResult.toBrush,
                        topLeft = Offset.Zero,
                        size = drawScope.size,
                        alpha = tintResult.progress,
                        blendMode = BlendMode.SrcAtop,
                    )
                }
                GradientInterpolator.Result.None -> Unit
            }
        } finally {
            canvas.restore()
        }
        drawDebug()
    }

    /**
     * Resolves a caller tint endpoint ([Color], [Brush], or null) into an interpolatable spec.
     *
     * Compose callers routinely pass default-parameter gradient brushes whose coordinates are
     * sentinels — `Brush.linearGradient(colors)` ends at [Offset.Infinite],
     * `Brush.radialGradient(colors)` centers at [Offset.Unspecified] (NaN) with an infinite
     * radius. Interpolating those raw values lerps Inf→NaN and crashes
     * `LinearGradient.nativeCreate`, so every non-finite coordinate is resolved against the
     * draw size here, before caching and interpolation. Results are memoized per
     * (value identity, draw size) so the reflective extraction runs once.
     */
    private fun tintSpecOf(value: Any?, drawSize: Size, fromEndpoint: Boolean): BrushSpec? {
        val sizeKey = drawSize.packedValue
        if (fromEndpoint) {
            if (value === cachedTintFromSource && sizeKey == cachedTintFromSize) return cachedTintFromSpec
            cachedTintFromSource = value
            cachedTintFromSize = sizeKey
            cachedTintFromSpec = resolveSpec(value, drawSize)
            return cachedTintFromSpec
        }
        if (value === cachedTintToSource && sizeKey == cachedTintToSize) return cachedTintToSpec
        cachedTintToSource = value
        cachedTintToSize = sizeKey
        cachedTintToSpec = resolveSpec(value, drawSize)
        return cachedTintToSpec
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

    /**
     * Pair indices in draw order. By default ([MorphConfiguration.keepRenderOrder]) that is
     * declaration order — order strategies only affect stagger timing — but with
     * keepRenderOrder = false the configured strategy reorders rendering (z-order) too.
     */
    private fun renderOrder(plan: MorphPlan, config: MorphConfiguration): IntArray {
        if (cachedRenderOrderPlan === plan && cachedRenderOrderStrategy == config.drawOrder &&
            cachedRenderOrderCustom == config.customDrawOrder && cachedRenderOrderKeep == config.keepRenderOrder
        ) return cachedRenderOrder
        cachedRenderOrderPlan = plan
        cachedRenderOrderStrategy = config.drawOrder
        cachedRenderOrderCustom = config.customDrawOrder
        cachedRenderOrderKeep = config.keepRenderOrder
        cachedRenderOrder = if (config.keepRenderOrder) IntArray(plan.pairs.size) { it }
            else DrawOrder.orderIndices(plan, config.drawOrder, config.customDrawOrder)
        return cachedRenderOrder
    }

    private companion object {
        fun configOrderDefault() = com.imorpher.vectormorph.core.model.DrawOrderStrategy.BY_INDEX
    }

    /** Rebuilds the per-plan scratch state (group names, merged props, contour extras). */
    private fun ensurePlanScratch(plan: MorphPlan) {
        if (scratchPlan === plan) return
        scratchPlan = plan
        pairGroupNames = Array(plan.pairs.size) { index ->
            val pair = plan.pairs[index]
            (pair.fromPath?.groupNames.orEmpty() + pair.toPath?.groupNames.orEmpty()).distinct()
        }
        mergedPathProps = Array(plan.pairs.size) { com.imorpher.vectormorph.core.animation.PathFrameValues() }
        contourPathCache.clear()
        combinedFillPathCache.clear()
        fromExtras = Array(plan.pairs.size) { i -> plan.pairs[i].fromOnlyContours }
        toExtras = Array(plan.pairs.size) { i -> plan.pairs[i].toOnlyContours }
    }

    /**
     * Development-only geometry overlays (bounds, centroids, points, matching lines,
     * numbered labels, resolved draw anchors/travel axes, resolution warnings) drawn in
     * viewport space from [MorphConfiguration.debug].
     *
     * Gating: the resolved anchor dot and the travel arrow render only with
     * [com.imorpher.vectormorph.core.model.MorphDebugOptions.showStartPoints]/`showDirection`
     * (and resolved anchors are only collected for those toggles), while the resolution-
     * warning banner paints whenever this overlay runs ([MorphConfiguration.debug] non-null)
     * and warnings were recorded — it is not gated behind the anchor/arrow toggles.
     */
    private fun DrawScope.drawDebugOverlay(
        plan: MorphPlan,
        options: com.imorpher.vectormorph.core.model.MorphDebugOptions,
    ) {
        for (pair in plan.pairs) {
            val hue = (pair.index * 67f) % 360f
            val color = Color.hsv(hue, 0.82f, 0.95f, alpha = 0.9f)
            val source = pair.fromPath
            val target = pair.toPath
            val representative = target ?: source ?: continue

            if (options.showPathMatching && source != null && target != null) {
                drawLine(
                    color = Color.Cyan.copy(alpha = 0.8f),
                    start = Offset(source.centroidX, source.centroidY),
                    end = Offset(target.centroidX, target.centroidY),
                    strokeWidth = 0.25f,
                )
            }

            val paths = if (source != null && target != null && source !== target) {
                listOf(source, target)
            } else listOf(representative)
            for (path in paths) {
                if (options.showBoundingBoxes) {
                    drawRect(
                        color = color,
                        topLeft = Offset(path.bounds[0], path.bounds[1]),
                        size = androidx.compose.ui.geometry.Size(
                            path.bounds[2] - path.bounds[0], path.bounds[3] - path.bounds[1],
                        ),
                        style = Stroke(width = 0.3f),
                    )
                }
                if (options.showCentroids) {
                    drawCircle(color, radius = 0.45f, center = Offset(path.centroidX, path.centroidY))
                }
                for (contour in path.contours) {
                    val first = contour.segments.firstOrNull() ?: continue
                    if (options.showPathPoints) {
                        contour.segments.forEach { segment ->
                            drawCircle(color, radius = 0.18f, center = Offset(segment.x0, segment.y0))
                        }
                    }
                    if (options.showStartPoints) {
                        drawCircle(
                            color = Color.Yellow,
                            radius = 0.5f,
                            center = Offset(first.x0, first.y0),
                            style = Stroke(width = 0.2f),
                        )
                    }
                    if (options.showDirection) {
                        drawLine(
                            color = Color.Magenta,
                            start = Offset(first.x0, first.y0),
                            end = Offset(first.x3, first.y3),
                            strokeWidth = 0.3f,
                        )
                    }
                }
                if (options.showPathNumbers) {
                    drawDebugNumber(path.index, path.centroidX + 0.6f, path.centroidY - 0.6f, color)
                }
            }
        }

        // Resolved-draw annotations (start/direction coordination plan rule 7): the anchor
        // the reveal actually selected and an arrow along the configured direction's travel
        // axis. buildRevealedStrokePath feeds [drawAnchors] only while this block can
        // consume it, so neither toggle changes any other pixel. Style follows the markers
        // above (yellow = start, magenta = direction).
        if (options.showStartPoints || options.showDirection) {
            for (anchor in drawAnchors) {
                val center = Offset(anchor.x, anchor.y)
                if (options.showStartPoints) {
                    drawCircle(color = Color.Yellow, radius = 0.5f, center = center)
                }
                if (options.showDirection) {
                    val axis = DirectionMath.axisFor(anchor.direction)
                    drawLine(
                        color = Color.Magenta,
                        start = center,
                        end = Offset(
                            anchor.x + (axis[2] - axis[0]) * anchor.arrowLength,
                            anchor.y + (axis[3] - axis[1]) * anchor.arrowLength,
                        ),
                        strokeWidth = 0.3f,
                    )
                }
            }
        }
        // The warning banner follows the OVERLAY running at all, not any single toggle:
        // recorded resolution warnings must stay visible with any debug option on.
        if (drawWarnings.isNotEmpty()) {
            drawResolutionWarningBanner()
        }
    }

    /**
     * Fixed top-left viewport-corner indicator for a frame with draw-resolution warnings:
     * a red triangle plus the warning count (7-segment digits, same style as
     * [drawDebugNumber]). Counted over all revealed contours, so it repeats no detail —
     * the callers keep the per-warning breakdown.
     */
    private fun DrawScope.drawResolutionWarningBanner() {
        warningPath.rewind()
        warningPath.moveTo(0.4f, 1.8f)
        warningPath.lineTo(1.15f, 0.5f)
        warningPath.lineTo(1.9f, 1.8f)
        warningPath.close()
        drawPath(warningPath, Color.Red)
        drawDebugNumber(drawWarnings.size, 2.6f, 0.5f, Color.Red)
    }

    /** Draws compact 7-segment digits so debug overlays stay backend-independent. */
    private fun DrawScope.drawDebugNumber(value: Int, x: Float, y: Float, color: Color) {
        val glyphs = intArrayOf(
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011,
        )
        val digits = value.toString()
        var cursorX = x - digits.length * 0.45f
        for (character in digits) {
            val digit = character.digitToIntOrNull() ?: continue
            val bits = glyphs[digit]
            val segments = arrayOf(
                Offset(0f, 0f) to Offset(0.7f, 0f),
                Offset(0.7f, 0f) to Offset(0.7f, 0.7f),
                Offset(0.7f, 0.7f) to Offset(0.7f, 1.4f),
                Offset(0f, 1.4f) to Offset(0.7f, 1.4f),
                Offset(0f, 0.7f) to Offset(0f, 1.4f),
                Offset(0f, 0f) to Offset(0f, 0.7f),
                Offset(0f, 0.7f) to Offset(0.7f, 0.7f),
            )
            for (index in segments.indices) {
                if ((bits and (1 shl (6 - index))) != 0) {
                    val (a, b) = segments[index]
                    drawLine(
                        color,
                        Offset(cursorX + a.x, y + a.y),
                        Offset(cursorX + b.x, y + b.y),
                        strokeWidth = 0.22f,
                    )
                }
            }
            cursorX += 0.9f
        }
    }

    // ------------------------------------------------------------------ vector transform

    /**
     * Applies the vector-level timeline transform (scale/rotate/translate around a
     * fractional pivot) before [block]. Identity transforms draw in place with no canvas ops.
     */
    private inline fun DrawScope.applyVectorTransform(
        frame: EvaluatedFrame,
        vw: Float,
        vh: Float,
        block: DrawScope.() -> Unit,
    ) {
        val sx = frame.vectorProps[PropKey.SCALE_X.ordinal].takeUnless { it.isNaN() } ?: 1f
        val syRaw = frame.vectorProps[PropKey.SCALE_Y.ordinal]
        val sy = if (syRaw.isNaN() || syRaw == -1f) sx else syRaw
        val rot = frame.vectorProps[PropKey.ROTATION_DEG.ordinal].takeUnless { it.isNaN() } ?: 0f
        val tx = frame.vectorProps[PropKey.TRANSLATION_X.ordinal].takeUnless { it.isNaN() } ?: 0f
        val ty = frame.vectorProps[PropKey.TRANSLATION_Y.ordinal].takeUnless { it.isNaN() } ?: 0f
        val px = frame.vectorProps[PropKey.PIVOT_X.ordinal].takeUnless { it.isNaN() } ?: 0.5f
        val py = frame.vectorProps[PropKey.PIVOT_Y.ordinal].takeUnless { it.isNaN() } ?: 0.5f

        if (sx == 1f && sy == 1f && rot == 0f && tx == 0f && ty == 0f) {
            block()
            return
        }
        val pivotX = px * vw
        val pivotY = py * vh
        withTransform({
            // rotate must pivot on the just-translated origin (Compose's default pivot is
            // the draw-area center, which would orbit the whole vector off-canvas).
            translate(pivotX + tx, pivotY + ty)
            rotate(rot, Offset.Zero)
            scale(sx, sy, pivot = Offset.Zero)
            translate(-pivotX, -pivotY)
        }) { block() }
    }

    // ------------------------------------------------------------------ pair dispatch

    /**
     * Draws one path pair: merges its named-group overrides into the frame values, applies
     * each enclosing group's animated transform around the path, and restores the frame
     * values afterwards so merged state never leaks into the next pair.
     */
    private fun DrawScope.drawPair(
        pair: PathPairPlan,
        plan: MorphPlan,
        frame: EvaluatedFrame,
        p: Float,
        config: MorphConfiguration,
        vectorAlpha: Float,
    ) {
        val base = frame.pathProps[pair.index]
        val merged = mergedPathProps[pair.index]
        merged.copyVisualsFrom(base)
        val names = pairGroupNames.getOrNull(pair.index).orEmpty()
        val hasPathFill = base.brushMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE
        val hasPathStroke = base.strokeBrushMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE
        names.forEach { name ->
            val group = base.groupProps[name] ?: return@forEach
            mergeGroupVisuals(merged, group, hasPathFill, hasPathStroke)
        }
        frame.pathProps[pair.index] = merged
        try {
            drawThroughGroups(pair, plan, frame, p, config, vectorAlpha, base, names, 0)
        } finally {
            frame.pathProps[pair.index] = base
        }
    }

    /**
     * Folds one named group's overrides into [into]: alpha multipliers multiply (nested
     * groups compose), other scalars and draw configs fill in when unset, and group paints
     * apply only when the path has none of its own kind. Transform properties are skipped —
     * groups apply those geometrically via [withGroupTransform].
     */
    private fun mergeGroupVisuals(
        into: com.imorpher.vectormorph.core.animation.PathFrameValues,
        group: com.imorpher.vectormorph.core.animation.PathFrameValues,
        hasPathFill: Boolean,
        hasPathStroke: Boolean,
    ) {
        fun multiply(key: PropKey) {
            val value = group.props[key.ordinal]
            if (!value.isNaN()) {
                val existing = into.props[key.ordinal]
                into.props[key.ordinal] = (if (existing.isNaN()) 1f else existing) * value
            }
        }
        multiply(PropKey.ALPHA); multiply(PropKey.FILL_ALPHA); multiply(PropKey.STROKE_ALPHA)
        for (key in PropKey.entries) {
            if (key == PropKey.ALPHA || key == PropKey.FILL_ALPHA || key == PropKey.STROKE_ALPHA ||
                key == PropKey.SCALE_X || key == PropKey.SCALE_Y || key == PropKey.ROTATION_DEG ||
                key == PropKey.TRANSLATION_X || key == PropKey.TRANSLATION_Y ||
                key == PropKey.PIVOT_X || key == PropKey.PIVOT_Y
            ) continue
            if (into.props[key.ordinal].isNaN() && !group.props[key.ordinal].isNaN()) {
                into.props[key.ordinal] = group.props[key.ordinal]
            }
        }
        // Reveal slots merge with the same "only when the target slot is null" rule the
        // per-prop array used; each group fills only its own unset slots.
        if (into.strokeReveal == null) into.strokeReveal = group.strokeReveal
        if (into.fillReveal == null) into.fillReveal = group.fillReveal
        if (into.clipReveal == null) into.clipReveal = group.clipReveal
        if (!hasPathFill && group.brushMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) {
            into.brushMode = group.brushMode; into.primaryBrush = group.primaryBrush
            into.secondaryBrush = group.secondaryBrush; into.brushProgress = group.brushProgress
        }
        if (!hasPathStroke && group.strokeBrushMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) {
            into.strokeBrushMode = group.strokeBrushMode; into.primaryStrokeBrush = group.primaryStrokeBrush
            into.secondaryStrokeBrush = group.secondaryStrokeBrush; into.strokeBrushProgress = group.strokeBrushProgress
        }
    }

    /** Recurses through the pair's named groups (outermost first), transforming at each level. */
    private fun DrawScope.drawThroughGroups(
        pair: PathPairPlan,
        plan: MorphPlan,
        frame: EvaluatedFrame,
        p: Float,
        config: MorphConfiguration,
        vectorAlpha: Float,
        base: com.imorpher.vectormorph.core.animation.PathFrameValues,
        names: List<String>,
        depth: Int,
    ) {
        if (depth >= names.size) {
            drawPairContents(pair, plan, frame, p, config, vectorAlpha)
            return
        }
        val group = base.groupProps[names[depth]]
        if (group == null) {
            drawThroughGroups(pair, plan, frame, p, config, vectorAlpha, base, names, depth + 1)
            return
        }
        withGroupTransform(group) {
            drawThroughGroups(pair, plan, frame, p, config, vectorAlpha, base, names, depth + 1)
        }
    }

    /**
     * Applies one group's animated scale/rotation/translation around a pivot resolved
     * inside the group's shared viewport-space bounds before [block]. Identity is a no-op.
     */
    private inline fun DrawScope.withGroupTransform(
        values: com.imorpher.vectormorph.core.animation.PathFrameValues,
        crossinline block: DrawScope.() -> Unit,
    ) {
        val props = values.props
        val sx = props[PropKey.SCALE_X.ordinal].takeUnless { it.isNaN() } ?: 1f
        val syValue = props[PropKey.SCALE_Y.ordinal]
        val sy = if (syValue.isNaN() || syValue == -1f) sx else syValue
        val rotation = props[PropKey.ROTATION_DEG.ordinal].takeUnless { it.isNaN() } ?: 0f
        val tx = props[PropKey.TRANSLATION_X.ordinal].takeUnless { it.isNaN() } ?: 0f
        val ty = props[PropKey.TRANSLATION_Y.ordinal].takeUnless { it.isNaN() } ?: 0f
        if (sx == 1f && sy == 1f && rotation == 0f && tx == 0f && ty == 0f) { block(); return }
        val bounds = values.groupBounds
        val minX = if (bounds[0].isFinite()) bounds[0] else 0f
        val minY = if (bounds[1].isFinite()) bounds[1] else 0f
        val maxX = if (bounds[2].isFinite()) bounds[2] else minX
        val maxY = if (bounds[3].isFinite()) bounds[3] else minY
        val px = minX + (maxX - minX) * (props[PropKey.PIVOT_X.ordinal].takeUnless { it.isNaN() } ?: 0.5f)
        val py = minY + (maxY - minY) * (props[PropKey.PIVOT_Y.ordinal].takeUnless { it.isNaN() } ?: 0.5f)
        withTransform({
            // rotate/scale must pivot on the just-translated origin: Compose's default
            // rotate pivot is the draw-area center, which would orbit content off-canvas.
            translate(px + tx, py + ty); rotate(rotation, Offset.Zero); scale(sx, sy, pivot = Offset.Zero); translate(-px, -py)
        }) { block() }
    }

    /**
     * The pair-level draw dispatch: runs the custom callback (if any), resolves reveal
     * configurations, validates custom-mode requirements, and routes to the right painter
     * for the pair's [PairKind] and fallback strategy.
     */
    private fun DrawScope.drawPairContents(
        pair: PathPairPlan,
        plan: MorphPlan,
        frame: EvaluatedFrame,
        p: Float,
        config: MorphConfiguration,
        vectorAlpha: Float,
    ) {
        val props = frame.pathProps[pair.index]
        var alphaOverride = props.props[PropKey.ALPHA.ordinal].takeUnless { it.isNaN() }
        val morphProgress = TimelineEvaluator.morphFraction(props.props, p)
        val custom = config.animation?.customizer?.transform(
            CustomVectorPathState(
                pair.index, pair.fromPath, pair.toPath, p, morphProgress,
                props.props[PropKey.DRAW_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: p,
                props.props[PropKey.FILL_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: p,
            ),
        )
        custom?.let {
            applyCustomOverrides(props, it)
            props.customOverrides = it
            alphaOverride = props.props[PropKey.ALPHA.ordinal].takeUnless { value -> value.isNaN() }
        }

        val customDraw = props.strokeReveal
            ?: config.animation?.strokeRevealFor(
                pair.fromPath?.name, pair.toPath?.name, pair.fromPath?.index ?: -1,
                pair.toPath?.index ?: -1, PropKey.DRAW_PROGRESS,
                pair.fromPath?.groupNames.orEmpty(), pair.toPath?.groupNames.orEmpty(),
            )
        val customFill = props.fillReveal
            ?: config.animation?.fillRevealFor(
                pair.fromPath?.name, pair.toPath?.name, pair.fromPath?.index ?: -1,
                pair.toPath?.index ?: -1, PropKey.FILL_PROGRESS,
                pair.fromPath?.groupNames.orEmpty(), pair.toPath?.groupNames.orEmpty(),
            )
        if ((customDraw?.mode == DrawMode.CUSTOM || customDraw?.direction == DrawDirection.CUSTOM) &&
            custom?.strokeRevealContours == null
        ) {
            error("Custom stroke drawing requires CustomVectorPathOverrides.strokeRevealContours for pair ${pair.index}")
        }
        if (customFill?.fillDirection == DrawDirection.CUSTOM && custom?.fillContours == null) {
            error("DrawDirection.CUSTOM requires CustomVectorPathOverrides.fillContours for pair ${pair.index}")
        }
        if (config.missingPath == MissingPathBehavior.CUSTOM &&
            (pair.kind == PairKind.FADE_IN || pair.kind == PairKind.FADE_OUT) && custom?.contours == null
        ) {
            error("MissingPathBehavior.CUSTOM requires a custom callback that returns contours for pair ${pair.index}")
        }
        if (custom?.contours != null) {
            drawCustomGeometry(pair, frame, p, config, vectorAlpha, alphaOverride, custom)
            return
        }

        when (pair.kind) {
            PairKind.MORPH -> if (config.fallback == com.imorpher.vectormorph.core.model.FallbackStrategy.DRAW_REVEAL) {
                drawStaticSide(pair, pair.fromPath!!, frame, (1f - p) * vectorAlpha * (alphaOverride ?: 1f), config)
                drawMissingPathByDrawing(pair, pair.toPath!!, frame, p, config, vectorAlpha * (alphaOverride ?: 1f))
            } else drawMorphPair(pair, frame, p, config, vectorAlpha, alphaOverride)
            PairKind.CROSSFADE -> {
                val a = alphaOverride ?: 1f
                val f = pair.fromPath!!
                val t = pair.toPath!!
                if (config.fallback == com.imorpher.vectormorph.core.model.FallbackStrategy.SCALE_CROSSFADE) {
                    withTransform({
                        translate(f.centroidX, f.centroidY)
                        scale(1f - 0.06f * p, 1f - 0.06f * p, pivot = Offset.Zero)
                        translate(-f.centroidX, -f.centroidY)
                    }) { drawStaticSide(pair, f, frame, (1f - p) * vectorAlpha * a, config) }
                    withTransform({
                        translate(t.centroidX, t.centroidY)
                        scale(0.94f + 0.06f * p, 0.94f + 0.06f * p, pivot = Offset.Zero)
                        translate(-t.centroidX, -t.centroidY)
                    }) { drawStaticSide(pair, t, frame, p * vectorAlpha * a, config) }
                } else {
                    drawStaticSide(pair, f, frame, (1f - p) * vectorAlpha * a, config)
                    drawStaticSide(pair, t, frame, p * vectorAlpha * a, config)
                }
            }
            PairKind.FADE_IN -> drawMissingSide(
                pair, pair.toPath!!, frame, p, config, vectorAlpha, alphaOverride, appearing = true,
            )
            PairKind.FADE_OUT -> drawMissingSide(
                pair, pair.fromPath!!, frame, p, config, vectorAlpha, alphaOverride, appearing = false,
            )
        }
    }

    /** Writes a custom callback's overrides into the frame values; alpha fields multiply. */
    private fun applyCustomOverrides(props: com.imorpher.vectormorph.core.animation.PathFrameValues, custom: CustomVectorPathOverrides) {
        custom.alpha?.let { props.props[PropKey.ALPHA.ordinal] = multiplyOverride(props.props[PropKey.ALPHA.ordinal], it) }
        custom.fillAlpha?.let { props.props[PropKey.FILL_ALPHA.ordinal] = multiplyOverride(props.props[PropKey.FILL_ALPHA.ordinal], it) }
        custom.strokeAlpha?.let { props.props[PropKey.STROKE_ALPHA.ordinal] = multiplyOverride(props.props[PropKey.STROKE_ALPHA.ordinal], it) }
        custom.strokeWidth?.let { props.props[PropKey.STROKE_WIDTH.ordinal] = it }
        custom.scaleX?.let { props.props[PropKey.SCALE_X.ordinal] = it }
        custom.scaleY?.let { props.props[PropKey.SCALE_Y.ordinal] = it }
        custom.rotationDegrees?.let { props.props[PropKey.ROTATION_DEG.ordinal] = it }
        custom.translationX?.let { props.props[PropKey.TRANSLATION_X.ordinal] = it }
        custom.translationY?.let { props.props[PropKey.TRANSLATION_Y.ordinal] = it }
        custom.pivotX?.let { props.props[PropKey.PIVOT_X.ordinal] = it }
        custom.pivotY?.let { props.props[PropKey.PIVOT_Y.ordinal] = it }
        custom.fill?.let {
            props.brushMode = com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_INTERPOLATED
            props.primaryBrush = it.buildBrush(); props.secondaryBrush = null; props.brushProgress = 1f
        }
        custom.stroke?.let {
            props.strokeBrushMode = com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_INTERPOLATED
            props.primaryStrokeBrush = it.buildBrush(); props.secondaryStrokeBrush = null; props.strokeBrushProgress = 1f
        }
    }

    /** Combines a stacked alpha multiplier with the existing override (or 1 when unset). */
    private fun multiplyOverride(existing: Float, multiplier: Float): Float =
        (if (existing.isNaN()) 1f else existing) * multiplier

    /**
     * Renders a pair whose geometry came from [CustomVectorPathOverrides.contours]: fill and
     * stroke draw from the replacement contours, honoring reveal progress, clip reveals, and
     * every scalar/paint override the callback supplied.
     */
    private fun DrawScope.drawCustomGeometry(
        pair: PathPairPlan,
        frame: EvaluatedFrame,
        progress: Float,
        config: MorphConfiguration,
        vectorAlpha: Float,
        alphaOverride: Float?,
        custom: CustomVectorPathOverrides,
    ) {
        val props = frame.pathProps[pair.index]
        val side = pair.toPath ?: pair.fromPath ?: return
        val morph = TimelineEvaluator.morphFraction(props.props, progress)
        val fillResult = animatedBrushResult(
            props.brushMode, props.primaryBrush, props.secondaryBrush, props.brushProgress,
            frame.vectorBrushMode, frame.vectorPrimaryBrush, frame.vectorSecondaryBrush, frame.vectorBrushProgress,
            custom.fill?.let { GradientInterpolator.Result.Interpolated(it.buildBrush(), 1f) }
                ?: GradientInterpolator.interpolate(pair.fromPath?.fill, pair.toPath?.fill, morph, config.colorSpace),
        )
        val strokeResult = animatedBrushResult(
            props.strokeBrushMode, props.primaryStrokeBrush, props.secondaryStrokeBrush, props.strokeBrushProgress,
            frame.vectorStrokeBrushMode, frame.vectorPrimaryStrokeBrush, frame.vectorSecondaryStrokeBrush,
            frame.vectorStrokeBrushProgress,
            custom.stroke?.let { GradientInterpolator.Result.Interpolated(it.buildBrush(), 1f) }
                ?: GradientInterpolator.interpolate(pair.fromPath?.stroke, pair.toPath?.stroke, morph, config.colorSpace),
        )
        val alpha = vectorAlpha * (alphaOverride ?: 1f)
        val fillAlpha = alpha * (props.props[PropKey.FILL_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f) * side.fillAlpha
        val strokeAlpha = alpha * (props.props[PropKey.STROKE_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f) * side.strokeAlpha
        // precedence: explicit custom override > timeline STROKE_WIDTH track > authored width
        val width = custom.strokeWidth
            ?: props.props[PropKey.STROKE_WIDTH.ordinal].takeUnless { it.isNaN() }
            ?: side.strokeWidth
        val contours = custom.contours.orEmpty()
        val drawP = props.props[PropKey.DRAW_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: progress
        val fillP = props.props[PropKey.FILL_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: progress
        val drawCfg = props.strokeReveal
        val fillCfg = props.fillReveal
        val transform = PairTransform.from(props, side, side, 0f)
        withPairTransform(transform) {
            val contents: DrawScope.() -> Unit = {
                val fillContours = custom.fillContours ?: contours
                val fillBox = customBounds(fillContours)
                // Fill all contours together so fill-rule holes (inner symbols of solid
                // icons) survive, including when custom contours replace the geometry.
                fillPath.rewind()
                fillPath.fillType = side.pathFillType
                fillContours.forEach { fillPath.addPath(staticPath(it)) }
                if (custom.fillContours != null) paintFillDirect(fillPath, fillResult, fillAlpha)
                else paintFillWithReveal(
                        fillPath, fillResult, fillAlpha, fillP,
                        fillCfg?.fillMode ?: config.defaultFillMode,
                        fillCfg?.fillDirection ?: config.defaultFillDirection,
                        fillBox[0], fillBox[1], fillBox[2], fillBox[3], morph, width, side, props,
                    )
                val strokeContours = custom.strokeRevealContours ?: contours
                for (contour in strokeContours) {
                    val path = staticPath(contour)
                    val direction = drawCfg?.direction ?: DrawDirection.AUTO
                    val shown = if (custom.strokeRevealContours != null) path else buildRevealedStrokePath(
                        path, drawP, drawCfg?.mode ?: DrawMode.FORWARD, drawCfg?.start ?: PathStart.AUTO,
                        direction, null, drawAnchorFor(config, side.name, side.name),
                        config.timing,
                        warnings = { drawWarnings.addAll(it) },
                        anchorCollector = if (collectDrawAnchors) debugAnchorSink(transform, direction) else null,
                    )
                    if (!shown.isEmpty && width > 0f) paintStroke(
                        shown, strokeResult, strokeAlpha,
                        Stroke(
                            width = width,
                            cap = side.strokeLineCap,
                            join = side.strokeLineJoin,
                            miter = side.strokeLineMiter,
                        ),
                    )
                }
            }
            val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
            val clipDirection = props.clipReveal?.direction
                ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            withClipReveal(side.clipContours, clipProgress, clipDirection, contents)
        }
    }

    /** Tight (minX, minY, maxX, maxY) of the contours' control points; zeros when empty. */
    private fun customBounds(contours: List<ContourData>): FloatArray {
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
     * Clips [block] to the pair's inherited group clip paths, optionally revealing the clip
     * itself along a directional moving edge when [progress] is mid-reveal. No contours or
     * full progress draw unclipped.
     */
    private inline fun DrawScope.withClipReveal(
        contours: List<ContourData>,
        progress: Float,
        direction: DrawDirection,
        crossinline block: DrawScope.() -> Unit,
    ) {
        if (contours.isEmpty()) { block(); return }
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.003f) return
        val box = customBounds(contours)
        maskPath.rewind()
        contours.forEach { maskPath.addPath(staticPath(it)) }
        clipPath(maskPath) {
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

    // ------------------------------------------------------------------ morph pair

    /**
     * Clips [block] to an expanding circle centered in the given bounds — the fill-mode
     * RADIAL and DrawMode.RADIAL reveal implementation.
     */
    private inline fun DrawScope.withRadialClip(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        progress: Float,
        crossinline block: DrawScope.() -> Unit,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.003f) return
        if (p >= 0.997f) { block(); return }
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val radius = hypot(maxX - minX, maxY - minY) * 0.5f * p
        radialPath.rewind()
        radialPath.addOval(androidx.compose.ui.geometry.Rect(
            centerX - radius, centerY - radius, centerX + radius, centerY + radius,
        ))
        clipPath(radialPath) { block() }
    }

    /**
     * Draws a fully matched path pair: interpolated paint, interpolated stroke width, the
     * two complementary fill legs (each side's complete contour set so fill-rule holes
     * survive), and the per-contour stroke pass with reveal support.
     */
    private fun DrawScope.drawMorphPair(
        pair: PathPairPlan,
        frame: EvaluatedFrame,
        globalP: Float,
        config: MorphConfiguration,
        vectorAlpha: Float,
        alphaOverride: Float?,
    ) {
        val props = frame.pathProps[pair.index]
        val f = pair.fromPath!!
        val t = pair.toPath!!
        val morphT = TimelineEvaluator.morphFraction(props.props, globalP)

        val space = config.colorSpace
        val fillResult = animatedBrushResult(
            props.brushMode,
            props.primaryBrush,
            props.secondaryBrush,
            props.brushProgress,
            frame.vectorBrushMode,
            frame.vectorPrimaryBrush,
            frame.vectorSecondaryBrush,
            frame.vectorBrushProgress,
            GradientInterpolator.interpolate(f.fill, t.fill, morphT, space),
        )
        val strokeResult = animatedBrushResult(
            props.strokeBrushMode, props.primaryStrokeBrush, props.secondaryStrokeBrush,
            props.strokeBrushProgress,
            frame.vectorStrokeBrushMode, frame.vectorPrimaryStrokeBrush,
            frame.vectorSecondaryStrokeBrush, frame.vectorStrokeBrushProgress,
            GradientInterpolator.interpolate(f.stroke, t.stroke, morphT, space),
        )
        val fillAlpha = lerp(f.fillAlpha, t.fillAlpha, morphT) *
            (props.props[PropKey.FILL_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f) *
            vectorAlpha * (alphaOverride ?: 1f)
        val strokeAlpha = lerp(f.strokeAlpha, t.strokeAlpha, morphT) *
            (props.props[PropKey.STROKE_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f) *
            vectorAlpha * (alphaOverride ?: 1f)
        val strokeWidth = lerp(f.strokeWidth, t.strokeWidth, morphT).let {
            val o = props.props[PropKey.STROKE_WIDTH.ordinal]
            if (o.isNaN()) it else o
        }

        val drawProgress = props.props[PropKey.DRAW_PROGRESS.ordinal].takeUnless { it.isNaN() }
        val fillProgress = props.props[PropKey.FILL_PROGRESS.ordinal].takeUnless { it.isNaN() }

        // draw config from the DSL track (mode/start/direction for reveals)
        val drawCfg = props.strokeReveal
            ?: config.animation?.takeIf { drawProgress != null }?.strokeRevealFor(
                f.name, t.name, f.index, t.index, PropKey.DRAW_PROGRESS, f.groupNames, t.groupNames,
            )
        val clipCfg = props.clipReveal
        val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
        val clipDirection = clipCfg?.direction ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
        // Group clips live in viewport space; use the active endpoint while its contents morph.
        val clipContours = if (morphT < 0.5f) f.clipContours else t.clipContours
        val fillCfg = props.fillReveal
            ?: config.animation?.takeIf { fillProgress != null }?.fillRevealFor(
                f.name, t.name, f.index, t.index, PropKey.FILL_PROGRESS, f.groupNames, t.groupNames,
            )

        val transform = PairTransform.from(props, f, t, morphT)

        // interpolated bounds of the pair (used by reveals + pivots)
        val bMinX = lerp(f.bounds[0], t.bounds[0], morphT)
        val bMinY = lerp(f.bounds[1], t.bounds[1], morphT)
        val bMaxX = lerp(f.bounds[2], t.bounds[2], morphT)
        val bMaxY = lerp(f.bounds[3], t.bounds[3], morphT)

        withPairTransform(transform) {
            // ---- fill pass
            if (fillResult !is GradientInterpolator.Result.None && fillAlpha > 0.003f) {
                // A stroke reveal with no explicit fill track owns the WHOLE path: the fill
                // follows the same reveal progress, otherwise a filled icon (AdditemSolid)
                // stays fully painted at draw progress 0 — the pen draws the outline of a
                // shape that should still be invisible.
                val effectiveFill = fillProgress
                    ?: when {
                        pair.strokeFillDraw -> ((morphT - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        drawProgress != null -> drawProgress
                        else -> 1f
                    }
                val fillMode = fillCfg?.fillMode ?: config.defaultFillMode
                val fillDir = fillCfg?.fillDirection ?: config.defaultFillDirection

                val customFillContours = props.customOverrides?.fillContours
                if (customFillContours != null) {
                    val customBox = customBounds(customFillContours)
                    // Custom replacement geometry is also filled as one combined path so a
                    // custom contour that punches a hole keeps working (same fill rule).
                    val combined = contoursFillPath(customFillContours, t.pathFillType)
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        paintFillWithReveal(
                            combined, fillResult, fillAlpha, effectiveFill, fillMode, fillDir,
                            customBox[0], customBox[1], customBox[2], customBox[3], morphT,
                            strokeWidth, t, props,
                        )
                    }
                } else {
                    // Fill-rule correctness needs each side's COMPLETE contour set in one
                    // path: solid icons encode inner symbols as contours winding opposite
                    // to their enclosing shape, and unmatched contours belong to exactly
                    // one side. The fill is therefore painted as two complementary legs of
                    // the same interpolated geometry — the from leg carries the from side's
                    // full set (paired geometry + its unmatched contours) at (1 - t) alpha,
                    // the to leg carries the to side's full set at t alpha. Each leg's fill
                    // rule cancels its own holes, so both endpoints render their icons
                    // exactly, unmatched shapes dissolve in place, and shared regions stay
                    // fully covered because the leg alphas sum to one. Crossfading brush
                    // results fade through their own progress instead.
                    val isCrossfade = fillResult is GradientInterpolator.Result.Crossfade
                    val componentFrom =
                        if (fillResult is GradientInterpolator.Result.Crossfade) 1f - fillResult.progress else 1f
                    val componentTo =
                        if (fillResult is GradientInterpolator.Result.Crossfade) fillResult.progress else 1f
                    // FADE reveals the fill by alpha, not by a clipping mask (the clip
                    // helpers cannot express it), so the legs carry the progress directly.
                    val fadeAlpha = if (fillMode == FillMode.FADE) effectiveFill.coerceIn(0f, 1f) else 1f
                    val fromLegAlpha = (fillAlpha * componentFrom * fadeAlpha * if (isCrossfade) 1f else 1f - morphT).coerceIn(0f, 1f)
                    val toLegAlpha = (fillAlpha * componentTo * fadeAlpha * if (isCrossfade) 1f else morphT).coerceIn(0f, 1f)
                    val fromExtrasList = fromExtras.getOrNull(pair.index).orEmpty()
                    val toExtrasList = toExtras.getOrNull(pair.index).orEmpty()
                    val revealActive = effectiveFill < 0.997f
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        // A leg paints whenever its side has ANY geometry: paired contours,
                        // unmatched contours, or both (fully unmatched pairs fade entirely
                        // through the legs).
                        if (fromLegAlpha > 0.003f && (pair.contourPairs.isNotEmpty() || fromExtrasList.isNotEmpty())) {
                            val leg = combinedMorphFillPath(pair, morphT, f.pathFillType, slot = 0, extras = fromExtrasList)
                            if (revealActive) {
                                // The pen owns the whole path: the fill sweeps in with the
                                // same reveal (directional clip keeps fill-rule holes safe).
                                withFillRevealClip(effectiveFill, fillMode, fillDir, bMinX, bMinY, bMaxX, bMaxY) {
                                    paintFillLegDirect(leg, fillResult, fromLeg = true, alpha = fromLegAlpha)
                                }
                            } else {
                                paintFillLegDirect(leg, fillResult, fromLeg = true, alpha = fromLegAlpha)
                            }
                        }
                        if (toLegAlpha > 0.003f && (pair.contourPairs.isNotEmpty() || toExtrasList.isNotEmpty())) {
                            val leg = combinedMorphFillPath(pair, morphT, t.pathFillType, slot = 1, extras = toExtrasList)
                            if (revealActive) {
                                withFillRevealClip(effectiveFill, fillMode, fillDir, bMinX, bMinY, bMaxX, bMaxY) {
                                    paintFillLegDirect(leg, fillResult, fromLeg = false, alpha = toLegAlpha)
                                }
                            } else {
                                paintFillLegDirect(leg, fillResult, fromLeg = false, alpha = toLegAlpha)
                            }
                        }
                    }
                }
            }

            // ---- stroke pass (with reveal / pen effect)
            if (strokeResult !is GradientInterpolator.Result.None && strokeAlpha > 0.003f && strokeWidth > 0f) {
                val style = Stroke(
                    width = strokeWidth,
                    cap = t.strokeLineCap,
                    join = t.strokeLineJoin,
                    miter = t.strokeLineMiter,
                )
                val customStrokeContours = props.customOverrides?.strokeRevealContours
                if (customStrokeContours != null) {
                    customStrokeContours.forEach { contour ->
                        withClipReveal(clipContours, clipProgress, clipDirection) {
                            paintStroke(staticPath(contour), strokeResult, strokeAlpha, style)
                        }
                    }
                } else for ((ci, cp) in pair.contourPairs.withIndex()) {
                    val path = morphContourPath(pair.index, ci, cp, morphT)
                    if (drawProgress != null && drawProgress <= 0f) continue
                    if (drawProgress != null && drawProgress < 1f) {
                        val mode = drawCfg?.mode ?: DrawMode.FORWARD
                        val start = drawCfg?.start ?: PathStart.AUTO
                        val direction = drawCfg?.direction ?: DrawDirection.AUTO
                        if (mode == DrawMode.RADIAL) {
                            withClipReveal(clipContours, clipProgress, clipDirection) {
                                withRadialClip(bMinX, bMinY, bMaxX, bMaxY, drawProgress) {
                                    paintStroke(path, strokeResult, strokeAlpha, style)
                                }
                            }
                        } else {
                            val strokeGeometry = if (cp.from.closed && shouldReverseWinding(cp.from.windingSign, direction))
                                morphContourPath(pair.index, ci, cp, morphT, reverse = true) else path
                            val customStart = drawAnchorFor(config, f.name, t.name)
                            val revealed = buildRevealedStrokePath(
                                strokeGeometry, drawProgress, mode, start, direction, cp, customStart,
                                config.timing, ci, pair.contourPairs.size, max(f.totalLength, t.totalLength),
                                closed = cp.from.closed && cp.to.closed,
                                honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                                warnings = { drawWarnings.addAll(it) },
                                anchorCollector = if (collectDrawAnchors) debugAnchorSink(transform, direction) else null,
                            )
                            if (revealed.isEmpty) continue
                            withClipReveal(clipContours, clipProgress, clipDirection) {
                                paintStroke(revealed, strokeResult, strokeAlpha, style)
                            }
                        }
                    } else {
                        withClipReveal(clipContours, clipProgress, clipDirection) {
                            paintStroke(path, strokeResult, strokeAlpha, style)
                        }
                    }
                }
                if (customStrokeContours == null) pair.fromOnlyContours.forEach { c ->
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        paintStroke(staticPath(c), strokeResult, strokeAlpha * (1f - morphT), style)
                    }
                }
                if (customStrokeContours == null) pair.toOnlyContours.forEach { c ->
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        paintStroke(staticPath(c), strokeResult, strokeAlpha * morphT, style)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ painting helpers

    /**
     * Resolves the effective paint for one pass: path-level brush overrides win over
     * vector-level ones; when neither exists the caller's interpolated fallback is used.
     */
    private fun animatedBrushResult(
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
     * Fills [path] honoring the fill reveal mode. Masking uses clip ops — the underlying
     * brush stays a real (possibly gradient) brush, so gradients reveal correctly.
     */
    private fun DrawScope.paintFillWithReveal(
        path: Path,
        result: GradientInterpolator.Result,
        alpha: Float,
        fillProgress: Float,
        mode: FillMode,
        direction: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        morphT: Float,
        strokeWidth: Float,
        t: PreparedPath,
        props: com.imorpher.vectormorph.core.animation.PathFrameValues,
    ) {
        val prog = fillProgress.coerceIn(0f, 1f)
        if (prog <= 0.003f) return
        if (alpha <= 0.003f) return

        if (prog >= 1f) {
            paintFillDirect(path, result, alpha)
            return
        }

        val w = (bMaxX - bMinX).coerceAtLeast(1e-3f)
        val h = (bMaxY - bMinY).coerceAtLeast(1e-3f)

        when (mode) {
            FillMode.FADE -> paintFillDirect(path, result, alpha * prog)

            FillMode.RADIAL -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = prog * (hypot(w, h) / 2f)
                maskPath.rewind()
                maskPath.addOval(
                    androidx.compose.ui.geometry.Rect(
                        left = cx - r, top = cy - r, right = cx + r, bottom = cy + r,
                    )
                )
                clipPath(maskPath) { paintFillDirect(path, result, alpha) }
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
                clipPath(maskPath) { paintFillDirect(path, result, alpha) }
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
                    rect = androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r),
                    startAngleDegrees = startAngle,
                    sweepAngleDegrees = (if (clockwise) 1f else -1f) * 360f * prog,
                    forceMoveTo = false,
                )
                maskPath.close()
                clipPath(maskPath) { paintFillDirect(path, result, alpha) }
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
                            drawPath(revealed, result.brush, alpha.coerceIn(0f, 1f), style = Stroke(width = width))
                        is GradientInterpolator.Result.Crossfade -> {
                            drawPath(revealed, result.fromBrush, (alpha * (1f - result.progress)).coerceIn(0f, 1f), style = Stroke(width = width))
                            drawPath(revealed, result.toBrush, (alpha * result.progress).coerceIn(0f, 1f), style = Stroke(width = width))
                        }
                        GradientInterpolator.Result.None -> Unit
                    }
                }
                when {
                    drawMode == DrawMode.RADIAL ->
                        withRadialClip(bMinX, bMinY, bMaxX, bMaxY, prog) { paintTraversal(path) }
                    drawMode == DrawMode.CUSTOM || drawDirection == DrawDirection.CUSTOM -> {
                        val customContours = props.customOverrides?.fillContours
                            ?: props.customOverrides?.strokeRevealContours
                            ?: error("Custom path-traversal fill requires custom fillContours or strokeRevealContours")
                        customContours.forEach { paintTraversal(staticPath(it)) }
                    }
                    else -> paintTraversal(
                        // Fill-sweep reuse of the reveal machinery, not a pen anchor: no
                        // debug anchor collector here (rule 7 covers stroke reveals), but
                        // the warnings still land in the frame collector.
                        buildRevealedStrokePath(
                            path, prog, drawMode,
                            strokeDraw?.start ?: PathStart.AUTO,
                            drawDirection,
                            null,
                            closed = t.contours.all { it.closed },
                            warnings = { drawWarnings.addAll(it) },
                        )
                    )
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

    /** Minimal fade-only fill used where reveal masking is unnecessary or already applied. */
    private fun DrawScope.paintFillSimple(
        path: Path,
        result: GradientInterpolator.Result,
        alpha: Float,
        fillProgress: Float,
        fillType: PathFillType,
    ) {
        if (alpha <= 0.003f || fillProgress <= 0.003f) return
        path.fillType = fillType
        paintFillDirect(path, result, alpha * fillProgress.coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------------ stroke reveal (the pen effect)

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
    private fun buildRevealedStrokePath(
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
     * Resolves the `PathStart.CUSTOM` draw anchor for one stroke-reveal site:
     * [MorphConfiguration.customDrawAnchors] first (the new per-path draw anchor map), then
     * [MorphConfiguration.customStartOffsets] (legacy fallback for one release), with the same
     * `from`-then-`to` name precedence at every site. One definition for all three call sites
     * fixes the plan's C4 inconsistency (the pair pass used `from ?: to`, the static sites only
     * looked at their own name); the static single-path sites pass their own path name for
     * both legs.
     */
    private fun drawAnchorFor(config: MorphConfiguration, fromName: String, toName: String): Float =
        config.customDrawAnchors[fromName]
            ?: config.customDrawAnchors[toName]
            ?: config.customStartOffsets[fromName]
            ?: config.customStartOffsets[toName]
            ?: 0f

    /**
     * Builds the per-reveal-site debug collector (start/direction coordination plan rule 7):
     * maps the reveal-space anchor through the site's [tr] pair transform — the same
     * translate→rotate→scale ops [withPairTransform] applies to the canvas — and stores it
     * with [arrowDirection]'s configured travel axis for the overlay to draw. Constructed
     * only while [collectDrawAnchors], never otherwise.
     */
    private fun debugAnchorSink(
        tr: PairTransform,
        arrowDirection: DrawDirection,
    ): (anchorX: Float, anchorY: Float, travelLength: Float) -> Unit = { x, y, travelLength ->
        val mapped = tr.map(x, y)
        drawAnchors += ResolvedDrawAnchor(mapped.x, mapped.y, arrowDirection, travelLength)
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

    // ------------------------------------------------------------------ static paths

    /**
     * Draws one endpoint's path unchanged (crossfade pairs and KEEP/SCALE missing paths),
     * with the path's own geometry, paint, clip contours, and any timeline style overrides.
     */
    private fun DrawScope.drawStaticSide(
        pair: PathPairPlan,
        side: PreparedPath,
        frame: EvaluatedFrame,
        alpha: Float,
        config: MorphConfiguration,
    ) {
        if (alpha <= 0.003f) return
        val props = frame.pathProps[pair.index]
        val fillBrush = side.fill?.let { GradientInterpolator.run { it.buildBrush() } }
        val strokeBrush = side.stroke?.let { GradientInterpolator.run { it.buildBrush() } }
        val fillResult = animatedBrushResult(
            props.brushMode, props.primaryBrush, props.secondaryBrush, props.brushProgress,
            frame.vectorBrushMode, frame.vectorPrimaryBrush, frame.vectorSecondaryBrush, frame.vectorBrushProgress,
            fillBrush?.let { GradientInterpolator.Result.Interpolated(it, 1f) } ?: GradientInterpolator.Result.None,
        )
        val strokeResult = animatedBrushResult(
            props.strokeBrushMode, props.primaryStrokeBrush, props.secondaryStrokeBrush, props.strokeBrushProgress,
            frame.vectorStrokeBrushMode, frame.vectorPrimaryStrokeBrush, frame.vectorSecondaryStrokeBrush,
            frame.vectorStrokeBrushProgress,
            strokeBrush?.let { GradientInterpolator.Result.Interpolated(it, 1f) } ?: GradientInterpolator.Result.None,
        )
        // Timeline fill/stroke-alpha and stroke-width overrides apply to static sides
        // (crossfades, KEEP/SCALE missing paths) exactly as they do to MORPH pairs.
        val fillAlphaOverride = props.props[PropKey.FILL_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
        val strokeAlphaOverride = props.props[PropKey.STROKE_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
        val strokeWidthOverride = props.props[PropKey.STROKE_WIDTH.ordinal].takeUnless { it.isNaN() }
        val transform = PairTransform.from(props, side, side, 0f)
        withPairTransform(transform) {
            val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
            val clipDirection = props.clipReveal?.direction
                ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            if (side.clipContours.isEmpty()) {
                drawStaticContents(side, fillResult, strokeResult, alpha, fillAlphaOverride, strokeAlphaOverride, strokeWidthOverride)
            } else {
                withClipReveal(side.clipContours, clipProgress, clipDirection) {
                    drawStaticContents(side, fillResult, strokeResult, alpha, fillAlphaOverride, strokeAlphaOverride, strokeWidthOverride)
                }
            }
        }
    }

    /** Fill + stroke of a static side; contours fill together so fill-rule holes survive. */
    private fun DrawScope.drawStaticContents(
        side: PreparedPath,
        fillResult: GradientInterpolator.Result,
        strokeResult: GradientInterpolator.Result,
        alpha: Float,
        fillAlphaOverride: Float = 1f,
        strokeAlphaOverride: Float = 1f,
        strokeWidthOverride: Float? = null,
    ) {
        if (fillResult !is GradientInterpolator.Result.None && side.fillAlpha > 0f) {
            // One fill path for every contour: solid icons rely on the fill rule to punch
            // their inner symbol out of the fill (the plus inside AddCircleSolid is a hole,
            // not painted geometry), which only works when contours are filled together.
            paintFillDirect(staticFillPath(side), fillResult, alpha * side.fillAlpha * fillAlphaOverride)
        }
        val width = strokeWidthOverride ?: side.strokeWidth
        if (strokeResult !is GradientInterpolator.Result.None && side.strokeAlpha > 0f && width > 0f) {
            val strokeAlpha = alpha * side.strokeAlpha * strokeAlphaOverride
            val style = Stroke(
                width = width,
                cap = side.strokeLineCap,
                join = side.strokeLineJoin,
                miter = side.strokeLineMiter,
            )
            side.contours.forEach { paintStroke(staticPath(it), strokeResult, strokeAlpha, style) }
        }
    }

    /**
     * Draws a path that only exists on one side, applying the configured
     * [MissingPathBehavior]: fade, draw-on, scale/collapse, keep, or a custom callback.
     */
    private fun DrawScope.drawMissingSide(
        pair: PathPairPlan,
        side: PreparedPath,
        frame: EvaluatedFrame,
        p: Float,
        config: MorphConfiguration,
        vectorAlpha: Float,
        alphaOverride: Float?,
        appearing: Boolean,
    ) {
        val props = frame.pathProps[pair.index]
        val alpha = (alphaOverride ?: 1f) * vectorAlpha
        val behavior = config.missingPath
        val fade = if (appearing) p.coerceIn(0f, 1f) else (1f - p).coerceIn(0f, 1f)

        when (behavior) {
            MissingPathBehavior.DRAW -> {
                if (appearing) {
                    drawMissingPathByDrawing(pair, side, frame, p, config, alpha)
                } else {
                    drawMissingPathByDrawing(pair, side, frame, 1f - p, config, alpha)
                }
            }
            MissingPathBehavior.KEEP -> drawStaticSide(pair, side, frame, alpha, config)
            MissingPathBehavior.SCALE, MissingPathBehavior.COLLAPSE -> {
                val s = when {
                    appearing && behavior == MissingPathBehavior.COLLAPSE -> popScale(p)
                    appearing -> p.coerceAtLeast(0.001f)
                    else -> fade.coerceAtLeast(0.001f)
                }
                withTransform({
                    translate(side.centroidX, side.centroidY)
                    scale(s, s, pivot = Offset.Zero)
                    translate(-side.centroidX, -side.centroidY)
                }) {
                    drawStaticSide(pair, side, frame, alpha, config)
                }
            }
            else -> drawStaticSide(pair, side, frame, alpha * fade, config)
        }
    }

    /**
     * The DRAW missing-path behavior: the path draws itself on with the stroke reveal
     * (per contour, winding-ordered) while the fill sweeps in behind it — the appearing
     * icon gets the pen treatment instead of a plain alpha ramp.
     */
    private fun DrawScope.drawMissingPathByDrawing(
        pair: PathPairPlan,
        side: PreparedPath,
        frame: EvaluatedFrame,
        globalProgress: Float,
        config: MorphConfiguration,
        alpha: Float,
    ) {
        if (alpha <= 0.003f) return
        val props = frame.pathProps[pair.index]
        val drawProgress = props.props[PropKey.DRAW_PROGRESS.ordinal].takeUnless { it.isNaN() }
            ?: globalProgress
        val fillProgress = props.props[PropKey.FILL_PROGRESS.ordinal].takeUnless { it.isNaN() }
            ?: globalProgress
        val drawConfig = props.strokeReveal ?: config.animation?.strokeRevealFor(
            null, side.name, -1, side.index, PropKey.DRAW_PROGRESS, emptyList(), side.groupNames,
        )
        val fillConfig = props.fillReveal ?: config.animation?.fillRevealFor(
            null, side.name, -1, side.index, PropKey.FILL_PROGRESS, emptyList(), side.groupNames,
        )
        val fallbackFill = side.fill?.let { GradientInterpolator.run { it.buildBrush() } }
            ?.let { GradientInterpolator.Result.Interpolated(it, 1f) }
            ?: GradientInterpolator.Result.None
        val fillResult = animatedBrushResult(
            props.brushMode, props.primaryBrush, props.secondaryBrush, props.brushProgress,
            frame.vectorBrushMode, frame.vectorPrimaryBrush, frame.vectorSecondaryBrush, frame.vectorBrushProgress,
            fallbackFill,
        )
        val strokeResult = side.stroke?.let { GradientInterpolator.run { it.buildBrush() } }
            ?.let { GradientInterpolator.Result.Interpolated(it, 1f) }
            ?: GradientInterpolator.Result.None
        val strokeResultWithAnimation = animatedBrushResult(
            props.strokeBrushMode, props.primaryStrokeBrush, props.secondaryStrokeBrush, props.strokeBrushProgress,
            frame.vectorStrokeBrushMode, frame.vectorPrimaryStrokeBrush, frame.vectorSecondaryStrokeBrush,
            frame.vectorStrokeBrushProgress, strokeResult,
        )
        val pathAlpha = alpha * (props.props[PropKey.ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f)
        // Timeline fill/stroke-alpha multipliers apply here too — not just to MORPH pairs.
        val fillAlphaOverride = props.props[PropKey.FILL_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
        val strokeAlphaOverride = props.props[PropKey.STROKE_ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
        val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
        val clipDirection = props.clipReveal?.direction
            ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
        val transform = PairTransform.from(props, side, side, 0f)
        withPairTransform(transform) {
            // The fill covers the WHOLE side (all contours in one path so fill-rule
            // holes survive): paint it exactly once, outside the per-contour stroke loop.
            if (fillResult !is GradientInterpolator.Result.None && side.fillAlpha > 0f) {
                withClipReveal(side.clipContours, clipProgress, clipDirection) {
                    paintFillWithReveal(
                        staticFillPath(side),
                        fillResult,
                        pathAlpha * side.fillAlpha * fillAlphaOverride,
                        fillProgress,
                        fillConfig?.fillMode ?: config.defaultFillMode,
                        fillConfig?.fillDirection ?: config.defaultFillDirection,
                        side.bounds[0], side.bounds[1], side.bounds[2], side.bounds[3],
                        globalProgress,
                        side.strokeWidth,
                        side,
                        props,
                    )
                }
            }
            side.contours.forEachIndexed { contourIndex, contour ->
                val path = staticPath(contour)
                if (strokeResultWithAnimation !is GradientInterpolator.Result.None && side.strokeWidth > 0f) {
                    val strokeMode = drawConfig?.mode ?: DrawMode.FORWARD
                    val strokeDirection = drawConfig?.direction ?: DrawDirection.AUTO
                    val winding = side.windingSigns.getOrNull(contourIndex) ?: 0
                    val strokeSource = if (contour.closed && shouldReverseWinding(winding, strokeDirection))
                        staticPath(reversedContour(contour)) else path
                    val style = Stroke(
                        width = side.strokeWidth,
                        cap = side.strokeLineCap,
                        join = side.strokeLineJoin,
                        miter = side.strokeLineMiter,
                    )
                    if (strokeMode == DrawMode.RADIAL) {
                        withClipReveal(side.clipContours, clipProgress, clipDirection) {
                            withRadialClip(side.bounds[0], side.bounds[1], side.bounds[2], side.bounds[3], drawProgress) {
                                paintStroke(path, strokeResultWithAnimation, pathAlpha * side.strokeAlpha * strokeAlphaOverride, style)
                            }
                        }
                    } else {
                        val revealed = buildRevealedStrokePath(
                            strokeSource,
                            drawProgress,
                            strokeMode,
                            drawConfig?.start ?: PathStart.AUTO,
                            strokeDirection,
                            null,
                            drawAnchorFor(config, side.name, side.name),
                            config.timing,
                            contourIndex,
                            side.contours.size,
                            side.totalLength,
                            side.arcLengths.getOrNull(contourIndex),
                            closed = contour.closed,
                            warnings = { drawWarnings.addAll(it) },
                            anchorCollector = if (collectDrawAnchors) debugAnchorSink(transform, strokeDirection) else null,
                        )
                        withClipReveal(side.clipContours, clipProgress, clipDirection) {
                            paintStroke(revealed, strokeResultWithAnimation, pathAlpha * side.strokeAlpha * strokeAlphaOverride, style)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ path management

    /**
     * Lerps one contour pair's packed coordinates at [morphT] into a Compose [Path],
     * cached per (pair, contour) and rewritten each frame. [reverse] traverses the same
     * geometry backwards for direction-ordered reveals; closure follows the active side so
     * open↔closed transitions never paint a ghost segment at the wrong endpoint.
     */
    private fun morphContourPath(
        pairIndex: Int,
        contourIndex: Int,
        cp: ContourPairPlan,
        morphT: Float,
        reverse: Boolean = false,
    ): Path {
        val key = pairIndex.toLong() * 4096L + contourIndex
        val path = contourPathCache.getOrPut(key) { Path() }
        path.rewind()
        val from = cp.from.coords
        val to = cp.to.coords
        val n = from.size
        coords = ensureCapacity(coords, n)
        val mt = 1f - morphT
        for (i in 0 until n) coords[i] = from[i] * mt + to[i] * morphT
        if (n >= 8) {
            if (!reverse) {
                path.moveTo(coords[0], coords[1])
                var i = 0
                while (i + 8 <= n) {
                    path.cubicTo(coords[i + 2], coords[i + 3], coords[i + 4], coords[i + 5], coords[i + 6], coords[i + 7])
                    i += 8
                }
            } else {
                path.moveTo(coords[n - 2], coords[n - 1])
                var i = n - 8
                while (i >= 0) {
                    path.cubicTo(coords[i + 4], coords[i + 5], coords[i + 2], coords[i + 3], coords[i], coords[i + 1])
                    i -= 8
                }
            }
        }
        // Closure follows the active side: closed-ness can differ across a pair (a filled
        // solid contour morphing into an open stroke contour), and closing from the wrong
        // side paints a ghost segment (a stroke across the open end) at that endpoint.
        if (if (morphT < 0.5f) cp.from.closed else cp.to.closed) path.close()
        return path
    }

    /**
     * Builds one side's fill geometry for a morphing path as one combined path: every
     * morphing contour plus [extras] (that side's paintable unmatched contours), carrying
     * the side's fill type so its fill rule can punch holes (see [drawMorphPair]).
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
            path.addPath(morphContourPath(pair.index, ci, cp, morphT))
        }
        extras.forEach { path.addPath(staticPath(it)) }
        return path
    }

    /**
     * Clips [block] to the portion of the path box already reached by a fill reveal at
     * [progress]: directional/sweep masks grow across the interpolated bounds, radial
     * grows from the center. Used when a stroke reveal owns the whole path — the fill
     * sweeps in behind the pen without touching the brush itself (gradients stay true).
     */
    private inline fun DrawScope.withFillRevealClip(
        progress: Float,
        mode: FillMode,
        direction: DrawDirection,
        bMinX: Float, bMinY: Float, bMaxX: Float, bMaxY: Float,
        crossinline block: DrawScope.() -> Unit,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p >= 0.997f) { block(); return }
        if (p <= 0.003f) return
        val w = (bMaxX - bMinX).coerceAtLeast(1e-3f)
        val h = (bMaxY - bMinY).coerceAtLeast(1e-3f)
        when (mode) {
            FillMode.RADIAL -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = p * (hypot(w, h) / 2f)
                maskPath.rewind()
                maskPath.addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
                clipPath(maskPath) { block() }
            }
            FillMode.SWEEP -> {
                val cx = (bMinX + bMaxX) / 2f
                val cy = (bMinY + bMaxY) / 2f
                val r = hypot(w, h)
                val clockwise = direction != DrawDirection.COUNTER_CLOCKWISE
                maskPath.rewind()
                maskPath.moveTo(cx, cy)
                maskPath.arcTo(
                    rect = androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = (if (clockwise) 1f else -1f) * 360f * p,
                    forceMoveTo = false,
                )
                maskPath.close()
                clipPath(maskPath) { block() }
            }
            FillMode.FADE -> block()
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
                clipPath(maskPath) { block() }
            }
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
        contours.forEach { fillPath.addPath(staticPath(it)) }
        return fillPath
    }

    /** Immutable contour geometry as a Compose [Path], built once per plan and reused. */
    private fun staticPath(contour: ContourData): Path {
        val path = staticContourCache.getOrPut(contour) { Path() }
        path.rewind()
        val segs = contour.segments
        if (segs.isEmpty()) return path
        path.moveTo(segs[0].x0, segs[0].y0)
        for (s in segs) {
            path.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x3, s.y3)
        }
        if (contour.closed) path.close()
        return path
    }

    /** The contour traversed end→start, cached so repeated reveals share one instance. */
    private fun reversedContour(contour: ContourData): ContourData =
        reversedContourCache.getOrPut(contour) { contour.reversed() }

    /** Whether a contour must run backwards to honor a CLOCKWISE/COUNTER_CLOCKWISE reveal. */
    private fun shouldReverseWinding(windingSign: Int, direction: DrawDirection): Boolean = when (direction) {
        DrawDirection.CLOCKWISE -> windingSign < 0
        DrawDirection.COUNTER_CLOCKWISE -> windingSign > 0
        else -> false
    }

    /** Grows the lerp buffer geometrically; sized generously so growth is rare. */
    private fun ensureCapacity(buffer: FloatArray, needed: Int): FloatArray =
        if (buffer.size >= needed) buffer else FloatArray(needed * 2)

    /** Linear interpolation helper shared by the paint/geometry paths. */
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Ease-out-back scale for COLLAPSE missing-path behavior. */
    private fun popScale(p: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = p - 1f
        val v = 1f + c3 * x * x * x + c1 * x * x
        return v.coerceAtLeast(0.001f)
    }

    // ------------------------------------------------------------------ pair transform

    /**
     * A path-level animated transform (scale/rotation/translation around an interpolated
     * fractional pivot of the pair's bounds). [Identity] skips all canvas ops.
     */
    private class PairTransform(
        /** Uniform X scale; 1 = unchanged. */
        val sx: Float,
        /** Y scale (follows [sx] when the timeline only animated SCALE_X). */
        val sy: Float,
        /** Clockwise rotation in degrees. */
        val rotation: Float,
        /** X translation in viewport units. */
        val tx: Float,
        /** Y translation in viewport units. */
        val ty: Float,
        /** Absolute pivot X the scale/rotation resolve around. */
        val pivotX: Float,
        /** Absolute pivot Y the scale/rotation resolve around. */
        val pivotY: Float,
    ) {
        val isIdentity: Boolean
            get() = sx == 1f && sy == 1f && rotation == 0f && tx == 0f && ty == 0f

        /**
         * Maps a vector-space point through this transform — the same
         * translate→rotate→scale ops [withPairTransform] applies to the canvas — so debug
         * markers recorded inside a transformed pair still land on their stroke.
         */
        fun map(x: Float, y: Float): Offset {
            var px = (x - pivotX) * sx
            var py = (y - pivotY) * sy
            if (rotation != 0f) {
                val radians = Math.toRadians(rotation.toDouble()).toFloat()
                val c = cos(radians)
                val s = sin(radians)
                val rotatedX = px * c - py * s
                py = px * s + py * c
                px = rotatedX
            }
            return Offset(px + pivotX + tx, py + pivotY + ty)
        }

        companion object {
            val Identity = PairTransform(1f, 1f, 0f, 0f, 0f, 0f, 0f)

            fun from(
                props: com.imorpher.vectormorph.core.animation.PathFrameValues,
                f: PreparedPath,
                t: PreparedPath,
                morphT: Float,
            ): PairTransform {
                val arr = props.props
                val sx = arr[PropKey.SCALE_X.ordinal].takeUnless { it.isNaN() } ?: 1f
                val syRaw = arr[PropKey.SCALE_Y.ordinal]
                val sy = if (syRaw.isNaN() || syRaw == -1f) sx else syRaw
                val rot = arr[PropKey.ROTATION_DEG.ordinal].takeUnless { it.isNaN() } ?: 0f
                val tx = arr[PropKey.TRANSLATION_X.ordinal].takeUnless { it.isNaN() } ?: 0f
                val ty = arr[PropKey.TRANSLATION_Y.ordinal].takeUnless { it.isNaN() } ?: 0f
                val px = arr[PropKey.PIVOT_X.ordinal].takeUnless { it.isNaN() } ?: 0.5f
                val py = arr[PropKey.PIVOT_Y.ordinal].takeUnless { it.isNaN() } ?: 0.5f
                if (sx == 1f && sy == 1f && rot == 0f && tx == 0f && ty == 0f) return Identity
                val minX = lerpB(f.bounds[0], t.bounds[0], morphT)
                val minY = lerpB(f.bounds[1], t.bounds[1], morphT)
                val maxX = lerpB(f.bounds[2], t.bounds[2], morphT)
                val maxY = lerpB(f.bounds[3], t.bounds[3], morphT)
                return PairTransform(sx, sy, rot, tx, ty, minX + (maxX - minX) * px, minY + (maxY - minY) * py)
            }

            private fun lerpB(a: Float, b: Float, t: Float) = a + (b - a) * t
        }
    }

    /** Applies the path-level transform before [block]; identity transforms draw in place. */
    private inline fun DrawScope.withPairTransform(tr: PairTransform, block: DrawScope.() -> Unit) {
        if (tr.isIdentity) {
            block()
        } else {
            withTransform({
                translate(tr.pivotX + tr.tx, tr.pivotY + tr.ty)
                rotate(tr.rotation)
                scale(tr.sx, tr.sy, pivot = Offset.Zero)
                translate(-tr.pivotX, -tr.pivotY)
            }) { block() }
        }
    }

    /**
     * One resolved draw anchor recorded for the debug overlay (start/direction coordination
     * plan rule 7): the pair-transform-mapped vector-space position where the pen began,
     * the configured [direction] (its `DirectionMath.axisFor` travel vector orients the
     * overlay arrow), and the arrow's [arrowLength] (≈10% of the recorded contour's bounds
     * diagonal, computed at the reveal site).
     */
    private class ResolvedDrawAnchor(
        val x: Float,
        val y: Float,
        val direction: DrawDirection,
        val arrowLength: Float,
    )
}
