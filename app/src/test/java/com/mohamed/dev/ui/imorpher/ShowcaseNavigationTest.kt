package com.mohamed.dev.ui.imorpher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseContent
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseScreen
import com.mohamed.dev.ui.imorpher.showcase.ShowcaseState
import com.mohamed.dev.ui.imorpher.showcase.TopicId
import com.mohamed.dev.ui.imorpher.showcase.TopicRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two layers of coverage:
 *
 * 1. Navigation: drives the real [ShowcaseScreen] through home -> topic -> detail for every
 *    registered showcase, pinning the registry ids to the ShowcaseContent dispatcher — a
 *    registry/dispatch drift fails here instead of silently rendering nothing on device.
 * 2. Per-showcase behavior: composes every showcase's content through the same dispatcher
 *    the app uses, inside a scrollable column, and interacts with its controls (toggles,
 *    buttons, pickers, replay) while asserting previews and labels.
 *
 * `performScrollToNode` runs before every interaction: performClick injects touch at the
 * node's current screen coordinates, and a node inside a verticalScroll column exists in
 * the semantics tree even while scrolled off screen, so the touch would silently hit
 * nothing. performScrollToNode itself throws when no node matches, which doubles as the
 * existence assertion.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShowcaseNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------ helpers

    private fun textExists(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun scrollToTop() {
        repeat(12) {
            composeRule.onRoot().performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
    }

    /**
     * Brings a node containing [text] into view: `performScrollToNode` handles non-lazy
     * verticalScroll columns; lazy containers (home/topic lists) need swipe sweeps, which
     * must be bidirectional because the target can sit above the viewport.
     */
    private fun bringTextIntoView(text: String): Boolean {
        runCatching {
            composeRule.onRoot().performScrollToNode(hasText(text, substring = true))
            composeRule.waitForIdle()
        }
        if (textExists(text)) return true
        scrollToTop()
        repeat(14) {
            if (textExists(text)) return true
            composeRule.onRoot().performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        return textExists(text)
    }

    /** Scrolls the outer scrollable until [text] is composed; asserts it at the end. */
    private fun assertTextReachable(text: String) {
        assertTrue("expected '$text' to be reachable", bringTextIntoView(text))
    }

    /** Brings the first node containing [text] into view and clicks it. */
    private fun clickFirstText(text: String) {
        assertTrue("expected '$text' to be clickable", bringTextIntoView(text))
        composeRule.onAllNodesWithText(text, substring = true)[0].performClick()
        composeRule.waitForIdle()
    }

    private fun backToHome() = clickFirstText("All functions")

    private fun backToTopic(topicTitle: String) = clickFirstText("← $topicTitle")

    /** Asserts a node matching [matcher] exists by scrolling it into view. */
    private fun assertReachable(matcher: androidx.compose.ui.test.SemanticsMatcher, what: String) {
        composeRule.onRoot().performScrollToNode(matcher)
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------ layer 1: navigation

    @Test
    fun homeListsEveryRegisteredTopic() {
        composeRule.setContent { ShowcaseScreen() }
        for (topic in TopicRegistry.topics) {
            assertTextReachable(topic.title)
        }
    }

    @Test
    fun everyTopicListsItsShowcasesAndReturns() {
        composeRule.setContent { ShowcaseScreen() }
        for (topic in TopicRegistry.topics) {
            clickFirstText(topic.title)
            for (item in topic.items) {
                assertTextReachable(item.title)
            }
            backToHome()
        }
    }

    @Test
    fun everyShowcaseDetailRendersWithReset() {
        composeRule.setContent { ShowcaseScreen() }
        for (topic in TopicRegistry.topics) {
            clickFirstText(topic.title)
            for (item in topic.items) {
                clickFirstText(item.title)
                assertTextReachable(item.title)
                assertTextReachable("Reset playgrounds")
                backToTopic(topic.title)
            }
            backToHome()
        }
    }

    // ------------------------------------------------------------------ layer 2: per-showcase

    /** Renders one showcase's content directly, exactly as ShowcaseDetailScreen would. */
    private fun renderShowcase(topicId: TopicId, itemId: String, state: ShowcaseState = ShowcaseState()) {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ShowcaseContent(topicId = topicId, itemId = itemId, state = state)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun stateShowcaseToggleUpdatesSelection() {
        renderShowcase(TopicId.MORPH_ICON, "morph-icon-state")
        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitForIdle()
        assertTextReachable("Selected")
    }

    @Test
    fun tintShowcaseRendersTintedPreview() {
        renderShowcase(TopicId.MORPH_ICON, "morph-icon-tint")
        assertReachable(hasContentDescription("Tinted morph icon"), "tinted preview")
        assertReachable(hasText("Indigo → Cyan"), "endpoint label")
    }

    @Test
    fun motionShowcasePreferenceButtonsApply() {
        renderShowcase(TopicId.MORPH_ICON, "morph-icon-motion")
        clickFirstText("Instant")
        assertReachable(hasContentDescription("Motion preference icon"), "motion preview")
    }

    @Test
    fun morphIconPlaygroundIconPickerAppliesSelection() {
        val state = ShowcaseState()
        renderShowcase(TopicId.MORPH_ICON, "morph-icon-playground", state)
        // The picker is one compact tile carrying the current icon's name as its content
        // description; tapping it opens the full-grid dialog (a second root — scope all
        // dialog lookups away from onRoot).
        composeRule.onRoot().performScrollToNode(hasContentDescription("ArrowUp1"))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("ArrowUp1")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("AdditemSolid")[0].performClick()
        composeRule.waitForIdle()
        assertTextReachable("AdditemSolid → ArrowUp2Solid")
        assertReachable(hasText("Copy Kotlin snippet", substring = true), "snippet button")
    }

    @Test
    fun playgroundShowsSingleIconPickerForSingleVectorApis() {
        renderShowcase(TopicId.DRAW_ICON, "draw-icon-playground")
        // DrawIcon animates one vector: exactly one picker (the default icon's tile),
        // no "To icon" slot.
        assertTrue(
            "single-vector APIs must not offer a To icon picker",
            composeRule.onAllNodesWithText("To icon", substring = true).fetchSemanticsNodes().isEmpty(),
        )
        assertReachable(hasContentDescription("ArrowUp1"), "the from picker tile")
        assertReachable(hasText("Stroke width", substring = true), "stroke width control")
    }

    @Test
    fun scrubShowcaseShowsQuantizedProgress() {
        renderShowcase(TopicId.MORPH_VECTOR, "morph-vector-scrub")
        assertReachable(hasText("0%"), "quantized progress label")
        assertReachable(hasContentDescription("Manual morph icon"), "scrub preview")
    }

    @Test
    fun missingPathShowcaseBehaviorButtonsApply() {
        renderShowcase(TopicId.MORPH_VECTOR, "morph-vector-missing")
        clickFirstText("Collapse")
        clickFirstText("Keep")
        assertReachable(hasContentDescription("Missing path morph icon"), "missing-path preview")
    }

    @Test
    fun morphVectorPlaygroundControlsRender() {
        renderShowcase(TopicId.MORPH_VECTOR, "morph-vector-playground")
        assertReachable(hasText("Progress · 0%"), "quantized progress slider label")
        assertReachable(hasText("Copy Kotlin snippet"), "snippet button")
    }

    @Test
    fun keyframeShowcasePlaysTimeline() {
        renderShowcase(TopicId.VECTOR_ANIMATOR, "vector-animator-keyframes")
        clickFirstText("Play")
        assertReachable(hasContentDescription("Keyframe timeline icon"), "keyframe preview")
    }

    @Test
    fun staggerShowcaseReplays() {
        renderShowcase(TopicId.VECTOR_ANIMATOR, "vector-animator-stagger")
        clickFirstText("Replay")
        assertReachable(hasContentDescription("Staggered icon"), "stagger preview")
    }

    @Test
    fun animatorPlaygroundToggleAndLabelStayInSync() {
        val state = ShowcaseState()
        renderShowcase(TopicId.VECTOR_ANIMATOR, "vector-animator-playground", state)
        assertReachable(hasText("reveal: true · color track: false"), "initial toggle label")
        clickFirstText("Toggle reveal")
        assertReachable(hasText("reveal: false · color track: false"), "toggled label")
    }

    @Test
    fun penRevealShowcaseReplays() {
        renderShowcase(TopicId.DRAW_ICON, "draw-icon-pen")
        clickFirstText("Replay")
        assertReachable(hasContentDescription("Pen reveal icon"), "pen preview")
    }

    @Test
    fun drawIconPlaygroundRendersControls() {
        renderShowcase(TopicId.DRAW_ICON, "draw-icon-playground")
        assertReachable(hasText("Stroke width", substring = true), "stroke width control")
        assertReachable(hasText("Mode:", substring = true), "draw mode control")
    }

    @Test
    fun animatorPlaygroundOmitsUnrelatedControls() {
        renderShowcase(TopicId.VECTOR_ANIMATOR, "vector-animator-playground")
        // Manual driver: no duration spec and no second icon.
        assertTrue(
            "VectorAnimator must not offer a duration slider",
            composeRule.onAllNodesWithText("Duration", substring = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "VectorAnimator must not offer a To icon picker",
            composeRule.onAllNodesWithText("To icon", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
