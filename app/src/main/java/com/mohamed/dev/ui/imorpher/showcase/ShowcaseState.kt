package com.mohamed.dev.ui.imorpher.showcase

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MorphDebugOptions
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathMapping
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.imorpher.vectormorph.core.reveal.DirectionMath
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning

/** Tint style selected in playgrounds: none, one solid color, or a gradient. */
enum class TintMode { NONE, SOLID, GRADIENT }

/** Overall placement of the fitted icon inside its layout bounds. */
enum class AlignmentKind(val label: String) {
    CENTER("Center"),
    CENTER_START("Center start"),
    CENTER_END("Center end"),
    TOP_CENTER("Top center"),
    BOTTOM_CENTER("Bottom center"),
}

/** Compose animation spec shape driving the overall morph progress. */
enum class AnimationSpecKind(val label: String) {
    TWEEN("Tween"),
    LINEAR("Tween (linear)"),
    SPRING_MEDIUM_BOUNCY("Spring (bouncy)"),
    SPRING_NO_BOUNCY("Spring (no bounce)"),
}

/** One row of the per-path draw-anchor editor: [pathName] starts at [fraction] (0..1). */
data class AnchorEntry(val pathName: String, val fraction: Float)

/** One row of the mappings editor: source path [source] morphs into [target]. */
data class PathMappingEdit(val source: String, val target: String)

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
    var startPoint by mutableStateOf(StartPointStrategy.AUTO)
    var pathDirection by mutableStateOf(PathDirectionStrategy.AUTO)
    var directionTraversal by mutableStateOf(DirectionTraversal.HONOR_DIRECTION)
    var drawOrder by mutableStateOf(DrawOrderStrategy.AUTO)
    var strokeFill by mutableStateOf(StrokeFillStrategy.AUTO)
    var defaultFillDirection by mutableStateOf(DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT)
    var viewportNormalization by mutableStateOf(ViewportNormalization.STRETCH)

    // --- geometry budget
    var segmentBudget by mutableStateOf<Int?>(null)
    var simplificationTolerance by mutableStateOf(0.25f)

    // --- editors
    var customAnchors by mutableStateOf(listOf<AnchorEntry>())
    var customMappings by mutableStateOf(listOf<PathMappingEdit>())
    var customDrawOrderNames by mutableStateOf(listOf<String>())

    // --- debug overlays
    var debugEnabled by mutableStateOf(false)
    var showPathPoints by mutableStateOf(false)
    var showPathNumbers by mutableStateOf(false)
    var showBoundingBoxes by mutableStateOf(false)
    var showCentroids by mutableStateOf(false)
    var showDirection by mutableStateOf(false)
    var showStartPoints by mutableStateOf(false)
    var showPathMatching by mutableStateOf(false)
    var markerArcFraction by mutableStateOf(0f)

    // --- sizing/placement
    var iconWidthDp by mutableStateOf<Float?>(null)
    var iconHeightDp by mutableStateOf<Float?>(null)
    var alignment by mutableStateOf(AlignmentKind.CENTER)
    var specKind by mutableStateOf(AnimationSpecKind.TWEEN)

    // --- reveal geometry
    var drawMode by mutableStateOf(DrawMode.FORWARD)
    var drawDirection by mutableStateOf(DrawDirection.AUTO)
    var pathStart by mutableStateOf(PathStart.AUTO)
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
     * Coherence diagnostics for the selected start/direction/mode combo, resolved through
     * `DirectionMath.resolve` (the renderer's anchor-precedence contract): empty when the
     * pickers agree, otherwise the conflict warnings playgrounds surface as a hint under
     * the start/direction dropdowns.
     *
     * Mode handling: [DrawMode.RADIAL] short-circuits to no warnings — a radial reveal
     * renders through a circular clip centred on the path centroid, so neither the anchor
     * nor the direction takes part and a conflict hint would be noise. [DrawMode.CUSTOM]
     * is not listed in the pickers and resolves like FORWARD inside the resolver, so the
     * stored value passes through unchanged.
     */
    fun startDirectionWarnings(): List<DrawResolutionWarning> {
        if (drawMode == DrawMode.RADIAL) return emptyList()
        return DirectionMath.resolve(pathStart, drawDirection, drawMode).warnings
    }

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
        startPoint = defaults.startPoint
        pathDirection = defaults.pathDirection
        directionTraversal = defaults.directionTraversal
        drawOrder = defaults.drawOrder
        strokeFill = defaults.strokeFill
        defaultFillDirection = defaults.defaultFillDirection
        viewportNormalization = defaults.viewportNormalization
        segmentBudget = defaults.segmentBudget
        simplificationTolerance = defaults.simplificationTolerance
        customAnchors = defaults.customAnchors
        customMappings = defaults.customMappings
        customDrawOrderNames = defaults.customDrawOrderNames
        debugEnabled = defaults.debugEnabled
        showPathPoints = defaults.showPathPoints
        showPathNumbers = defaults.showPathNumbers
        showBoundingBoxes = defaults.showBoundingBoxes
        showCentroids = defaults.showCentroids
        showDirection = defaults.showDirection
        showStartPoints = defaults.showStartPoints
        showPathMatching = defaults.showPathMatching
        markerArcFraction = defaults.markerArcFraction
        iconWidthDp = defaults.iconWidthDp
        iconHeightDp = defaults.iconHeightDp
        alignment = defaults.alignment
        specKind = defaults.specKind
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

    val startPoints = StartPointStrategy.entries
    val pathDirections = PathDirectionStrategy.entries
    val directionTraversals = DirectionTraversal.entries
    val drawOrders = DrawOrderStrategy.entries.filter { it != DrawOrderStrategy.CUSTOM }
    val strokeFills = StrokeFillStrategy.entries
    val viewportNormalizations = ViewportNormalization.entries
    val alignments = AlignmentKind.entries
    val specKinds = AnimationSpecKind.entries
    val fillDirections = DrawDirection.entries.filter { it != DrawDirection.CUSTOM }

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
    startPoint = startPoint,
    pathDirection = pathDirection,
    drawOrder = drawOrder,
    customDrawOrder = customDrawOrderNames,
    missingPath = missingPath,
    fallback = fallback,
    timing = timing,
    colorSpace = colorSpace,
    strokeFill = strokeFill,
    animation = null,
    staggerDelay = staggerDelay,
    staggerOrder = staggerOrder,
    customMappings = customMappings.map { PathMapping(source = it.source, target = it.target) },
    customDrawAnchors = customAnchors.associate { it.pathName to it.fraction },
    defaultFillMode = fillMode,
    defaultFillDirection = defaultFillDirection,
    debug = toDebugOptions(),
    keepRenderOrder = keepRenderOrder,
    directionTraversal = directionTraversal,
    segmentBudget = segmentBudget,
    simplificationTolerance = simplificationTolerance,
    viewportNormalization = viewportNormalization,
)

