package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Turns the live playground state into a paste-ready Kotlin snippet: every adjustable control
 * maps to one explicit argument so copied code matches what is on screen.
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
        val duration = state.durationMillis.roundToInt()

        val configuration = buildString {
            append("MorphConfiguration(")
            append("pathMatching = PathMatchingStrategy.${state.pathMatching.name}, ")
            append("missingPath = MissingPathBehavior.${state.missingPath.name}, ")
            append("fallback = FallbackStrategy.${state.fallback.name}, ")
            append("timing = TimingMode.${state.timing.name}, ")
            append("colorSpace = ColorInterpolationSpace.${state.colorSpace.name}")
            append(")")
        }

        val tintLines = when (state.tintMode) {
            TintMode.NONE -> emptyList()
            TintMode.SOLID -> listOf(
                "tintFrom = Color(0x${hex(state.primary)}),",
                "tintTo = Color(0x${hex(state.secondary)}),",
            )
            TintMode.GRADIENT -> listOf(
                "tintFrom = Brush.linearGradient(",
                "    colors = listOf(Color(0x${hex(state.primary)}), Color(0x${hex(state.secondary)})),",
                "),",
                "tintTo = Brush.linearGradient(",
                "    colors = listOf(Color(0x${hex(state.accent)}), Color(0x${hex(state.secondary)})),",
                "),",
            )
        }

        when (target) {
            Target.MORPH_ICON -> {
                lines += "MorphIcon("
                lines += "from = IMorpherIcons.${state.fromIconName},"
                lines += "to = IMorpherIcons.${state.toIconName},"
                lines += "selected = selected,"
                lines += tintLines
                lines += "animationSpec = tween($duration),"
                lines += "motionPreference = MotionPreference.${state.motionPreference.name},"
                lines += "configuration = $configuration,"
                lines += "contentDescription = \"…\","
                lines += ")"
            }
            Target.MORPH_VECTOR -> {
                lines += "MorphVector("
                lines += "from = $iconFrom,"
                lines += "to = $iconTo,"
                lines += "progress = progress,"
                lines += tintLines
                lines += "configuration = $configuration,"
                lines += "contentDescription = \"…\","
                lines += ")"
            }
            Target.VECTOR_ANIMATOR -> {
                lines += "val animation = VectorAnimation {"
                lines += "    at(0f) { scale = ${f(state.startScale)}; rotation = ${f(state.startRotation)} }"
                lines += "    at(1f) { scale = ${f(state.endScale)}; rotation = ${f(state.endRotation)} }"
                if (state.revealEnabled) {
                    lines += "    allPaths {"
                    lines += "        strokeReveal("
                    lines += "            start = PathStart.${state.pathStart.name},"
                    lines += "            direction = DrawDirection.${state.drawDirection.name},"
                    lines += "            interval = 0f..0.75f,"
                    lines += "            mode = DrawMode.${state.drawMode.name},"
                    lines += "        )"
                    lines += "    }"
                }
                if (state.colorEnabled) {
                    lines += "    allPaths {"
                    lines += "        // Both channels: filled paths recolor their fill, stroke-only paths their stroke."
                    lines += "        color(from = Color(0x${hex(state.primary)}), to = Color(0x${hex(state.secondary)}), interval = 0.2f..0.95f)"
                    lines += "        strokeColor(from = Color(0x${hex(state.primary)}), to = Color(0x${hex(state.secondary)}), interval = 0.2f..0.95f)"
                    lines += "    }"
                }
                if (state.staggerDelay > 0f) {
                    lines += "    stagger(${f(state.staggerDelay)}, DrawOrderStrategy.${state.staggerOrder.name})"
                }
                lines += "}"
                lines += "VectorAnimator("
                lines += "animation = VectorAnimationDefinition($iconFrom, animation),"
                lines += "progress = progress,"
                lines += tintLines
                lines += ")"
            }
            Target.DRAW_ICON -> {
                lines += "val animation = VectorAnimation {"
                lines += "    allPaths {"
                lines += "        strokeReveal("
                lines += "            start = PathStart.${state.pathStart.name},"
                lines += "            direction = DrawDirection.${state.drawDirection.name},"
                lines += "            interval = 0f..1f,"
                lines += "            mode = DrawMode.${state.drawMode.name},"
                lines += "        )"
                lines += "    }"
                if (state.staggerDelay > 0f) {
                    lines += "    stagger(${f(state.staggerDelay)}, DrawOrderStrategy.${state.staggerOrder.name})"
                }
                lines += "}"
                lines += "DrawIcon("
                lines += "vector = $iconFrom,"
                lines += "animation = animation,"
                lines += "animationSpec = tween($duration),"
                lines += tintLines
                lines += ")"
            }
        }
        return lines.joinToString("\n")
    }

    private fun hex(color: Color): String {
        val argb = (color.alpha * 255f).toInt().shl(24) or
            (color.red * 255f).toInt().shl(16) or
            (color.green * 255f).toInt().shl(8) or
            (color.blue * 255f).toInt()
        return "%08X".format(argb)
    }

    private fun f(value: Float): String =
        if (value == value.roundToInt().toFloat()) "${value.roundToInt()}f" else "$value" + "f"
}
