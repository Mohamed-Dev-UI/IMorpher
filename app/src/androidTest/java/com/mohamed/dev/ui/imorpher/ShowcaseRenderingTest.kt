package com.mohamed.dev.ui.imorpher

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Device-side rendering checks against the live showcase UI. The heavy per-pair and
 * per-parameter matrices run as JVM pixel tests (see vector-morph-compose and the app's
 * unit tests); these cover the composables inside a real activity.
 */
class ShowcaseRenderingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manuallyScrubbedMorphStartsWithAVisibleIcon() {
        val node = composeRule.onNodeWithContentDescription("Manual morph icon")
        node.performScrollTo()
        composeRule.waitForIdle()
        assertTrue("manual morph icon should render visible pixels", iconPixelCount(node) > 0)
    }

    @Test
    fun penRevealDrawsAnIntermediateFrameAndFinishes() {
        val icon = composeRule.onNodeWithContentDescription("Pen reveal icon")
        icon.performScrollTo()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Replay").performClick()
        // The one-shot autoplay may already have finished while the clock was still
        // advancing during the scroll; tick one frame so the replay's first frame renders
        // before the baseline capture.
        composeRule.mainClock.advanceTimeBy(16)
        composeRule.waitForIdle()
        val initialCount = iconPixelCount(icon)

        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        val middleCount = iconPixelCount(icon)
        assertTrue(
            "pen reveal should draw progressively (initial=$initialCount middle=$middleCount clock=${composeRule.mainClock.currentTime})",
            middleCount > initialCount,
        )

        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        val finalCount = iconPixelCount(icon)
        assertTrue(
            "pen reveal should finish the icon (middle=$middleCount final=$finalCount)",
            finalCount > middleCount,
        )
    }

    @Test
    fun keyframeTimelinePlayReachesTheEnd() {
        // The Play button drives the playhead through the 1.5s tween; give it generous
        // headroom and assert the progress readout reaches 100% (capture-free).
        composeRule.onNodeWithText("Play").performScrollTo()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Play").performClick()
        composeRule.mainClock.advanceTimeBy(3500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("100%").assertExists()
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
