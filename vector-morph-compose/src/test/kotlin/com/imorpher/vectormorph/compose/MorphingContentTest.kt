package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * State-driven chained content contract ([MorphingContent], mirroring the `MorphingIcon`
 * task 19 guarantees): no intro animation, restart-from-last-settled on retarget, and the
 * [MorphContent] both-endpoints-stay-composed promise during the transition.
 *
 * The tests freeze slot closures per target (`slotFor`) so the settled from slot carries a
 * different content description than the live to slot — that is the observable that tells
 * restart-from-last-settled apart from a mid-flight takeover. Runs freeze the clock
 * (`autoAdvance=false`), commit recompositions with `runOnIdle`, then advance the clock
 * manually.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MorphingContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstCompositionRendersTheTargetWithoutIntroAnimation() {
        val slots = SlotProbes()

        composeRule.setContent {
            MorphingContent(
                target = "a",
                contentDescription = "state content",
                to = slots.slotFor("a"),
            )
        }

        // The target is displayed from the first frame (progress 1): the container
        // description is already exposed and the target slot keeps its semantics. At
        // progress 1 MorphContent keeps both endpoints composed — the from slot is the
        // settled content, which here is the same target closure, so two unmerged nodes.
        composeRule.onNodeWithContentDescription("state content").assertExists()
        composeRule.onAllNodesWithContentDescription("markera").assertCountEquals(2)
    }

    @Test
    fun retargetKeepsBothEndpointsComposedAndSettlesOnTheNewTarget() {
        val slots = SlotProbes()
        var target by mutableStateOf("a")
        var slot by mutableStateOf(slots.slotFor("a"))

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MorphingContent(
                target = target,
                animationSpec = tween(300),
                to = slot,
            )
        }
        composeRule.onAllNodesWithContentDescription("markera").assertCountEquals(2)

        target = "b"
        slot = slots.slotFor("b")
        composeRule.runOnIdle { } // commit the recomposition before moving the clock
        composeRule.mainClock.advanceTimeBy(120) // mid-flight a→b

        // MorphContent guarantees both endpoints stay composed: the settled a closure is
        // still the from slot while b owns the to slot.
        composeRule.onAllNodesWithContentDescription("markera").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("markerb").assertCountEquals(1)

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("markerb").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("markera").assertCountEquals(0)
    }

    @Test
    fun midFlightRetargetRestartsFromTheLastSettledContent() {
        val slots = SlotProbes()
        var target by mutableStateOf("a")
        var slot by mutableStateOf(slots.slotFor("a"))

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MorphingContent(
                target = target,
                animationSpec = tween(300),
                to = slot,
            )
        }

        target = "b"
        slot = slots.slotFor("b")
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(100) // a→b in flight

        target = "c" // retarget mid-flight; must abandon b and restart from the settled a
        slot = slots.slotFor("c")
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(120)

        // The from slot is still the LAST SETTLED content (a): the in-flight b frame never
        // took over the outgoing side, and b's closure never settles.
        composeRule.onAllNodesWithContentDescription("markera").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("markerb").assertCountEquals(0)

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("markerc").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("markerb").assertCountEquals(0)
    }

    @Test
    fun instantPreferenceRendersTheNewTargetDirectly() {
        val slots = SlotProbes()
        var target by mutableStateOf("a")
        var slot by mutableStateOf(slots.slotFor("a"))

        composeRule.setContent {
            MorphingContent(
                target = target,
                motionPreference = MotionPreference.INSTANT,
                to = slot,
            )
        }

        target = "b"
        slot = slots.slotFor("b")
        composeRule.waitForIdle()

        composeRule.onAllNodesWithContentDescription("markerb").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("markera").assertCountEquals(0)
    }

    /** Frozen closures per label, so settled vs. live slot content is distinguishable. */
    private class SlotProbes {
        fun slotFor(label: String): @Composable BoxScope.() -> Unit = {
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "marker$label" },
            )
        }
    }
}
