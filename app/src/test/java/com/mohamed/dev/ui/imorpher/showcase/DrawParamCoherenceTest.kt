package com.mohamed.dev.ui.imorpher.showcase

import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.reveal.DirectionMath
import com.imorpher.vectormorph.core.reveal.DrawResolutionWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Start/direction coherence coverage for the showcase: the `PathStart.AUTO` default, the
 * conflict diagnostics behind the picker hint, and the persistence round-trips of the new
 * sentinel (and of blobs saved before it). Robolectric so org.json, used by persistence,
 * has a real implementation — same harness as `ShowcaseStateTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawParamCoherenceTest {

    @Test
    fun defaultPathStartIsAuto() {
        assertEquals(PathStart.AUTO, ShowcaseState().pathStart)
    }

    @Test
    fun autoPathStartSurvivesPersistenceRoundTrip() {
        val original = ShowcaseState()
        assertEquals(PathStart.AUTO, original.pathStart)
        val restored = ShowcaseState()
        assertTrue(
            ShowcaseStatePersistence.applyJsonForTest(
                ShowcaseStatePersistence.toJsonForTest(original),
                restored,
            ),
        )
        assertEquals(PathStart.AUTO, restored.pathStart)
    }

    @Test
    fun legacyPersistedPathStartStillLoads() {
        // Blobs saved before the AUTO default carry START; parseEnum must keep honoring it.
        val legacy = ShowcaseState().apply { pathStart = PathStart.START }
        val restored = ShowcaseState()
        assertTrue(
            ShowcaseStatePersistence.applyJsonForTest(
                ShowcaseStatePersistence.toJsonForTest(legacy),
                restored,
            ),
        )
        assertEquals(PathStart.START, restored.pathStart)
    }

    @Test
    fun conflictingComboReportsWarnings() {
        val state = ShowcaseState().apply {
            pathStart = PathStart.TOP
            drawDirection = DrawDirection.LEFT_TO_RIGHT
        }
        assertTrue(
            DrawResolutionWarning.START_OVERRIDES_DIRECTION in state.startDirectionWarnings(),
        )
    }

    @Test
    fun coherentDefaultComboIsSilent() {
        // AUTO start derives the anchor from the direction; nothing contradicts.
        assertTrue(ShowcaseState().startDirectionWarnings().isEmpty())
    }

    @Test
    fun autoDerivesAnchorFromDirectionWithoutWarnings() {
        val state = ShowcaseState().apply { drawDirection = DrawDirection.BOTTOM_TO_TOP }
        val resolved = DirectionMath.resolve(state.pathStart, state.drawDirection, state.drawMode)
        assertEquals(PathStart.BOTTOM, resolved.anchor)
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun radialModeSilencesAnchorWarnings() {
        // RADIAL reveals render through a centroid-anchored clip: the anchor is unused,
        // so start/direction coherence warnings would be noise.
        val state = ShowcaseState().apply {
            pathStart = PathStart.TOP
            drawDirection = DrawDirection.LEFT_TO_RIGHT
            drawMode = DrawMode.RADIAL
        }
        assertTrue(state.startDirectionWarnings().isEmpty())
    }

    @Test
    fun outsideInWarnsAnchorIgnored() {
        val state = ShowcaseState().apply {
            pathStart = PathStart.CENTER
            drawMode = DrawMode.OUTSIDE_IN
        }
        assertTrue(DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE in state.startDirectionWarnings())
    }

    @Test
    fun sweepOnlyDirectionWarnsOnStroke() {
        val state = ShowcaseState().apply { drawDirection = DrawDirection.CENTER_OUT }
        assertTrue(DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE in state.startDirectionWarnings())
    }
}
