package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.PropKey
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.animation.CustomVectorAnimation
import com.imorpher.vectormorph.core.animation.CustomVectorPathOverrides
import com.imorpher.vectormorph.core.animation.CustomVectorPathState
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator.buildBrush
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.plan.ContourPairPlan
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.PairKind
import com.imorpher.vectormorph.core.plan.PathPairPlan
import com.imorpher.vectormorph.core.reveal.DirectionMath
import com.imorpher.vectormorph.core.reveal.DrawOrder
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Resolves interpolated geometry for one contour of a morphing pair. The renderer owns the
 * per-(pair, contour) path cache and injects it so the fill and stroke passes share buffers.
 */
internal typealias MorphContourPathOf =
    (pairIndex: Int, contourIndex: Int, cp: ContourPairPlan, morphT: Float, reverse: Boolean) -> Path

/**
 * Draws a [MorphPlan] at a given progress — the ONLY per-frame hot path.
 *
 * Scratch paths, path measures, and coordinate buffers are reused. Matching, normalization,
 * subdivision, and path measurements are all complete before drawing; animated brush values are
 * rebuilt from their immutable specs as progress changes.
 *
 * What each kind of painting does is delegated to one pass collaborator — [ClipPass] for clip
 * masks and reveal clipping, [StrokePass] for strokes and the pen reveal, [FillPass] for fills
 * and fill reveals, [TintPass] for the tint layer. This class keeps the frame orchestration:
 * plan scratch state, pair dispatch and ordering, group nesting, transforms, custom callbacks,
 * debug overlays, and the interpolated-geometry caches the passes read through
 * [staticPath] and [morphContourPath] — one shared (pair, contour) buffer per frame rather
 * than one per pass.
 *
 * Rendering happens in viewport coordinates: the scope is transformed so that
 * (0..viewportWidth, 0..viewportHeight) maps into the layout bounds, aspect-preserved and
 * centered — icons never change visual scale regardless of layout size.
 */
class MorphRenderer {

    /** Reused packed-coordinate buffer for the per-frame control-point lerp. */
    private var coords = FloatArray(512)

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

    /** Scratch triangle for the debug resolution-warning indicator. */
    private val warningPath = Path()

    // per-contour rebuilt-from-scratch caches keyed by pairIndex*contour slot

    /** Interpolated contour geometry per (pair, contour); rewritten each frame. */
    private val contourPathCache = HashMap<Long, Path>()

    /** Immutable source/target contour geometry built once per plan. */
    private val staticContourCache = HashMap<ContourData, Path>()

    // Pass collaborators: each owns the scratch masks/paths and state its pass needs.

    /** Clip masks and reveal clipping. */
    private val clips = ClipPass(::staticPath)

    /** Stroke painting and the pen-drawing reveal. */
    private val strokes = StrokePass(clips, ::staticPath, ::morphContourPath)

    /** Fill painting and fill-reveal modes. */
    private val fills = FillPass(clips, ::staticPath, ::morphContourPath, strokes)

    /** Tint layer painting and endpoint spec resolution. */
    private val tint = TintPass()

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

    // Per-pair unmatched contours per side (rebuilt with plan scratch state): each side's
    // leg path carries its own extras so fill-rule holes cancel within the complete set.
    /** Per-pair unmatched source contours (the from leg's extra geometry). */
    private var fromExtras: Array<List<ContourData>> = emptyArray()

    /** Per-pair unmatched target contours (the to leg's extra geometry). */
    private var toExtras: Array<List<ContourData>> = emptyArray()


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
        alignment: Alignment = Alignment.Center,
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
        val contentWidth = vw * uniform
        val contentHeight = vh * uniform
        // Alignment.Center keeps the exact historical float centering; other alignments resolve
        // through Compose's Alignment contract (integer layout offsets, LTR).
        val dx: Float
        val dy: Float
        if (alignment === Alignment.Center) {
            dx = (drawScope.size.width - contentWidth) / 2f
            dy = (drawScope.size.height - contentHeight) / 2f
        } else {
            val offset = alignment.align(
                size = IntSize(contentWidth.roundToInt(), contentHeight.roundToInt()),
                space = IntSize(drawScope.size.width.roundToInt(), drawScope.size.height.roundToInt()),
                layoutDirection = LayoutDirection.Ltr,
            )
            dx = offset.x.toFloat()
            dy = offset.y.toFloat()
        }

