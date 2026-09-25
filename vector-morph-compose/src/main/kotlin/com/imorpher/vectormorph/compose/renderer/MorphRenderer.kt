package com.imorpher.vectormorph.compose.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
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

    private var coords = FloatArray(512)

    // scratch paths (never escape this class)
    private val fillPath = Path()
    private val strokePath = Path()
    private val maskPath = Path()
    private val wedgePath = Path()
    private val radialPath = Path()
    private val measure = PathMeasure()

    // per-contour rebuilt-from-scratch caches keyed by pairIndex*contour slot
    private val contourPathCache = HashMap<Long, Path>()
    private val staticContourCache = HashMap<ContourData, Path>()
    private val reversedContourCache = HashMap<ContourData, ContourData>()
    private var scratchPlan: MorphPlan? = null
    private var pairGroupNames: Array<List<String>> = emptyArray()
    private var mergedPathProps: Array<com.imorpher.vectormorph.core.animation.PathFrameValues> = emptyArray()
    private var cachedRenderOrderPlan: MorphPlan? = null
    private var cachedRenderOrderStrategy = configOrderDefault()
    private var cachedRenderOrderCustom: List<String> = emptyList()
    private var cachedRenderOrderKeep = true
    private var cachedRenderOrder: IntArray = IntArray(0)

    /** Draws one frame. */
    fun render(
        drawScope: DrawScope,
        plan: MorphPlan,
        frame: EvaluatedFrame,
        progress: Float,
        config: MorphConfiguration,
    ) {
        val p = progress.coerceIn(0f, 1f)
        ensurePlanScratch(plan)

        val vw = if (plan.from.viewportWidth > 0f) plan.from.viewportWidth else 24f
        val vh = if (plan.from.viewportHeight > 0f) plan.from.viewportHeight else 24f

        val scaleX = drawScope.size.width / vw
        val scaleY = drawScope.size.height / vh
        val uniform = min(scaleX, scaleY)
        val dx = (drawScope.size.width - vw * uniform) / 2f
        val dy = (drawScope.size.height - vh * uniform) / 2f

        drawScope.withTransform({
            translate(dx, dy)
            scale(uniform, uniform, pivot = Offset.Zero)
        }) {
            val vAlpha = frame.vectorProps[PropKey.ALPHA.ordinal].takeUnless { it.isNaN() } ?: 1f
            applyVectorTransform(frame, vw, vh) {
                val order = renderOrder(plan, config)
                for (pairIndex in order) {
                    drawPair(plan.pairs[pairIndex], plan, frame, p, config, vAlpha)
                }
                config.debug?.let { drawDebugOverlay(plan, it) }
            }
        }
    }

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

    private fun ensurePlanScratch(plan: MorphPlan) {
        if (scratchPlan === plan) return
        scratchPlan = plan
        pairGroupNames = Array(plan.pairs.size) { index ->
            val pair = plan.pairs[index]
            (pair.fromPath?.groupNames.orEmpty() + pair.toPath?.groupNames.orEmpty()).distinct()
        }
        mergedPathProps = Array(plan.pairs.size) { com.imorpher.vectormorph.core.animation.PathFrameValues() }
        contourPathCache.clear()
    }

    /** Development-only geometry overlays. Coordinates are already in viewport space here. */
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
            if (into.drawConfigs[key.ordinal] == null) into.drawConfigs[key.ordinal] = group.drawConfigs[key.ordinal]
        }
        if (!hasPathFill && group.brushMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) {
            into.brushMode = group.brushMode; into.primaryBrush = group.primaryBrush
            into.secondaryBrush = group.secondaryBrush; into.brushProgress = group.brushProgress
        }
        if (!hasPathStroke && group.strokeBrushMode != com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_NONE) {
            into.strokeBrushMode = group.strokeBrushMode; into.primaryStrokeBrush = group.primaryStrokeBrush
            into.secondaryStrokeBrush = group.secondaryStrokeBrush; into.strokeBrushProgress = group.strokeBrushProgress
        }
    }

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

        val customDraw = props.drawConfigs[PropKey.DRAW_PROGRESS.ordinal]
            ?: config.animation?.drawConfigFor(
                pair.fromPath?.name, pair.toPath?.name, pair.fromPath?.index ?: -1,
                pair.toPath?.index ?: -1, PropKey.DRAW_PROGRESS,
                pair.fromPath?.groupNames.orEmpty(), pair.toPath?.groupNames.orEmpty(),
            )
        val customFill = props.drawConfigs[PropKey.FILL_PROGRESS.ordinal]
            ?: config.animation?.drawConfigFor(
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

    private fun multiplyOverride(existing: Float, multiplier: Float): Float =
        (if (existing.isNaN()) 1f else existing) * multiplier

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
        val width = custom.strokeWidth ?: side.strokeWidth
        val contours = custom.contours.orEmpty()
        val drawP = props.props[PropKey.DRAW_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: progress
        val fillP = props.props[PropKey.FILL_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: progress
        val drawCfg = props.drawConfigs[PropKey.DRAW_PROGRESS.ordinal]
        val fillCfg = props.drawConfigs[PropKey.FILL_PROGRESS.ordinal]
        val transform = PairTransform.from(props, side, side, 0f)
        withPairTransform(transform) {
            val contents: DrawScope.() -> Unit = {
                val fillContours = custom.fillContours ?: contours
                val fillBox = customBounds(fillContours)
                for (contour in fillContours) {
                    val path = staticPath(contour).apply { fillType = side.pathFillType }
                    if (custom.fillContours != null) paintFillDirect(path, fillResult, fillAlpha)
                    else paintFillWithReveal(
                            path, fillResult, fillAlpha, fillP,
                            fillCfg?.fillMode ?: config.defaultFillMode,
                            fillCfg?.fillDirection ?: config.defaultFillDirection,
                            fillBox[0], fillBox[1], fillBox[2], fillBox[3], morph, width, side, props,
                        )
                }
                val strokeContours = custom.strokeRevealContours ?: contours
                for (contour in strokeContours) {
                    val path = staticPath(contour)
                    val shown = if (custom.strokeRevealContours != null) path else buildRevealedStrokePath(
                        path, drawP, drawCfg?.mode ?: DrawMode.FORWARD, drawCfg?.start ?: PathStart.START,
                        drawCfg?.direction ?: DrawDirection.AUTO, null, config.customStartOffsets[side.name] ?: 0f,
                        config.timing,
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
            val clipDirection = props.drawConfigs[PropKey.CLIP_PROGRESS.ordinal]?.direction
                ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            withClipReveal(side.clipContours, clipProgress, clipDirection, contents)
        }
    }

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

    /** Applies inherited group clips and optionally reveals the clip mask from one edge. */
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
        val drawCfg = props.drawConfigs[PropKey.DRAW_PROGRESS.ordinal]
            ?: config.animation?.takeIf { drawProgress != null }?.drawConfigFor(
                f.name, t.name, f.index, t.index, PropKey.DRAW_PROGRESS, f.groupNames, t.groupNames,
            )
        val clipCfg = props.drawConfigs[PropKey.CLIP_PROGRESS.ordinal]
        val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
        val clipDirection = clipCfg?.direction ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
        // Group clips live in viewport space; use the active endpoint while its contents morph.
        val clipContours = if (morphT < 0.5f) f.clipContours else t.clipContours
        val fillCfg = props.drawConfigs[PropKey.FILL_PROGRESS.ordinal]
            ?: config.animation?.takeIf { fillProgress != null }?.drawConfigFor(
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
                val effectiveFill = fillProgress
                    ?: if (pair.strokeFillDraw) ((morphT - 0.45f) / 0.55f).coerceIn(0f, 1f) else 1f
                val fillMode = fillCfg?.fillMode ?: drawCfg?.fillMode ?: config.defaultFillMode
                val fillDir = fillCfg?.fillDirection ?: drawCfg?.fillDirection ?: config.defaultFillDirection

                val customFillContours = props.customOverrides?.fillContours
                if (customFillContours != null) {
                    val customBox = customBounds(customFillContours)
                    customFillContours.forEach { contour ->
                        withClipReveal(clipContours, clipProgress, clipDirection) {
                            if (props.customOverrides?.fillContours != null) {
                                paintFillDirect(staticPath(contour), fillResult, fillAlpha)
                            } else paintFillWithReveal(
                                staticPath(contour), fillResult, fillAlpha, effectiveFill, fillMode, fillDir,
                                customBox[0], customBox[1], customBox[2], customBox[3], morphT,
                                strokeWidth, t, props,
                            )
                        }
                    }
                } else for ((ci, cp) in pair.contourPairs.withIndex()) {
                    val path = morphContourPath(pair.index, ci, cp, morphT)
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        paintFillWithReveal(
                            path, fillResult, fillAlpha, effectiveFill, fillMode, fillDir,
                            bMinX, bMinY, bMaxX, bMaxY, morphT,
                            strokeWidth, t, props,
                        )
                    }
                }
                // contour-count asymmetries
                if (customFillContours == null) pair.fromOnlyContours.forEach { c ->
                    val path = staticPath(c)
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        paintFillSimple(path, fillResult, fillAlpha * (1f - morphT), effectiveFill, t.pathFillType)
                    }
                }
                if (customFillContours == null) pair.toOnlyContours.forEach { c ->
                    val path = staticPath(c)
                    withClipReveal(clipContours, clipProgress, clipDirection) {
                        paintFillSimple(path, fillResult, fillAlpha * morphT, effectiveFill, t.pathFillType)
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
                        val start = drawCfg?.start ?: PathStart.START
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
                            val customStart = config.customStartOffsets[f.name]
                                ?: config.customStartOffsets[t.name]
                                ?: 0f
                            val revealed = buildRevealedStrokePath(
                                strokeGeometry, drawProgress, mode, start, direction, cp, customStart,
                                config.timing, ci, pair.contourPairs.size, max(f.totalLength, t.totalLength),
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
                // very wide brush in the fill color
                val strokeDraw = props.drawConfigs[PropKey.DRAW_PROGRESS.ordinal]
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
                        buildRevealedStrokePath(
                            path, prog, drawMode,
                            strokeDraw?.start ?: PathStart.START,
                            drawDirection,
                            null,
                        )
                    )
                }
            }
        }
    }

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
     * using arc-length measurement — constant visual speed, exact geometry.
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
            source, measure, len, start, direction, customStartFraction,
        )
        val s = startFrac * len

        when (mode) {
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
                        measure.getSegment(0f, end - len, strokePath, false)
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
                    val opened = measure.getSegment(0f, s, strokePath, true)
                    if (opened) {
                        measure.getSegment(len + begin, len, strokePath, false)
                    } else {
                        measure.getSegment(len + begin, len, strokePath, true)
                    }
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
                        if (hi > 1e-3f) measure.getSegment(0f, hi, strokePath, !opened)
                    }
                    else -> {
                        // wraps the contour end: [lo..len] then [0..hi-len], joined at the wrap
                        val opened = if (lo >= len - 1e-3f) false else
                            measure.getSegment(lo, len, strokePath, true)
                        if (hi - len > 1e-3f) measure.getSegment(0f, hi - len, strokePath, !opened)
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

    /** Resolves where the "pen" starts: the arc fraction nearest the requested anchor. */
    private fun resolveStartFraction(
        source: Path,
        measure: PathMeasure,
        len: Float,
        start: PathStart,
        direction: DrawDirection,
        customStartFraction: Float,
    ): Float {
        if (start == PathStart.CUSTOM) return customStartFraction.coerceIn(0f, 1f)
        val resolvedStart = if (start != PathStart.START) start else when (direction) {
            DrawDirection.AUTO -> PathStart.TOP_LEFT
            DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT -> PathStart.TOP_RIGHT
            DrawDirection.BOTTOM_LEFT_TO_TOP_RIGHT -> PathStart.BOTTOM_LEFT
            DrawDirection.BOTTOM_RIGHT_TO_TOP_LEFT -> PathStart.BOTTOM_RIGHT
            DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT -> PathStart.TOP_LEFT
            DrawDirection.RIGHT_TO_LEFT -> PathStart.RIGHT
            DrawDirection.LEFT_TO_RIGHT -> PathStart.LEFT
            DrawDirection.TOP_TO_BOTTOM -> PathStart.TOP
            DrawDirection.BOTTOM_TO_TOP -> PathStart.BOTTOM
            DrawDirection.CLOCKWISE, DrawDirection.COUNTER_CLOCKWISE -> PathStart.TOP
            else -> PathStart.START
        }
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
        val transform = PairTransform.from(props, side, side, 0f)
        withPairTransform(transform) {
            val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
            val clipDirection = props.drawConfigs[PropKey.CLIP_PROGRESS.ordinal]?.direction
                ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
            if (side.clipContours.isEmpty()) {
                drawStaticContents(side, fillResult, strokeResult, alpha)
            } else {
                withClipReveal(side.clipContours, clipProgress, clipDirection) {
                    drawStaticContents(side, fillResult, strokeResult, alpha)
                }
            }
        }
    }

    private fun DrawScope.drawStaticContents(
        side: PreparedPath,
        fillResult: GradientInterpolator.Result,
        strokeResult: GradientInterpolator.Result,
        alpha: Float,
    ) {
        if (fillResult !is GradientInterpolator.Result.None && side.fillAlpha > 0f) {
            val fillAlpha = alpha * side.fillAlpha
            side.contours.forEach {
                val path = staticPath(it)
                path.fillType = side.pathFillType
                paintFillDirect(path, fillResult, fillAlpha)
            }
        }
        if (strokeResult !is GradientInterpolator.Result.None && side.strokeAlpha > 0f && side.strokeWidth > 0f) {
            val strokeAlpha = alpha * side.strokeAlpha
            val style = Stroke(
                width = side.strokeWidth,
                cap = side.strokeLineCap,
                join = side.strokeLineJoin,
                miter = side.strokeLineMiter,
            )
            side.contours.forEach { paintStroke(staticPath(it), strokeResult, strokeAlpha, style) }
        }
    }

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
        val drawConfig = props.drawConfigs[PropKey.DRAW_PROGRESS.ordinal] ?: config.animation?.drawConfigFor(
            null, side.name, -1, side.index, PropKey.DRAW_PROGRESS, emptyList(), side.groupNames,
        )
        val fillConfig = props.drawConfigs[PropKey.FILL_PROGRESS.ordinal] ?: config.animation?.drawConfigFor(
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
        val clipProgress = props.props[PropKey.CLIP_PROGRESS.ordinal].takeUnless { it.isNaN() } ?: 1f
        val clipDirection = props.drawConfigs[PropKey.CLIP_PROGRESS.ordinal]?.direction
            ?: DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT
        val transform = PairTransform.from(props, side, side, 0f)
        withPairTransform(transform) {
            side.contours.forEachIndexed { contourIndex, contour ->
                val path = staticPath(contour)
                path.fillType = side.pathFillType
                if (fillResult !is GradientInterpolator.Result.None) {
                    withClipReveal(side.clipContours, clipProgress, clipDirection) {
                        paintFillWithReveal(
                            path,
                            fillResult,
                            pathAlpha * side.fillAlpha,
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
                                paintStroke(path, strokeResultWithAnimation, pathAlpha * side.strokeAlpha, style)
                            }
                        }
                    } else {
                        val revealed = buildRevealedStrokePath(
                            strokeSource,
                            drawProgress,
                            strokeMode,
                            drawConfig?.start ?: PathStart.START,
                            strokeDirection,
                            null,
                            config.customStartOffsets[side.name] ?: 0f,
                            config.timing,
                            contourIndex,
                            side.contours.size,
                            side.totalLength,
                            side.arcLengths.getOrNull(contourIndex),
                        )
                        withClipReveal(side.clipContours, clipProgress, clipDirection) {
                            paintStroke(revealed, strokeResultWithAnimation, pathAlpha * side.strokeAlpha, style)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ path management

    /** Rebuilds the interpolated contour path; cached per (pair, contour) per frame. */
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
        if (cp.from.closed) path.close()
        return path
    }

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

    private fun reversedContour(contour: ContourData): ContourData =
        reversedContourCache.getOrPut(contour) { contour.reversed() }

    private fun shouldReverseWinding(windingSign: Int, direction: DrawDirection): Boolean = when (direction) {
        DrawDirection.CLOCKWISE -> windingSign < 0
        DrawDirection.COUNTER_CLOCKWISE -> windingSign > 0
        else -> false
    }

    private fun ensureCapacity(buffer: FloatArray, needed: Int): FloatArray =
        if (buffer.size >= needed) buffer else FloatArray(needed * 2)

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

    private class PairTransform(
        val sx: Float, val sy: Float, val rotation: Float,
        val tx: Float, val ty: Float, val pivotX: Float, val pivotY: Float,
    ) {
        val isIdentity: Boolean
            get() = sx == 1f && sy == 1f && rotation == 0f && tx == 0f && ty == 0f

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

}
