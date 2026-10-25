package com.mohamed.dev.ui.imorpher

import com.imorpher.vectormorph.core.model.ColorInterpolationSpace
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MotionPreference
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.mohamed.dev.ui.imorpher.showcase.AlignmentKind
import com.mohamed.dev.ui.imorpher.showcase.AnimationSpecKind
import com.mohamed.dev.ui.imorpher.showcase.DefaultPathNames
import com.mohamed.dev.ui.imorpher.showcase.PairSuggestions
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the shared ConfigurationPanel against coverage drift. The panel is rendered
 * identically by all four playgrounds, so its option lists must stay pinned to the
 * engine enums (minus the documented CUSTOM-only exclusions), the alias enums must map
 * every entry, and the per-path editors must always receive non-empty path names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigurationPanelCoverageTest {

    @Test
    fun strategyListsArePinnedToTheEngineEnums() {
        assertEquals(PathMatchingStrategy.entries.toList(), ShowcaseOptions.pathMatchings)
        assertEquals(MissingPathBehavior.entries.toList(), ShowcaseOptions.missingPaths)
        assertEquals(FallbackStrategy.entries.toList(), ShowcaseOptions.fallbacks)
        assertEquals(TimingMode.entries.toList(), ShowcaseOptions.timings)
        assertEquals(ColorInterpolationSpace.entries.toList(), ShowcaseOptions.colorSpaces)
        assertEquals(FillMode.entries.toList(), ShowcaseOptions.fillModes)
        assertEquals(StrokeFillStrategy.entries.toList(), ShowcaseOptions.strokeFills)
        assertEquals(StartPointStrategy.entries.toList(), ShowcaseOptions.startPoints)
        assertEquals(PathDirectionStrategy.entries.toList(), ShowcaseOptions.pathDirections)
        assertEquals(
            com.imorpher.vectormorph.core.model.DirectionTraversal.entries.toList(),
            ShowcaseOptions.directionTraversals,
        )
        assertEquals(ViewportNormalization.entries.toList(), ShowcaseOptions.viewportNormalizations)
        assertEquals(MotionPreference.entries.toList(), ShowcaseOptions.motionPreferences)
        assertEquals(AlignmentKind.entries.toList(), ShowcaseOptions.alignments)
        assertEquals(AnimationSpecKind.entries.toList(), ShowcaseOptions.specKinds)
    }

    @Test
    fun customOnlyEntriesStayOutOfEveryPanelPicker() {
        // DrawMode.CUSTOM / DrawDirection.CUSTOM / PathStart.CUSTOM / DrawOrderStrategy.CUSTOM
        // require extra input (callback contours, editors); pickers only carry the plain
        // entries, with the corresponding editors exposed next to their strategy dropdowns.
        assertEquals(
            DrawMode.entries.filter { it != DrawMode.CUSTOM },
            ShowcaseOptions.drawModes,
        )
        assertEquals(
            DrawDirection.entries.filter { it != DrawDirection.CUSTOM },
            ShowcaseOptions.drawDirections,
        )
        assertEquals(
            PathStart.entries.filter { it != PathStart.CUSTOM },
            ShowcaseOptions.pathStarts,
        )
        assertEquals(
            DrawOrderStrategy.entries.filter { it != DrawOrderStrategy.CUSTOM },
            ShowcaseOptions.drawOrders,
        )
        assertEquals(
            DrawOrderStrategy.entries.filter { it != DrawOrderStrategy.CUSTOM },
            ShowcaseOptions.staggerOrders,
        )
        assertEquals(
            DrawDirection.entries.filter { it != DrawDirection.CUSTOM },
            ShowcaseOptions.fillDirections,
        )
    }

    @Test
    fun panelEditorsAlwaysReceiveNonEmptyPathNames() {
        // A cache miss must not block: callers get the placeholder names immediately and
        // the real names arrive through the snapshot-backed cache (each recomposition
        // re-calls this, so warm results replace the placeholder on the next pass).
        for (pair in listOf(
            "AddCircleOutline" to "AddCircleSolid",
            "SealOutline" to "SealSolid",
        )) {
            val names = PairSuggestions.pathNamesFor(pair.first, pair.second)
            assertTrue("editors need at least one name on every call", names.isNotEmpty())
            assertEquals(names, PairSuggestions.pathNamesFor(pair.first, pair.second))
        }
    }

    @Test
    fun defaultPathNamesCoverTheFallbackContract() {
        assertEquals(3, DefaultPathNames.size)
        assertTrue(DefaultPathNames[0].startsWith("path["))
        assertFalse(DefaultPathNames.any { it.isBlank() })
    }
}
