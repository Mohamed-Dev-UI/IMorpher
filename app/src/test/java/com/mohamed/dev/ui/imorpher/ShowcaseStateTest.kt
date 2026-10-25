package com.mohamed.dev.ui.imorpher

import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DirectionTraversal
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.mohamed.dev.ui.imorpher.showcase.AlignmentKind
import com.mohamed.dev.ui.imorpher.showcase.AnchorEntry
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestions
import com.mohamed.dev.ui.imorpher.showcase.PathMappingEdit
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseOptions
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseStatePersistence
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
import com.mohamed.dev.ui.imorpher.showcase.TintBuilders
import com.mohamed.dev.ui.imorpher.showcase.TintMode
import com.mohamed.dev.ui.imorpher.showcase.toAlignment
import com.mohamed.dev.ui.imorpher.showcase.toAnimationSpec
import com.mohamed.dev.ui.imorpher.showcase.toConfiguration
import com.mohamed.dev.ui.imorpher.showcase.toDebugOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure JVM coverage for the showcase data layer: state defaults and reset, persistence
 * round-trips (including corrupt blobs), snippet compilation-correctness invariants,
 * option-list sanity, and the engine-ranked pair suggestions. Runs under Robolectric so
 * org.json (used by persistence) has a real implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowcaseStateTest {

    // ------------------------------------------------------------------ state

    @Test
    fun defaultsResolveToRealCatalogIcons() {
        val state = ShowcaseState()
        assertEquals(IconCatalog.icon(state.fromIconName), state.fromIcon)
        assertEquals(IconCatalog.icon(state.toIconName), state.toIcon)
        assertTrue("defaults must be a curated morphable pair", state.fromIcon !== state.toIcon)
    }

    @Test
    fun resetToDefaultsRestoresEveryMutableField() {
        val state = ShowcaseState()
        val before = ShowcaseState()

        state.fromIconName = "ArchiveSolid"
        state.primary = Color(0xFFFF00FF)
        state.tintMode = TintMode.GRADIENT
        state.durationMillis = 1200f
        state.motionPreference = MotionPreference.INSTANT
        state.pathMatching = com.imorpher.vectormorph.core.model.PathMatchingStrategy.BY_NAME
        state.missingPath = MissingPathBehavior.COLLAPSE
        state.fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.CROSSFADE
        state.timing = TimingMode.BY_COMMAND
        state.colorSpace = ColorInterpolationSpace.OKLCH
        state.keepRenderOrder = false
        state.drawMode = DrawMode.CENTER_OUT
        state.drawDirection = DrawDirection.BOTTOM_TO_TOP
        state.pathStart = PathStart.BOTTOM_RIGHT
        state.fillMode = com.imorpher.vectormorph.core.model.FillMode.RADIAL
        state.staggerDelay = 0.2f
        state.staggerOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.BOTTOM_RIGHT_TO_TOP_LEFT
        state.startScale = 1.4f
        state.endScale = 0.3f
        state.startRotation = 170f
        state.endRotation = -170f
        state.strokeWidth = 2.9f
        state.revealEnabled = false
        state.colorEnabled = true
        state.startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.MATCH_SOURCE
        state.pathDirection = com.imorpher.vectormorph.core.model.PathDirectionStrategy.CLOCKWISE
        state.directionTraversal = com.imorpher.vectormorph.core.model.DirectionTraversal.LEGACY
        state.drawOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.COUNTER_CLOCKWISE
        state.strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.MORPH
        state.defaultFillDirection = com.imorpher.vectormorph.core.model.DrawDirection.BOTTOM_TO_TOP
        state.viewportNormalization = com.imorpher.vectormorph.core.model.ViewportNormalization.FIT
        state.alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.BOTTOM_CENTER
        state.debugEnabled = true
        state.showPathPoints = true
        state.showPathNumbers = true
        state.showBoundingBoxes = true
        state.showCentroids = true
        state.showDirection = true
        state.showStartPoints = true
        state.showPathMatching = true
        state.markerArcFraction = 0.8f
        state.segmentBudget = 12
        state.simplificationTolerance = 0.75f
        state.iconWidthDp = 44f
        state.iconHeightDp = 44f
        state.specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.SPRING_MEDIUM_BOUNCY
        state.customAnchors = listOf(com.mohamed.dev.ui.imorpher.showcase.AnchorEntry("path[1]", 0.4f))
        state.customMappings = listOf(com.mohamed.dev.ui.imorpher.showcase.PathMappingEdit("a", "b"))
        state.customDrawOrderNames = listOf("path[2]", "path[1]")

        state.resetToDefaults()

        assertEquals(before.fromIconName, state.fromIconName)
        assertEquals(before.primary, state.primary)
        assertEquals(before.tintMode, state.tintMode)
        assertEquals(before.durationMillis, state.durationMillis)
        assertEquals(before.motionPreference, state.motionPreference)
        assertEquals(before.pathMatching, state.pathMatching)
        assertEquals(before.missingPath, state.missingPath)
        assertEquals(before.fallback, state.fallback)
        assertEquals(before.timing, state.timing)
        assertEquals(before.colorSpace, state.colorSpace)
        assertEquals(before.keepRenderOrder, state.keepRenderOrder)
        assertEquals(before.drawMode, state.drawMode)
        assertEquals(before.drawDirection, state.drawDirection)
        assertEquals(before.pathStart, state.pathStart)
        assertEquals(before.fillMode, state.fillMode)
        assertEquals(before.staggerDelay, state.staggerDelay)
        assertEquals(before.staggerOrder, state.staggerOrder)
        assertEquals(before.startScale, state.startScale)
        assertEquals(before.endScale, state.endScale)
        assertEquals(before.startRotation, state.startRotation)
        assertEquals(before.endRotation, state.endRotation)
        assertEquals(before.strokeWidth, state.strokeWidth)
        assertEquals(before.revealEnabled, state.revealEnabled)
        assertEquals(before.colorEnabled, state.colorEnabled)
        assertEquals(before.startPoint, state.startPoint)
        assertEquals(before.pathDirection, state.pathDirection)
        assertEquals(before.directionTraversal, state.directionTraversal)
        assertEquals(before.drawOrder, state.drawOrder)
        assertEquals(before.strokeFill, state.strokeFill)
        assertEquals(before.defaultFillDirection, state.defaultFillDirection)
        assertEquals(before.viewportNormalization, state.viewportNormalization)
        assertEquals(before.alignment, state.alignment)
        assertEquals(before.debugEnabled, state.debugEnabled)
        assertEquals(before.showPathPoints, state.showPathPoints)
        assertEquals(before.showPathNumbers, state.showPathNumbers)
        assertEquals(before.showBoundingBoxes, state.showBoundingBoxes)
        assertEquals(before.showCentroids, state.showCentroids)
        assertEquals(before.showDirection, state.showDirection)
        assertEquals(before.showStartPoints, state.showStartPoints)
        assertEquals(before.showPathMatching, state.showPathMatching)
        assertEquals(before.markerArcFraction, state.markerArcFraction)
        assertEquals(before.segmentBudget, state.segmentBudget)
        assertEquals(before.simplificationTolerance, state.simplificationTolerance)
        assertEquals(before.iconWidthDp, state.iconWidthDp)
        assertEquals(before.iconHeightDp, state.iconHeightDp)
        assertEquals(before.specKind, state.specKind)
        assertEquals(before.customAnchors, state.customAnchors)
        assertEquals(before.customMappings, state.customMappings)
        assertEquals(before.customDrawOrderNames, state.customDrawOrderNames)
    }

    @Test
    fun inheritTransfersOnlySharedChoices() {
        val source = ShowcaseState().apply {
            fromIconName = "AddCircleOutline"
            toIconName = "AddCircleSolid"
            primary = Color(0xFF112233)
            tintMode = TintMode.SOLID
            durationMillis = 900f
        }
        val target = ShowcaseState()

        target.inherit(source)

        assertEquals("AddCircleOutline", target.fromIconName)
        assertEquals("AddCircleSolid", target.toIconName)
        assertEquals(Color(0xFF112233), target.primary)
        assertEquals(TintMode.SOLID, target.tintMode)
        assertNotEquals(900f, target.durationMillis)
    }

    @Test
    fun configurationCarriesPlaygroundChoices() {
        val state = ShowcaseState().apply {
            missingPath = MissingPathBehavior.KEEP
            timing = TimingMode.BY_COMMAND
            staggerDelay = 0.15f
        }
        val configuration = state.toConfiguration()
        assertEquals(MissingPathBehavior.KEEP, configuration.missingPath)
        assertEquals(TimingMode.BY_COMMAND, configuration.timing)
        assertEquals(0.15f, configuration.staggerDelay)
    }

    // ------------------------------------------------------------------ persistence

    @Test
    fun persistenceRoundTripsEveryField() {
        val original = ShowcaseState().apply {
            fromIconName = "ArrangeCircle2Outline"
            toIconName = "ArrangeCircle2Solid"
            primary = Color(0xFF010203)
            secondary = Color(0xFF040506)
            accent = Color(0xFF070809)
            tintMode = TintMode.GRADIENT
            durationMillis = 777f
            motionPreference = MotionPreference.CROSSFADE_ONLY
            missingPath = MissingPathBehavior.SCALE
            timing = TimingMode.UNIFORM
            colorSpace = ColorInterpolationSpace.LINEAR_SRGB
            keepRenderOrder = false
            drawMode = DrawMode.OUTSIDE_IN
            drawDirection = DrawDirection.LEFT_TO_RIGHT
            pathStart = PathStart.CENTER
            staggerDelay = 0.25f
            startScale = 1.25f
            endRotation = -45f
            strokeWidth = 2.25f
            revealEnabled = false
            colorEnabled = true
            startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.MATCH_TARGET
            pathDirection = com.imorpher.vectormorph.core.model.PathDirectionStrategy.COUNTER_CLOCKWISE
            directionTraversal = com.imorpher.vectormorph.core.model.DirectionTraversal.LEGACY
            drawOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.CLOCKWISE
            strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.DRAW
            defaultFillDirection = com.imorpher.vectormorph.core.model.DrawDirection.RIGHT_TO_LEFT
            viewportNormalization = com.imorpher.vectormorph.core.model.ViewportNormalization.FIT
            alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.TOP_CENTER
            debugEnabled = true
            showPathNumbers = true
            showDirection = true
            showPathMatching = true
            markerArcFraction = 0.55f
            segmentBudget = 14
            simplificationTolerance = 0.5f
            iconWidthDp = 40f
            iconHeightDp = 40f
            specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.SPRING_NO_BOUNCY
            customAnchors = listOf(com.mohamed.dev.ui.imorpher.showcase.AnchorEntry("path[1]", 0.25f))
            customMappings = listOf(com.mohamed.dev.ui.imorpher.showcase.PathMappingEdit("path[1]", "path[2]"))
            customDrawOrderNames = listOf("path[2]", "path[1]")
        }

        val restored = ShowcaseState()
        assertTrue(
            ShowcaseStatePersistence.applyJsonForTest(
                ShowcaseStatePersistence.toJsonForTest(original),
                restored,
            ),
        )

        assertEquals(original.fromIconName, restored.fromIconName)
        assertEquals(original.toIconName, restored.toIconName)
        assertEquals(original.primary, restored.primary)
        assertEquals(original.secondary, restored.secondary)
        assertEquals(original.accent, restored.accent)
        assertEquals(original.tintMode, restored.tintMode)
        assertEquals(original.durationMillis, restored.durationMillis)
        assertEquals(original.motionPreference, restored.motionPreference)
        assertEquals(original.missingPath, restored.missingPath)
        assertEquals(original.timing, restored.timing)
        assertEquals(original.colorSpace, restored.colorSpace)
        assertEquals(original.keepRenderOrder, restored.keepRenderOrder)
        assertEquals(original.drawMode, restored.drawMode)
        assertEquals(original.drawDirection, restored.drawDirection)
        assertEquals(original.pathStart, restored.pathStart)
        assertEquals(original.staggerDelay, restored.staggerDelay)
        assertEquals(original.startScale, restored.startScale)
        assertEquals(original.endRotation, restored.endRotation)
        assertEquals(original.strokeWidth, restored.strokeWidth)
        assertEquals(original.revealEnabled, restored.revealEnabled)
        assertEquals(original.colorEnabled, restored.colorEnabled)
        assertEquals(original.startPoint, restored.startPoint)
        assertEquals(original.pathDirection, restored.pathDirection)
        assertEquals(original.directionTraversal, restored.directionTraversal)
        assertEquals(original.drawOrder, restored.drawOrder)
        assertEquals(original.strokeFill, restored.strokeFill)
        assertEquals(original.defaultFillDirection, restored.defaultFillDirection)
        assertEquals(original.viewportNormalization, restored.viewportNormalization)
        assertEquals(original.alignment, restored.alignment)
        assertEquals(original.debugEnabled, restored.debugEnabled)
        assertEquals(original.showPathNumbers, restored.showPathNumbers)
        assertEquals(original.showDirection, restored.showDirection)
        assertEquals(original.showPathMatching, restored.showPathMatching)
        assertEquals(original.markerArcFraction, restored.markerArcFraction)
        assertEquals(original.segmentBudget, restored.segmentBudget)
        assertEquals(original.simplificationTolerance, restored.simplificationTolerance)
        assertEquals(original.iconWidthDp, restored.iconWidthDp)
        assertEquals(original.iconHeightDp, restored.iconHeightDp)
        assertEquals(original.specKind, restored.specKind)
        assertEquals(original.customAnchors, restored.customAnchors)
        assertEquals(original.customMappings, restored.customMappings)
        assertEquals(original.customDrawOrderNames, restored.customDrawOrderNames)
    }

    @Test
    fun corruptPersistenceBlobFallsBackToDefaults() {
        val state = ShowcaseState().apply { durationMillis = 1234f; tintMode = TintMode.SOLID }
        assertFalse(ShowcaseStatePersistence.applyJsonForTest("not json at all", state))
        assertEquals("corrupt blob must not half-apply", 1234f, state.durationMillis)
        assertEquals(TintMode.SOLID, state.tintMode)
    }

    @Test
    fun unknownEnumNamesAndIconNamesFallBackToDefaults() {
        val state = ShowcaseState().apply { fromIconName = "SomethingNew" }
        val json = ShowcaseStatePersistence.toJsonForTest(ShowcaseState())
            .replace("\"fromIconName\":\"ArrowUp1\"", "\"fromIconName\":\"DoesNotExist\"")
            .replace("\"timing\":\"BY_PATH_LENGTH\"", "\"timing\":\"WARP_DRIVE\"")
        assertTrue(ShowcaseStatePersistence.applyJsonForTest(json, state))
        assertEquals("unknown icon must keep the current/default name", "SomethingNew", state.fromIconName)
        assertEquals(TimingMode.BY_PATH_LENGTH, state.timing)
    }

    @Test
    fun infiniteJsonNumbersKeepCurrentValues() {
        // 1e308 is a finite double that overflows to +/-Infinity when narrowed to Float —
        // exactly the gap the NaN-only guard misses.
        val state = ShowcaseState().apply {
            durationMillis = 700f
            staggerDelay = 0.3f
            startRotation = -45f
        }
        val corrupt = "{\"durationMillis\":1e308,\"staggerDelay\":-1e308,\"startRotation\":1e308}"
        assertTrue(ShowcaseStatePersistence.applyJsonForTest(corrupt, state))
        assertEquals("infinite numbers must keep the current values", 700f, state.durationMillis)
        assertEquals("infinite numbers must keep the current values", 0.3f, state.staggerDelay)
        assertEquals("infinite numbers must keep the current values", -45f, state.startRotation)
    }

    @Test
    fun unknownIconNameDegradesToAValidCatalogVector() {
        // icon() is a backstop for a programming error (names entering state are validated
        // by hasIcon), so instead of crashing a session it degrades to a catalog member.
        val degraded = IconCatalog.icon("DoesNotExist")
        assertSame(IconCatalog.icon("AddCircleOutline"), degraded)
        assertSame(IconCatalog.icon("ArrowUp1"), IconCatalog.icon("ArrowUp1"))
    }

    @Test
    fun pathNamesResolveOffTheMainThreadIntoRealNames() {
        // SealOutline/SealSolid plan with the named paths ring/cross/dot. First call may
        // return the placeholder while the lookup warms; the result must surface through
        // recomposition without ever planning on the caller's thread.
        val deadline = System.currentTimeMillis() + 5_000
        var names = PairSuggestions.pathNamesFor("SealOutline", "SealSolid")
        while (System.currentTimeMillis() < deadline) {
            if (names.containsAll(listOf("ring", "cross", "dot"))) break
            Thread.sleep(25)
            names = PairSuggestions.pathNamesFor("SealOutline", "SealSolid")
        }
        assertTrue(
            "path names must resolve to the plan's real names ($names)",
            names.containsAll(listOf("ring", "cross", "dot")),
        )
    }

    @Test
    fun pathNamesCacheStaysBounded() {
        val fromNames = IconCatalog.entries.map { it.name }
        var requested = 0
        outer@ for (from in fromNames) {
            for (to in fromNames) {
                if (requested >= 120) break@outer
                PairSuggestions.pathNamesFor(from, to)
                requested++
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (PairSuggestions.cachedPathNamesCountForTest() > 64) break
            Thread.sleep(25)
        }
        assertTrue("bounded cache must exceed a few entries", PairSuggestions.cachedPathNamesCountForTest() >= 16)
        assertTrue(
            "cache exceeded its cap (${PairSuggestions.cachedPathNamesCountForTest()})",
            PairSuggestions.cachedPathNamesCountForTest() <= 64,
        )
    }

    // ------------------------------------------------------------------ snippets

    @Test
    fun snippetsUseResolvableIconAccessorsForEveryTarget() {
        for (target in SnippetGenerator.Target.entries) {
            val snippet = SnippetGenerator.generate(ShowcaseState(), target)
            assertTrue("$target must reference IMorpherIcons accessors", snippet.contains("IMorpherIcons."))
            assertFalse(
                "$target must not emit bare icon names (uncompilable)",
                Regex("from = (?!IMorpherIcons)[A-Za-z]").containsMatchIn(snippet),
            )
        }
    }

    @Test
    fun snippetTrackValuesMatchState() {
        val state = ShowcaseState().apply {
            durationMillis = 650f
            tintMode = TintMode.SOLID
            startScale = 0.9f
            startRotation = -45f
            staggerDelay = 0.2f
        }
        val snippet = SnippetGenerator.generate(state, SnippetGenerator.Target.VECTOR_ANIMATOR)
        // VectorAnimator is a manual driver: it takes progress, never a tween spec.
        assertFalse(snippet.contains("tween("))
        assertTrue(snippet.contains("tintFrom = Color(0x"))
        assertTrue(snippet.contains("scale = 0.9f"))
        assertTrue(snippet.contains("rotation = -45f"))
        assertTrue(snippet.contains("stagger(0.2f"))
        val iconSnippet = SnippetGenerator.generate(state, SnippetGenerator.Target.MORPH_ICON)
        assertTrue(iconSnippet.contains("tween(650)"))
    }

    @Test
    fun drawIconSnippetOmitsStaggerWhenDisabled() {
        val state = ShowcaseState().apply { staggerDelay = 0f }
        val snippet = SnippetGenerator.generate(state, SnippetGenerator.Target.DRAW_ICON)
        assertFalse("zero stagger must not be emitted", snippet.contains("stagger("))
    }

    @Test
    fun gradientSnippetIncludesBothEndpointBrushes() {
        val state = ShowcaseState().apply { tintMode = TintMode.GRADIENT }
        val snippet = SnippetGenerator.generate(state, SnippetGenerator.Target.MORPH_VECTOR)
        assertTrue(snippet.contains("Brush.linearGradient"))
        assertEquals(1, Regex("tintFrom = Brush.linearGradient").findAll(snippet).count())
        assertEquals(1, Regex("tintTo = Brush.linearGradient").findAll(snippet).count())
    }

    @Test
    fun snippetEmitsEveryNonDefaultParameter() {
        val state = ShowcaseState().apply {
            pathMatching = PathMatchingStrategy.BY_NAME
            missingPath = MissingPathBehavior.KEEP
            timing = TimingMode.UNIFORM
            colorSpace = ColorInterpolationSpace.LINEAR_SRGB
            strokeFill = StrokeFillStrategy.DRAW
            startPoint = StartPointStrategy.MATCH_TARGET
            pathDirection = PathDirectionStrategy.COUNTER_CLOCKWISE
            directionTraversal = DirectionTraversal.LEGACY
            drawOrder = DrawOrderStrategy.CLOCKWISE
            customDrawOrderNames = listOf("ring", "cross")
            keepRenderOrder = false
            viewportNormalization = ViewportNormalization.FIT
            segmentBudget = 12
            simplificationTolerance = 0.4f
            defaultFillDirection = DrawDirection.RIGHT_TO_LEFT
            staggerDelay = 0.2f
            customAnchors = listOf(AnchorEntry("ring", 0.25f))
            customMappings = listOf(PathMappingEdit("ring", "ring"))
            debugEnabled = true
            showPathPoints = true
            markerArcFraction = 0.4f
            iconWidthDp = 56f
            iconHeightDp = 40f
            alignment = AlignmentKind.TOP_CENTER
            durationMillis = 500f
            motionPreference = MotionPreference.REDUCED
        }
        val snippet = SnippetGenerator.generate(state, SnippetGenerator.Target.MORPH_ICON)
        for (needle in listOf(
            "pathMatching = PathMatchingStrategy.BY_NAME",
            "missingPath = MissingPathBehavior.KEEP",
            "timing = TimingMode.UNIFORM",
            "colorSpace = ColorInterpolationSpace.LINEAR_SRGB",
            "strokeFill = StrokeFillStrategy.DRAW",
            "startPoint = StartPointStrategy.MATCH_TARGET",
            "pathDirection = PathDirectionStrategy.COUNTER_CLOCKWISE",
            "directionTraversal = DirectionTraversal.LEGACY",
            "drawOrder = DrawOrderStrategy.CLOCKWISE",
            "customDrawOrder = listOf(\"ring\", \"cross\")",
            "keepRenderOrder = false",
            "viewportNormalization = ViewportNormalization.FIT",
            "segmentBudget = 12",
            "simplificationTolerance = 0.4f",
            "defaultFillDirection = DrawDirection.RIGHT_TO_LEFT",
            "staggerDelay = 0.2f",
            "customDrawAnchors = mapOf(\"ring\" to 0.25f)",
            "customMappings = listOf(PathMapping(source = \"ring\", target = \"ring\"))",
            "debug = MorphDebugOptions(",
            "showPathPoints = true",
            "markerArcFraction = 0.4f",
            "width = 56.dp",
            "height = 40.dp",
            "alignment = Alignment.TopCenter",
            "tween(500)",
            "motionPreference = MotionPreference.REDUCED",
        )) {
            assertTrue("snippet must emit '$needle'", snippet.contains(needle))
        }
    }

    @Test
    fun snippetOmitsDefaultConfigurationValues() {
        val snippet = SnippetGenerator.generate(ShowcaseState(), SnippetGenerator.Target.MORPH_ICON)
        for (needle in listOf(
            "pathMatching", "missingPath", "fallback", "timing", "colorSpace", "strokeFill",
            "startPoint", "pathDirection", "directionTraversal", "drawOrder", "staggerDelay",
            "customDrawAnchors", "customMappings", "customDrawOrder", "viewportNormalization",
            "segmentBudget", "simplificationTolerance", "MorphDebugOptions", "keepRenderOrder",
            "alignment =", "width =", "height =", "animationSpec", "motionPreference",
        )) {
            assertFalse("default '$needle' must be omitted from the snippet", snippet.contains(needle))
        }
    }

    @Test
    fun staggerSnippetAlwaysEmitsTheDrawOrderArgument() {
        // VectorAnimationBuilder.stagger(delay, order) has NO default for `order`, so the
        // pasted snippet only compiles when the argument is spelled out — even at AUTO.
        for (target in listOf(SnippetGenerator.Target.VECTOR_ANIMATOR, SnippetGenerator.Target.DRAW_ICON)) {
            val autoState = ShowcaseState().apply { staggerDelay = 0.2f }
            val autoSnippet = SnippetGenerator.generate(autoState, target)
            assertTrue(
                "$target must emit the order argument at AUTO (stagger has no order default)",
                autoSnippet.contains("stagger(0.2f, DrawOrderStrategy.AUTO)"),
            )
            val orderedState = ShowcaseState().apply {
                staggerDelay = 0.2f
                staggerOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.COUNTER_CLOCKWISE
            }
            val orderedSnippet = SnippetGenerator.generate(orderedState, target)
            assertTrue(
                "$target must emit the chosen order",
                orderedSnippet.contains("stagger(0.2f, DrawOrderStrategy.COUNTER_CLOCKWISE)"),
            )
        }
    }

    // ------------------------------------------------------------------ options + suggestions

    @Test
    fun everyCatalogIconResolves() {
        for (entry in IconCatalog.entries) {
            val icon = IconCatalog.icon(entry.name)
            assertEquals("icon ${entry.name} must exist", entry.name, icon.name)
        }
    }

    @Test
    fun curatedPairsReferenceCatalogIcons() {
        for ((first, second) in IconCatalog.pairs) {
            IconCatalog.icon(first)
            IconCatalog.icon(second)
        }
        assertTrue(IconCatalog.pairs.size >= 12)
    }

    @Test
    fun optionListsExcludeCustomOnlyValues() {
        assertFalse(ShowcaseOptions.drawModes.contains(DrawMode.CUSTOM))
        assertFalse(ShowcaseOptions.pathStarts.contains(PathStart.CUSTOM))
        assertTrue(ShowcaseOptions.motionPreferences.contains(MotionPreference.FULL))
        assertTrue(ShowcaseOptions.colorSpacesLabeled.size == ShowcaseOptions.colorSpaces.size)
    }

    @Test
    fun tintBuildersProduceAllThreeModes() {
        val state = ShowcaseState().apply {
            tintMode = TintMode.GRADIENT
            primary = Color(0xFF000001)
            secondary = Color(0xFF000002)
            accent = Color(0xFF000003)
        }
        assertEquals(state.primary, TintBuilders.fromColor(state))
        assertEquals(state.secondary, TintBuilders.toColor(state))
        val fromBrush = TintBuilders.fromBrush(state)
        val toBrush = TintBuilders.toBrush(state)
        assertNotEquals(fromBrush, toBrush)
    }

    @Test
    fun pairSuggestionsRankAllCuratedPairs() = runBlocking {
        val ranking = PairSuggestions.ranking(kotlinx.coroutines.CoroutineScope(Dispatchers.Default))
        // Poll briefly for the async ranking pass; it is pure CPU work over 15 pairs.
        val deadline = System.currentTimeMillis() + 10_000
        while (ranking.isRunning && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertFalse(ranking.isRunning)
        assertEquals(IconCatalog.pairs.size, ranking.suggestions.size)
        val scores = ranking.suggestions.map { it.score }
        assertEquals("must be sorted by descending score", scores, scores.sortedDescending())
        for (suggestion in ranking.suggestions) {
            IconCatalog.icon(suggestion.fromName)
            IconCatalog.icon(suggestion.toName)
        }
    }

    // ------------------------------------------------------------------ full config coverage

    @Test
    fun toConfigurationMapsEveryParameterField() {
        val state = ShowcaseState().apply {
            fromIconName = "ArrangeCircle2Outline"
            toIconName = "ArrangeCircle2Solid"
            pathMatching = com.imorpher.vectormorph.core.model.PathMatchingStrategy.BY_GEOMETRY
            startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.MATCH_SOURCE
            pathDirection = com.imorpher.vectormorph.core.model.PathDirectionStrategy.CLOCKWISE
            drawOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.CLOCKWISE
            customDrawOrderNames = listOf("path[2]", "path[1]")
            missingPath = MissingPathBehavior.MORPH_FROM_NEAREST
            fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.SCALE_CROSSFADE
            timing = TimingMode.BY_COMMAND
            colorSpace = ColorInterpolationSpace.OKLAB
            strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.MORPH
            staggerDelay = 0.2f
            staggerOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.TOP_RIGHT_TO_BOTTOM_LEFT
            customMappings = listOf(com.mohamed.dev.ui.imorpher.showcase.PathMappingEdit("path[1]", "path[2]"))
            customAnchors = listOf(com.mohamed.dev.ui.imorpher.showcase.AnchorEntry("path[1]", 0.25f))
            fillMode = com.imorpher.vectormorph.core.model.FillMode.SWEEP
            defaultFillDirection = DrawDirection.LEFT_TO_RIGHT
            keepRenderOrder = false
            directionTraversal = com.imorpher.vectormorph.core.model.DirectionTraversal.LEGACY
            segmentBudget = 12
            simplificationTolerance = 0.4f
            viewportNormalization = com.imorpher.vectormorph.core.model.ViewportNormalization.FIT
            debugEnabled = false
        }
        val configuration = state.toConfiguration()
        val expected = com.imorpher.vectormorph.core.model.MorphConfiguration(
            pathMatching = com.imorpher.vectormorph.core.model.PathMatchingStrategy.BY_GEOMETRY,
            startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.MATCH_SOURCE,
            pathDirection = com.imorpher.vectormorph.core.model.PathDirectionStrategy.CLOCKWISE,
            drawOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.CLOCKWISE,
            customDrawOrder = listOf("path[2]", "path[1]"),
            missingPath = MissingPathBehavior.MORPH_FROM_NEAREST,
            fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.SCALE_CROSSFADE,
            timing = TimingMode.BY_COMMAND,
            colorSpace = ColorInterpolationSpace.OKLAB,
            strokeFill = com.imorpher.vectormorph.core.model.StrokeFillStrategy.MORPH,
            animation = null,
            staggerDelay = 0.2f,
            staggerOrder = com.imorpher.vectormorph.core.model.DrawOrderStrategy.TOP_RIGHT_TO_BOTTOM_LEFT,
            customMappings = listOf(com.imorpher.vectormorph.core.model.PathMapping("path[1]", "path[2]")),
            customDrawAnchors = mapOf("path[1]" to 0.25f),
            defaultFillMode = com.imorpher.vectormorph.core.model.FillMode.SWEEP,
            defaultFillDirection = DrawDirection.LEFT_TO_RIGHT,
            debug = null,
            keepRenderOrder = false,
            directionTraversal = com.imorpher.vectormorph.core.model.DirectionTraversal.LEGACY,
            segmentBudget = 12,
            simplificationTolerance = 0.4f,
            viewportNormalization = com.imorpher.vectormorph.core.model.ViewportNormalization.FIT,
        )
        assertEquals(expected, configuration)
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedLegacyMapStaysOutOfGeneratedConfiguration() {
        val configuration = ShowcaseState().toConfiguration()
        assertEquals(
            "the deprecated alignment map must not be set by the showcase",
            emptyMap<String, Float>(),
            configuration.customStartOffsets,
        )
    }

    @Test
    fun debugOptionsFollowTheMasterSwitch() {
        val disabled = ShowcaseState().apply {
            showPathNumbers = true
            showDirection = true
        }
        assertEquals(null, disabled.toDebugOptions())
        val enabled = ShowcaseState().apply {
            debugEnabled = true
            showPathNumbers = true
            showDirection = true
            markerArcFraction = 0.3f
        }
        assertEquals(
            com.imorpher.vectormorph.core.model.MorphDebugOptions(
                showPathNumbers = true,
                showDirection = true,
                markerArcFraction = 0.3f,
            ),
            enabled.toDebugOptions(),
        )
    }

    @Test
    fun animationSpecsFollowTheSelectedKind() {
        val state = ShowcaseState().apply { durationMillis = 650f }
        state.specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.TWEEN
        assertEquals(androidx.compose.animation.core.tween<Float>(650), state.toAnimationSpec())
        state.specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.LINEAR
        assertEquals(
            androidx.compose.animation.core.tween<Float>(650, easing = androidx.compose.animation.core.LinearEasing),
            state.toAnimationSpec(),
        )
        state.specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.SPRING_MEDIUM_BOUNCY
        assertEquals(
            androidx.compose.animation.core.spring<Float>(dampingRatio = 0.5f, stiffness = 30f),
            state.toAnimationSpec(),
        )
        state.specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.SPRING_NO_BOUNCY
        assertEquals(
            androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 400f),
            state.toAnimationSpec(),
        )
    }

    @Test
    fun alignmentsMapToComposeAlignment() {
        val state = ShowcaseState()
        state.alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.CENTER
        assertEquals(androidx.compose.ui.Alignment.Center, state.toAlignment())
        state.alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.CENTER_START
        assertEquals(androidx.compose.ui.Alignment.CenterStart, state.toAlignment())
        state.alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.CENTER_END
        assertEquals(androidx.compose.ui.Alignment.CenterEnd, state.toAlignment())
        state.alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.TOP_CENTER
        assertEquals(androidx.compose.ui.Alignment.TopCenter, state.toAlignment())
        state.alignment = com.mohamed.dev.ui.imorpher.showcase.AlignmentKind.BOTTOM_CENTER
        assertEquals(androidx.compose.ui.Alignment.BottomCenter, state.toAlignment())
    }

    @Test
    fun malformedNewFieldsFallBackWithoutThrowing() {
        val state = ShowcaseState().apply {
            segmentBudget = 20
            markerArcFraction = 0.5f
            specKind = com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.LINEAR
        }
        val json = ShowcaseStatePersistence.toJsonForTest(ShowcaseState())
            .replace("\"segmentBudget\":-1", "\"segmentBudget\":-5")
            .replace("\"markerArcFraction\":0", "\"markerArcFraction\":1000.0")
            .replace("\"specKind\":\"TWEEN\"", "\"specKind\":\"WARP\"")
            .replace("\"anchorEntries\":[]", "\"anchorEntries\":\"garbage\"")
        assertTrue(ShowcaseStatePersistence.applyJsonForTest(json, state))
        assertEquals("budget sentinel keeps off", null, state.segmentBudget)
        assertEquals("out-of-range fraction clamps into range", 1f, state.markerArcFraction)
        assertEquals(
            "unknown spec kind keeps the current value (load-time fallback convention)",
            com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind.LINEAR,
            state.specKind,
        )
        assertEquals(0, state.customAnchors.size)
    }
}