        val vAlpha = frame.vectorProps[PropKey.ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
        val tintResult = tint.resultFor(tintFrom, tintTo, drawScope.size, p, config.colorSpace)
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

        tint.paint(
            drawScope = drawScope,
            result = tintResult,
            plan = plan,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            uniformScale = uniform,
            dx = dx,
            dy = dy,
            drawContents = { drawIconContents() },
        )
        drawDebug()
    }

    /**
     * Runs a [CustomVectorAnimation] callback exactly once per failing instance: exceptions are
     * caught, recorded as a draw warning, and the callback is skipped from then on.
     */
    private fun runCustomizer(
        animation: VectorAnimation?,
        state: CustomVectorPathState,
    ): CustomVectorPathOverrides? {
        val customizer = animation?.customizer ?: return null
        if (customizer in failedCustomizers) return null
        return try {
            customizer.transform(state)
        } catch (exception: Exception) {
            failedCustomizers.add(customizer)
            drawWarnings.add(DrawResolutionWarning.CUSTOM_ANIMATION_FAILED)
            CustomizerFailures.record(exception)
            null
        }
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

        /**
         * Custom animation callbacks that threw once, process-wide. A callback is disabled after
         * its first failure so a buggy per-frame callback cannot crash every subsequent frame
         * (or every new renderer); the failure is surfaced through the frame's draw warnings and
         * the pair renders exactly as if no customizer were set.
         */
        val failedCustomizers: MutableSet<CustomVectorAnimation> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())
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
        fills.onPlanChanged()
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
        val custom = runCustomizer(
            config.animation,
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
                fills.drawCustomFill(
                    drawScope = this,
                    contours = custom.fillContours ?: contours,
                    side = side,
                    result = fillResult,
                    alpha = fillAlpha,
                    strokeWidth = width,
                    props = props,
                    reveal = custom.fillContours == null,
                    fillProgress = fillP,
                    fillMode = fillCfg?.fillMode ?: config.defaultFillMode,
                    fillDir = fillCfg?.fillDirection ?: config.defaultFillDirection,
                    timing = config.timing,
                    customStart = drawAnchorFor(config, side.name, side.name),
                    honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                    warnings = { drawWarnings.addAll(it) },
                )
                strokes.drawCustomContours(
                    drawScope = this,
                    contours = custom.strokeRevealContours ?: contours,
                    result = strokeResult,
                    alpha = strokeAlpha,
                    strokeWidth = width,
                    side = side,
                    reveal = custom.strokeRevealContours == null,
                    drawCfg = drawCfg,
                    drawProgress = drawP,
                    customStart = drawAnchorFor(config, side.name, side.name),
                    timing = config.timing,
                    honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                    anchorCollector = if (collectDrawAnchors)
                        debugAnchorSink(transform, drawCfg?.direction ?: DrawDirection.AUTO) else null,
                    warnings = { drawWarnings.addAll(it) },
                )
            }
            val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
            val clipDirection = props.clipReveal?.direction
                ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            clips.withClipReveal(this, side.clipContours, clipProgress, clipDirection, contents)
        }
    }

    /**
     * Draws a fully matched path pair: resolves its per-frame paints, alphas, stroke width,
     * reveal tracks and clip masks, then hands the pair to the fill and stroke passes inside the
     * pair's own transform. What renders is interpolated paint, interpolated stroke width, the
     * two complementary fill legs (each side's complete contour set so fill-rule holes survive),
     * and the per-contour stroke reveal.
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
        val clipContours = clips.contoursFor(pair, f, t, morphT)
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
            fills.drawMorph(
                drawScope = this,
                pair = pair,
                f = f,
                t = t,
                morphT = morphT,
                result = fillResult,
                alpha = fillAlpha,
                strokeWidth = strokeWidth,
                props = props,
                fillMode = fillCfg?.fillMode ?: config.defaultFillMode,
                fillDir = fillCfg?.fillDirection ?: config.defaultFillDirection,
                drawProgress = drawProgress,
                fillProgress = fillProgress,
                clipContours = clipContours,
                clipProgress = clipProgress,
                clipDirection = clipDirection,
                bMinX = bMinX, bMinY = bMinY, bMaxX = bMaxX, bMaxY = bMaxY,
                fromExtras = fromExtras.getOrNull(pair.index).orEmpty(),
                toExtras = toExtras.getOrNull(pair.index).orEmpty(),
                timing = config.timing,
                customStart = drawAnchorFor(config, f.name, t.name),
                honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                warnings = { drawWarnings.addAll(it) },
            )

            // ---- stroke pass (with reveal / pen effect)
            val strokeWidthScale = if (pair.strokeFillMorph && f.fill != null && t.fill != null && f.stroke != null && t.stroke != null) {
                (1f - morphT).coerceAtLeast(0f)
            } else {
                null
            }
            strokes.drawMorph(
                drawScope = this,
                pair = pair,
                f = f,
                t = t,
                morphT = morphT,
                result = strokeResult,
                alpha = strokeAlpha,
                strokeWidth = strokeWidth,
                strokeWidthScale = strokeWidthScale,
                props = props,
                drawCfg = drawCfg,
                clipContours = clipContours,
                clipProgress = clipProgress,
                clipDirection = clipDirection,
                bMinX = bMinX, bMinY = bMinY, bMaxX = bMaxX, bMaxY = bMaxY,
                drawProgress = drawProgress,
                customStart = drawAnchorFor(config, f.name, t.name),
                timing = config.timing,
                honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                anchorCollector = if (collectDrawAnchors) debugAnchorSink(transform, drawCfg?.direction ?: DrawDirection.AUTO) else null,
                warnings = { drawWarnings.addAll(it) },
            )
        }
    }

    // ------------------------------------------------------------------ painting helpers

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
                clips.withClipReveal(this, side.clipContours, clipProgress, clipDirection) {
                    drawStaticContents(side, fillResult, strokeResult, alpha, fillAlphaOverride, strokeAlphaOverride, strokeWidthOverride)
                }
            }
        }
    }

    /** Fill + stroke of a static side; each pass owns its own paint sequence. */
    private fun DrawScope.drawStaticContents(
        side: PreparedPath,
        fillResult: GradientInterpolator.Result,
        strokeResult: GradientInterpolator.Result,
        alpha: Float,
        fillAlphaOverride: Float = 1f,
        strokeAlphaOverride: Float = 1f,
        strokeWidthOverride: Float? = null,
    ) {
        fills.paintStaticSide(this, side, fillResult, alpha, fillAlphaOverride)
        strokes.paintStaticSide(this, side, strokeResult, alpha, strokeWidthOverride, strokeAlphaOverride)
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
            // ---- fill pass
            fills.drawMissingSide(
                drawScope = this,
                side = side,
                result = fillResult,
                alpha = pathAlpha * fillAlphaOverride,
                fillProgress = fillProgress,
                fillMode = fillConfig?.fillMode ?: config.defaultFillMode,
                fillDir = fillConfig?.fillDirection ?: config.defaultFillDirection,
                clipProgress = clipProgress,
                clipDirection = clipDirection,
                props = props,
                timing = config.timing,
                customStart = drawAnchorFor(config, side.name, side.name),
                honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                warnings = { drawWarnings.addAll(it) },
            )
            // ---- stroke pass (with reveal / pen effect)
            strokes.drawMissingSide(
                drawScope = this,
                side = side,
                result = strokeResultWithAnimation,
                alpha = pathAlpha * strokeAlphaOverride,
                drawProgress = drawProgress,
                drawCfg = drawConfig,
                clipProgress = clipProgress,
                clipDirection = clipDirection,
                customStart = drawAnchorFor(config, side.name, side.name),
                timing = config.timing,
                honorDirection = config.directionTraversal == DirectionTraversal.HONOR_DIRECTION,
                anchorCollector = if (collectDrawAnchors)
                    debugAnchorSink(transform, drawConfig?.direction ?: DrawDirection.AUTO) else null,
                warnings = { drawWarnings.addAll(it) },
            )
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