/** [MorphConfiguration.debug] value for the current state (null while overlays are off). */
fun ShowcaseState.toDebugOptions(): MorphDebugOptions? {
    if (!debugEnabled) return null
    return MorphDebugOptions(
        showPathPoints = showPathPoints,
        showPathNumbers = showPathNumbers,
        showBoundingBoxes = showBoundingBoxes,
        showCentroids = showCentroids,
        showDirection = showDirection,
        showStartPoints = showStartPoints,
        showPathMatching = showPathMatching,
        markerArcFraction = markerArcFraction,
    )
}

/** Overall progress spec per the selected [AnimationSpecKind]; tween kinds carry [ShowcaseState.durationMillis]. */
fun ShowcaseState.toAnimationSpec(): AnimationSpec<Float> = when (specKind) {
    AnimationSpecKind.TWEEN -> tween(durationMillis = durationMillis.toInt())
    AnimationSpecKind.LINEAR -> tween(durationMillis = durationMillis.toInt(), easing = LinearEasing)
    AnimationSpecKind.SPRING_MEDIUM_BOUNCY -> spring(dampingRatio = 0.5f, stiffness = 30f)
    AnimationSpecKind.SPRING_NO_BOUNCY -> spring(dampingRatio = 1f, stiffness = 400f)
}

/** Compose [Alignment] placement per the selected [AlignmentKind]. */
fun ShowcaseState.toAlignment(): Alignment = when (alignment) {
    AlignmentKind.CENTER -> Alignment.Center
    AlignmentKind.CENTER_START -> Alignment.CenterStart
    AlignmentKind.CENTER_END -> Alignment.CenterEnd
    AlignmentKind.TOP_CENTER -> Alignment.TopCenter
    AlignmentKind.BOTTOM_CENTER -> Alignment.BottomCenter
}
