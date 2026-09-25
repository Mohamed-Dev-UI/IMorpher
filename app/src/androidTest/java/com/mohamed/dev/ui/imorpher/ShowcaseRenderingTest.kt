package com.mohamed.dev.ui.imorpher

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShowcaseRenderingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stateDrivenHomeIconRendersInBothStates() {
        composeRule.mainClock.autoAdvance = false
        assertIconHasPixels("Home not selected")

        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertIconHasPixels("Home selected")
    }

    @Test
    fun manuallyScrubbedMorphStartsWithAVisiblePlayIcon() {
        composeRule.mainClock.autoAdvance = false
        val node = composeRule.onNodeWithContentDescription("Play to pause morph")
        node.performScrollTo()
        assertTrue("play icon should render visible pixels", iconPixelCount(node) > 0)
    }

    @Test
    fun reducedMotionIconRendersInBothStates() {
        val description = "Reduced motion playback toggle"
        val icon = composeRule.onNodeWithContentDescription(description)
        icon.performScrollTo()
        composeRule.mainClock.autoAdvance = false
        assertIconHasPixels(description)

        composeRule.onAllNodes(isToggleable())[1].performClick()
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()
        assertIconHasPixels(description)
    }

    @Test
    fun penRevealDrawsAnIntermediateFrameAndFinishes() {
        val icon = composeRule.onNodeWithContentDescription("Spark drawing animation")
        icon.performScrollTo()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Replay").performClick()
        // The one-shot autoplay may already have finished while the clock was still
        // advancing during scroll; tick one frame so the replay's first frame renders
        // before the baseline capture.
        composeRule.mainClock.advanceTimeBy(16)
        composeRule.waitForIdle()
        val initialCount = iconPixelCount(icon)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        val middleCount = iconPixelCount(icon)
        assertTrue(
            "pen reveal should draw progressively (initial=$initialCount middle=$middleCount clock=${composeRule.mainClock.currentTime})",
            middleCount > initialCount,
        )

        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()
        val finalCount = iconPixelCount(icon)
        assertTrue(
            "pen reveal should finish the icon (middle=$middleCount final=$finalCount)",
            finalCount > middleCount,
        )
    }

    @Test
    fun timelinePlayButtonRunsTheKeyframesAndGroupReveal() {
        // The Play button drives the playhead: give the 1.5s tween generous headroom and
        // assert the progress readout reaches 100% (capture-free, no frozen-clock races).
        // Scroll and click BEFORE freezing the clock — waitForIdle inside a frozen clock
        // never settles during scroll-driven recomposition.
        composeRule.onNodeWithText("Play timeline").performScrollTo()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Play timeline").performClick()
        composeRule.mainClock.advanceTimeBy(3500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("100%").assertExists()

        // The showcase definition's choreography: rays reveal as a synchronized group,
        // the group rotates, and the core ends as a gradient fill.
        val prepared = com.imorpher.vectormorph.core.compiler.VectorCompiler.compile(ShowcaseVectors.LayeredSpark)
        val plan = com.imorpher.vectormorph.core.plan.MorphPlanner.plan(prepared, prepared)
        val evaluator = com.imorpher.vectormorph.core.animation.TimelineEvaluator(
            LayeredTimelineAnimation,
            plan,
            com.imorpher.vectormorph.core.model.ColorInterpolationSpace.SRGB,
            timingMode = com.imorpher.vectormorph.core.model.TimingMode.BY_PATH_LENGTH,
        )
        val frame = com.imorpher.vectormorph.core.animation.EvaluatedFrame(plan)

        evaluator.evaluate(0.5f, frame)
        val midRays = frame.pathProps[0].groupProps.getValue("rays")
        assertTrue(
            "rays must be mostly revealed at mid-play: ${midRays.props[com.imorpher.vectormorph.core.animation.PropKey.DRAW_PROGRESS.ordinal]}",
            midRays.props[com.imorpher.vectormorph.core.animation.PropKey.DRAW_PROGRESS.ordinal] > 0.5f,
        )
        assertTrue(midRays.props[com.imorpher.vectormorph.core.animation.PropKey.STROKE_WIDTH.ordinal] > 1f)

        evaluator.evaluate(1f, frame)
        val core = frame.pathProps[8]
        assertEquals(1f, core.props[com.imorpher.vectormorph.core.animation.PropKey.FILL_PROGRESS.ordinal], 1e-3f)
        assertTrue(
            "core must end with the interpolated gradient brush",
            core.brushMode == com.imorpher.vectormorph.core.animation.PathFrameValues.BRUSH_INTERPOLATED &&
                core.primaryBrush != null,
        )
        assertEquals(0f, frame.vectorProps[com.imorpher.vectormorph.core.animation.PropKey.ROTATION_DEG.ordinal], 1e-3f)
    }

    private fun assertIconHasPixels(description: String) {
        val count = iconPixelCount(composeRule.onNodeWithContentDescription(description))
        assertTrue("$description should draw visible pixels", count > 0)
    }

    private fun iconPixelCount(node: androidx.compose.ui.test.SemanticsNodeInteraction): Int {
        val pixels = node.captureToImage().toPixelMap()
        val background = pixels[1, 1]
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (abs(color.red - background.red) > 0.04f ||
                abs(color.green - background.green) > 0.04f ||
                abs(color.blue - background.blue) > 0.04f
            ) {
                count++
            }
        }
        return count
    }
}
