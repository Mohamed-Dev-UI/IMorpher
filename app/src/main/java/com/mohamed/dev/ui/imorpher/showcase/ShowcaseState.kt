package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode

/** Tint style selected in playgrounds: none, one solid color, or a gradient. */
enum class TintMode { NONE, SOLID, GRADIENT }

/**
 * Shared, fully mutable parameter state behind every playground. Each playground owns one
 * instance (remembered), and the common controls render directly from it.
 */
class ShowcaseState {
    // --- icons
    var fromIconName by mutableStateOf(IconCatalog.pairs.first().first)
    var toIconName by mutableStateOf(IconCatalog.pairs.first().second)

    // --- color
    var primary by mutableStateOf(Color(0xFF536DFE))
    var secondary by mutableStateOf(Color(0xFF00BCD4))
    var accent by mutableStateOf(Color(0xFFFFC107))
    var tintMode by mutableStateOf(TintMode.NONE)

    // --- timing
    var durationMillis by mutableStateOf(400f)
    var motionPreference by mutableStateOf(MotionPreference.FULL)

    // --- configuration
    var pathMatching by mutableStateOf(PathMatchingStrategy.AUTO)
    var missingPath by mutableStateOf(MissingPathBehavior.FADE)
    var fallback by mutableStateOf(FallbackStrategyDefault)
    var timing by mutableStateOf(TimingMode.BY_PATH_LENGTH)
    var colorSpace by mutableStateOf(ColorInterpolationSpace.SRGB)
    var keepRenderOrder by mutableStateOf(true)

    // --- reveal geometry
    var drawMode by mutableStateOf(DrawMode.FORWARD)
    var drawDirection by mutableStateOf(DrawDirection.AUTO)
    var pathStart by mutableStateOf(PathStart.START)
    var fillMode by mutableStateOf(FillMode.DIRECTIONAL)

    // --- stagger / order
    var staggerDelay by mutableStateOf(0f)
    var staggerOrder by mutableStateOf(DrawOrderStrategy.AUTO)

    // --- transform (VectorAnimator playground)
    var startScale by mutableStateOf(0.7f)
    var endScale by mutableStateOf(1.1f)
    var startRotation by mutableStateOf(180f)
    var endRotation by mutableStateOf(0f)

    // --- paint (DrawIcon playground)
    var strokeWidth by mutableStateOf(1.5f)

    // --- VectorAnimator playground track toggles
    var revealEnabled by mutableStateOf(true)
    var colorEnabled by mutableStateOf(false)

    val fromIcon: Any get() = IconCatalog.icon(fromIconName)
    val toIcon: Any get() = IconCatalog.icon(toIconName)

    /**
     * Restores every field to its default so the in-place "Reset playgrounds" action can
     * wipe experiments without recreating the activity (which would also drop the user
     * back to the home screen).
     */
    fun resetToDefaults() {
        val defaults = ShowcaseState()
        fromIconName = defaults.fromIconName
        toIconName = defaults.toIconName
        primary = defaults.primary
        secondary = defaults.secondary
        accent = defaults.accent
        tintMode = defaults.tintMode
        durationMillis = defaults.durationMillis
        motionPreference = defaults.motionPreference
        pathMatching = defaults.pathMatching
        missingPath = defaults.missingPath
        fallback = defaults.fallback
        timing = defaults.timing
        colorSpace = defaults.colorSpace
        keepRenderOrder = defaults.keepRenderOrder
        drawMode = defaults.drawMode
        drawDirection = defaults.drawDirection
        pathStart = defaults.pathStart
        fillMode = defaults.fillMode
        staggerDelay = defaults.staggerDelay
        staggerOrder = defaults.staggerOrder
        startScale = defaults.startScale
        endScale = defaults.endScale
        startRotation = defaults.startRotation
        endRotation = defaults.endRotation
        strokeWidth = defaults.strokeWidth
        revealEnabled = defaults.revealEnabled
        colorEnabled = defaults.colorEnabled
    }

    /** Transfers another playground's shared choices (icons, colors, tint) into this one. */
    fun inherit(other: ShowcaseState) {
        fromIconName = other.fromIconName
        toIconName = other.toIconName
        primary = other.primary
        secondary = other.secondary
        accent = other.accent
        tintMode = other.tintMode
    }
}

/** The [FallbackStrategy] default is spelled out to avoid a name clash with the state field. */
private val FallbackStrategyDefault = com.imorpher.vectormorph.core.model.FallbackStrategy.AUTO

/** Enum choices surfaced by the playground dropdowns, pre-filtered to useful values. */
object ShowcaseOptions {
    val motionPreferences = MotionPreference.entries
    val pathMatchings = PathMatchingStrategy.entries
    val missingPaths = MissingPathBehavior.entries
    val fallbacks = com.imorpher.vectormorph.core.model.FallbackStrategy.entries
    val timings = TimingMode.entries
    val colorSpaces = ColorInterpolationSpace.entries
    val drawModes = DrawMode.entries.filter { it != DrawMode.CUSTOM }
    val drawDirections = DrawDirection.entries.filter { it !in setOf(DrawDirection.CUSTOM) }
    val pathStarts = PathStart.entries.filter { it != PathStart.CUSTOM }
    val fillModes = FillMode.entries
    val staggerOrders = DrawOrderStrategy.entries.filter { it != DrawOrderStrategy.CUSTOM }
    val tintModes = TintMode.entries

    val colorSpacesLabeled = listOf("SRGB", "Linear sRGB", "OKLab", "OKLCH")
}

/** Curated [from, to] icon pairs for the topical showcases. */
object IconPairs {
    val home = IconCatalog.pair("ArrowUp1", "ArrowUp2Solid")
    val play = IconCatalog.pair("ArrowDown2Outline", "ArrowDown2Solid")
    val add = IconCatalog.pair("AddCircleOutline", "AddCircleSolid")
    val archive = IconCatalog.pair("ArchiveOutline", "ArchiveSolid")
    val algorithm = IconCatalog.pair("AlgorithmOutline", "AlgorithmSolid")
    val arrange = IconCatalog.pair("ArrangeCircle2Outline", "ArrangeCircle2Solid")
}

/** A small curated palette the playgrounds can apply per slot. */
object ShowcaseColors {
    val indigo = Color(0xFF536DFE)
    val cyan = Color(0xFF00BCD4)
    val amber = Color(0xFFFFC107)
    val coral = Color(0xFFFF5722)
    val slate = Color(0xFF838383)
    val ink = Color(0xFF1B1B1F)

    val swatches = listOf(indigo, cyan, amber, coral, slate, ink)
}

/** Builds the tint values a playground passes to a composable, honoring [ShowcaseState.tintMode]. */
object TintBuilders {
    fun fromColor(state: ShowcaseState): Color = state.primary

    fun toColor(state: ShowcaseState): Color = state.secondary

    fun fromBrush(state: ShowcaseState): Brush = Brush.linearGradient(
        colors = listOf(state.primary, state.secondary),
    )

    fun toBrush(state: ShowcaseState): Brush = Brush.linearGradient(
        colors = listOf(state.accent, state.secondary),
    )
}

/** Builds a [MorphConfiguration] from the playground's shared choices. */
fun ShowcaseState.toConfiguration(): MorphConfiguration = MorphConfiguration(
    pathMatching = pathMatching,
    missingPath = missingPath,
    fallback = fallback,
    timing = timing,
    colorSpace = colorSpace,
    keepRenderOrder = keepRenderOrder,
    staggerDelay = staggerDelay,
    staggerOrder = staggerOrder,
)
