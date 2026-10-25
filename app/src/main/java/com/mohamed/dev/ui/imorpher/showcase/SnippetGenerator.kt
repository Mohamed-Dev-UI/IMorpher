package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphDebugOptions
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.imorpher.vectormorph.core.model.FallbackStrategy
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the live playground state into a paste-ready Kotlin snippet: every adjustable control
 * maps to one explicit argument so copied code matches what is on screen. Arguments equal to
 * their default are omitted, so the pasted code stays minimal and expresses exactly the
 * non-default state the playground shows.
 */
object SnippetGenerator {

    /** Which composable a playground drives. */
    enum class Target { MORPH_ICON, MORPH_VECTOR, VECTOR_ANIMATOR, DRAW_ICON }

    fun generate(state: ShowcaseState, target: Target): String {
        val lines = ArrayList<String>()
        // Icon names are plain strings in state; pasted code needs the real accessor
        // (`IMorpherIcons.ArrowUp1`), otherwise the snippet does not compile.
        val iconFrom = "IMorpherIcons.${state.fromIconName}"
        val iconTo = "IMorpherIcons.${state.toIconName}"
        val tintLines = tintLines(state)
        val alignmentLine = alignmentLine(state)

        when (target) {
            Target.MORPH_ICON -> {
                lines += "MorphIcon("
                lines += "from = $iconFrom,"
                lines += "to = $iconTo,"
                lines += "selected = selected,"
                // tintFrom/tintTo have no defaults on MorphIcon: always spell them out so the
                // copied code compiles even when the playground keeps tint switched off.
                if (tintLines.isEmpty()) {
                    lines += "tintFrom = ${colorArg(state.primary)},"
                    lines += "tintTo = ${colorArg(state.secondary)},"
                } else {
                    lines += tintLines
                }
                animationSpecLine(state)?.let { lines += it }
                if (state.motionPreference != MotionPreference.FULL) {
                    lines += "motionPreference = MotionPreference.${state.motionPreference.name},"
                }
                configurationLine(state)?.let { lines += "configuration = $it," }
                sizeLines(state).forEach { lines += it }
                alignmentLine?.let { lines += it }
                lines += "contentDescription = \"…\","
                lines += ")"
            }
            Target.MORPH_VECTOR -> {
                lines += "MorphVector("
                lines += "from = $iconFrom,"
                lines += "to = $iconTo,"
                lines += "progress = progress,"
                lines += tintLines
                configurationLine(state)?.let { lines += "configuration = $it," }
                sizeLines(state).forEach { lines += it }
                alignmentLine?.let { lines += it }
                lines += "contentDescription = \"…\","
                lines += ")"
            }
            Target.VECTOR_ANIMATOR -> {
                lines += "val animation = VectorAnimation {"
                lines += "    at(0f) { scale = ${f(state.startScale)}; rotation = ${f(state.startRotation)} }"
                lines += "    at(1f) { scale = ${f(state.endScale)}; rotation = ${f(state.endRotation)} }"
                appendRevealBlock(lines, state, intervalLine = "            interval = 0f..0.75f,")
                if (state.colorEnabled) {
                    lines += "    allPaths {"
                    lines += "        // Both channels: filled paths recolor their fill, stroke-only paths their stroke."
                    lines += "        color(from = ${colorArg(state.primary)}, to = ${colorArg(state.secondary)}, interval = 0.2f..0.95f)"
                    lines += "        strokeColor(from = ${colorArg(state.primary)}, to = ${colorArg(state.secondary)}, interval = 0.2f..0.95f)"
                    lines += "    }"
                }
                if (state.staggerDelay > 0f) {
                    lines += "    stagger(${f(state.staggerDelay)}${staggerOrderSuffix(state)})"
                }
                lines += "}"
                lines += "VectorAnimator("
                lines += "animation = VectorAnimationDefinition($iconFrom, animation${definitionConfigurationSuffix(state)}),"
                lines += "progress = progress,"
                lines += tintLines
                sizeLines(state).forEach { lines += it }
                alignmentLine?.let { lines += it }
                lines += ")"
            }
            Target.DRAW_ICON -> {
                lines += "val animation = VectorAnimation {"
                lines += "    allPaths {"
                lines += "        strokeReveal("
                lines += "            interval = 0f..1f,"
                drawRevealArgumentLines(state).forEach { lines += "            $it" }
                lines += "        )"
                lines += "    }"
                if (state.staggerDelay > 0f) {
                    lines += "    stagger(${f(state.staggerDelay)}${staggerOrderSuffix(state)})"
                }
                lines += "}"
                lines += "DrawIcon("
                lines += "vector = $iconFrom,"
                lines += "animation = animation,"
                animationSpecLine(state)?.let { lines += it }
                lines += tintLines
                configurationLine(state)?.let { lines += "configuration = $it," }
                sizeLines(state).forEach { lines += it }
                alignmentLine?.let { lines += it }
                lines += ")"
            }
        }
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ configuration arg

    /**
     * `MorphConfiguration(...)` argument list spelling every non-default field, or null when
     * the whole configuration is at its default (the caller's default value is then enough).
     */
    private fun configurationLine(state: ShowcaseState): String? = state.configurationArgs().takeIf { it.isNotEmpty() }?.let { args ->
        if (args.size == 1) "MorphConfiguration(${args[0]})" else "MorphConfiguration(\n    ${args.joinToString(",\n    ")},\n)"
    }

    /** One `name = value` string per [MorphConfiguration] field that differs from its default. */
    private fun ShowcaseState.configurationArgs(): List<String> {
        val args = ArrayList<String>()
        if (pathMatching != PathMatchingStrategy.AUTO) args += "pathMatching = PathMatchingStrategy.${pathMatching.name}"
        if (missingPath != MissingPathBehavior.FADE) args += "missingPath = MissingPathBehavior.${missingPath.name}"
        if (fallback != FallbackStrategy.AUTO) args += "fallback = FallbackStrategy.${fallback.name}"
        if (timing != TimingMode.BY_PATH_LENGTH) args += "timing = TimingMode.${timing.name}"
        if (colorSpace != ColorInterpolationSpace.SRGB) args += "colorSpace = ColorInterpolationSpace.${colorSpace.name}"
        if (keepRenderOrder != true) args += "keepRenderOrder = false"
        if (startPoint != StartPointStrategy.AUTO) args += "startPoint = StartPointStrategy.${startPoint.name}"
        if (pathDirection != PathDirectionStrategy.AUTO) args += "pathDirection = PathDirectionStrategy.${pathDirection.name}"
        if (directionTraversal != DirectionTraversal.HONOR_DIRECTION) {
            args += "directionTraversal = DirectionTraversal.${directionTraversal.name}"
        }
        if (drawOrder != DrawOrderStrategy.AUTO) args += "drawOrder = DrawOrderStrategy.${drawOrder.name}"
        if (customDrawOrderNames.isNotEmpty()) {
            args += "customDrawOrder = listOf(${customDrawOrderNames.joinToString(", ") { "\"$it\"" }})"
        }
        if (strokeFill != StrokeFillStrategy.AUTO) args += "strokeFill = StrokeFillStrategy.${strokeFill.name}"
        if (staggerDelay != 0f) args += "staggerDelay = ${f(staggerDelay)}"
        if (staggerOrder != DrawOrderStrategy.AUTO) args += "staggerOrder = DrawOrderStrategy.${staggerOrder.name}"
        if (customMappings.isNotEmpty()) {
            args += "customMappings = listOf(${customMappings.joinToString(", ") {
                "PathMapping(source = \"${it.source}\", target = \"${it.target}\")"
            }})"
        }
        if (customAnchors.isNotEmpty()) {
            args += "customDrawAnchors = mapOf(${customAnchors.joinToString(", ") {
                "\"${it.pathName}\" to ${f(it.fraction)}"
            }})"
        }
        if (fillMode != FillMode.DIRECTIONAL) args += "defaultFillMode = FillMode.${fillMode.name}"
        if (defaultFillDirection != DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT) {
            args += "defaultFillDirection = DrawDirection.${defaultFillDirection.name}"
        }
        toDebugOptions()?.let { args += debugArg(it) }
        segmentBudget?.let { args += "segmentBudget = $it" }
        if (abs(simplificationTolerance - 0.25f) > 1e-6f) args += "simplificationTolerance = ${f(simplificationTolerance)}"
        if (viewportNormalization != ViewportNormalization.STRETCH) {
            args += "viewportNormalization = ViewportNormalization.${viewportNormalization.name}"
        }
        return args
    }

    /**
     * Single-line `debug = MorphDebugOptions(showPathPoints = true, …)` — the flags are all
     * booleans on a narrow type, so one line stays readable inside the argument list.
     */
    private fun debugArg(options: MorphDebugOptions): String {
        val bits = ArrayList<String>()
        if (options.showPathPoints) bits += "showPathPoints = true"
        if (options.showPathNumbers) bits += "showPathNumbers = true"
        if (options.showBoundingBoxes) bits += "showBoundingBoxes = true"
        if (options.showCentroids) bits += "showCentroids = true"
        if (options.showDirection) bits += "showDirection = true"
        if (options.showStartPoints) bits += "showStartPoints = true"
        if (options.showPathMatching) bits += "showPathMatching = true"
        if (options.markerArcFraction != 0f) bits += "markerArcFraction = ${f(options.markerArcFraction)}"
        return "debug = MorphDebugOptions(${bits.joinToString(", ")})"
    }

    // ------------------------------------------------------------------ composable-level args

    /**
     * `animationSpec` line for the two APIs that own one ([Target.MORPH_ICON],
     * [Target.DRAW_ICON]) — null at both defaults (tween 400), so pasted code inherits the
     * documented default instead of restating it.
     */
    private fun animationSpecLine(state: ShowcaseState): String? {
        val duration = state.durationMillis.roundToInt()
        return when (state.specKind) {
            AnimationSpecKind.TWEEN ->
                if (duration == 400) null else "animationSpec = tween($duration),"
            AnimationSpecKind.LINEAR -> "animationSpec = tween($duration, easing = LinearEasing),"
            AnimationSpecKind.SPRING_MEDIUM_BOUNCY -> "animationSpec = spring(dampingRatio = 0.5f, stiffness = 30f),"
            AnimationSpecKind.SPRING_NO_BOUNCY -> "animationSpec = spring(dampingRatio = 1f, stiffness = 400f),"
        }
    }

    /** `width`/`height` lines when the playground overrides the icon's intrinsic size. */
    private fun sizeLines(state: ShowcaseState): List<String> = buildList {
        state.iconWidthDp?.let { add("width = ${it.roundToInt()}.dp,") }
        state.iconHeightDp?.let { add("height = ${it.roundToInt()}.dp,") }
    }

    /** `alignment = Alignment.…` line for every placement other than the default center. */
    private fun alignmentLine(state: ShowcaseState): String? = when (state.alignment) {
        AlignmentKind.CENTER -> null
        AlignmentKind.CENTER_START -> "alignment = Alignment.CenterStart,"
        AlignmentKind.CENTER_END -> "alignment = Alignment.CenterEnd,"
        AlignmentKind.TOP_CENTER -> "alignment = Alignment.TopCenter,"
        AlignmentKind.BOTTOM_CENTER -> "alignment = Alignment.BottomCenter,"
    }

    /** Tint arguments, omitted entirely when the playground keeps tint off. */
    private fun tintLines(state: ShowcaseState): List<String> = when (state.tintMode) {
        TintMode.NONE -> emptyList()
        TintMode.SOLID -> listOf(
            "tintFrom = ${colorArg(state.primary)},",
            "tintTo = ${colorArg(state.secondary)},",
        )
        TintMode.GRADIENT -> listOf(
            "tintFrom = Brush.linearGradient(",
            "    colors = listOf(${colorArg(state.primary)}, ${colorArg(state.secondary)}),",
            "),",
            "tintTo = Brush.linearGradient(",
            "    colors = listOf(${colorArg(state.accent)}, ${colorArg(state.secondary)}),",
            "),",
        )
    }

    private fun colorArg(color: Color): String = "Color(0x${hex(color)})"

    /** Configuration suffix for `VectorAnimationDefinition(vector, animation, configuration = …)`. */
    private fun definitionConfigurationSuffix(state: ShowcaseState): String =
        configurationLine(state)?.let { ", configuration = $it" } ?: ""

    // ------------------------------------------------------------------ reveal DSL args

    /** Stroke-reveal arguments for the VectorAnimator playground's reveal block. */
    private fun appendRevealBlock(lines: ArrayList<String>, state: ShowcaseState, intervalLine: String) {
        if (!state.revealEnabled) return
        lines += "    allPaths {"
        lines += "        strokeReveal("
        lines += intervalLine
        drawRevealArgumentLines(state).forEach { lines += "        $it" }
        lines += "        )"
        lines += "    }"
    }

    /** Per-argument reveal lines; defaults are omitted (the API defaults match the playground's). */
    private fun drawRevealArgumentLines(state: ShowcaseState): List<String> = buildList {
        if (state.pathStart != PathStart.AUTO) add("start = PathStart.${state.pathStart.name},")
        if (state.drawDirection != DrawDirection.AUTO) add("direction = DrawDirection.${state.drawDirection.name},")
        if (state.drawMode != DrawMode.FORWARD) add("mode = DrawMode.${state.drawMode.name},")
    }

    /** Draw-order suffix for the `stagger(delay, order)` DSL call, omitted at the default. */
    private fun staggerOrderSuffix(state: ShowcaseState): String =
        if (state.staggerOrder == DrawOrderStrategy.AUTO) "" else ", DrawOrderStrategy.${state.staggerOrder.name}"

    // ------------------------------------------------------------------ formatting

    private fun hex(color: Color): String {
        val argb = (color.alpha * 255f).toInt().shl(24) or
            (color.red * 255f).toInt().shl(16) or
            (color.green * 255f).toInt().shl(8) or
            (color.blue * 255f).toInt()
        return "%08X".format(argb)
    }

    private fun f(value: Float): String =
        if (value == value.roundToInt().toFloat()) "${value.roundToInt()}f" else "${value}f"
}
