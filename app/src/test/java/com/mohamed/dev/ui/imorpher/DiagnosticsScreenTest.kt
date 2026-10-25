package com.mohamed.dev.ui.imorpher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseContent
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.TopicId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Diagnostics screens drive asynchronous engine work (planMorph, caches, codec), so
 * instead of the click-and-assert shape of [ShowcaseNavigationTest] they wait for the
 * computed texts to appear after composing through the real dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DiagnosticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun textExists(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Renders one Diagnostics showcase's content, exactly as ShowcaseDetailScreen would. */
    private fun renderShowcase(itemId: String, state: ShowcaseState = ShowcaseState()) {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ShowcaseContent(topicId = TopicId.DIAGNOSTICS, itemId = itemId, state = state)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun planReportShowsPairCountsAndScore() {
        renderShowcase("diagnostics-plan-report")
        composeRule.waitUntil(20_000) { textExists("Path pairs") }
        composeRule.waitUntil(20_000) { textExists("Score") }
        composeRule.waitUntil(20_000) { textExists("Morph ") }
        composeRule.waitUntil(20_000) { textExists("No fallback") || textExists("Fallback") }
    }

    @Test
    fun cachesWarmAndTrimUpdateNumbers() {
        renderShowcase("diagnostics-caches")
        composeRule.waitUntil(10_000) { textExists("Cache entries") }
        composeRule.onAllNodesWithText("Warm the cache")[0].performClick()
        composeRule.waitUntil(20_000) { textExists("Cold plan") }
        composeRule.onAllNodesWithText("Trim softly")[0].performClick()
        composeRule.waitForIdle()
        assertTrue("cache numbers should still display after trim", textExists("Cache entries"))
    }

    @Test
    fun codecRoundTripReplaysDecodedTimeline() {
        renderShowcase("diagnostics-codec")
        composeRule.waitUntil(20_000) { textExists("Encoded") }
        composeRule.waitUntil(20_000) { textExists("Decoded warnings") }
        composeRule.onRoot().performScrollToNode(hasContentDescription("Decoded timeline icon"))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Replay")[0].performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun customGeometryShowcaseReplays() {
        renderShowcase("diagnostics-custom")
        composeRule.onRoot().performScrollToNode(hasContentDescription("Custom geometry icon"))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Replay")[0].performClick()
        composeRule.waitForIdle()
    }
}
