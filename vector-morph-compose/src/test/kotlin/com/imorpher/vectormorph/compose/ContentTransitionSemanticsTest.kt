package com.imorpher.vectormorph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Accessibility surface: containers expose their description, slots keep their own semantics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ContentTransitionSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun painterContainerExposesItsContentDescription() {
        composeRule.setContent {
            MorphPainter(
                from = ColorPainter(Color.Red),
                to = ColorPainter(Color.Blue),
                progress = 0.5f,
                modifier = Modifier.size(48.dp),
                contentDescription = "progress transition",
            )
        }
        composeRule.onNodeWithContentDescription("progress transition").assertIsDisplayed()
    }

    @Test
    fun slotContainerAndBothSlotsKeepSemantics() {
        composeRule.setContent {
            MorphContent(
                progress = 0.5f,
                modifier = Modifier.size(48.dp),
                contentDescription = "slot transition",
                from = {
                    Box(
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "outgoing slot" },
                    )
                },
                to = {
                    Box(
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "incoming slot" },
                    )
                },
            )
        }
        composeRule.onNodeWithContentDescription("slot transition").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("outgoing slot").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("incoming slot").assertIsDisplayed()
    }
}
