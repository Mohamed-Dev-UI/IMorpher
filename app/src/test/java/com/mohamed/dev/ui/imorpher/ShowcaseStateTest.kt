package com.mohamed.dev.ui.imorpher

import androidx.compose.ui.graphics.Color
import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.TimingMode
import com.mohamed.dev.ui.imorpher.showcase.IconCatalog
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestions
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseOptions
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseStatePersistence
import com.mohamed.dev.ui.imorpher.showcase.SnippetGenerator
import com.mohamed.dev.ui.imorpher.showcase.TintBuilders
import com.mohamed.dev.ui.imorpher.showcase.TintMode
import com.mohamed.dev.ui.imorpher.showcase.toConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
