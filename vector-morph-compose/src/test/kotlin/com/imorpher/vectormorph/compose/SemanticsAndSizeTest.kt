package com.imorpher.vectormorph.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Accessibility semantics, explicit sizing, and instant-motion behavior of the composables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SemanticsAndSizeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun clearCache() {
        MorphCaches.clear()
    }

    @Test
    fun morphIconExposesContentDescriptionAsSemantics() {
        composeRule.setContent {
            MorphIcon(
                from = outlined(),
                to = filled(),
                selected = false,
                contentDescription = "Home",
                motionPreference = MotionPreference.INSTANT,
            )
        }

        composeRule.onNodeWithContentDescription("Home").assertExists()
    }

    @Test
    fun morphingIconForwardsContentDescription() {
        composeRule.setContent {
            MorphingIcon(
                target = filled(),
                contentDescription = "Star",
                motionPreference = MotionPreference.INSTANT,
            )
        }

        composeRule.onNodeWithContentDescription("Star").assertExists()
    }

    @Test
    fun morphIconHonoursExplicitWidthAndHeight() {
        composeRule.setContent {
            MorphIcon(
                from = outlined(),
                to = filled(),
                selected = true,
                contentDescription = "Sized",
                motionPreference = MotionPreference.INSTANT,
                width = 16.dp,
                height = 20.dp,
            )
        }

        composeRule.onNodeWithContentDescription("Sized")
            .assertWidthIsEqualTo(16.dp)
            .assertHeightIsEqualTo(20.dp)
    }

    @Test
    fun instantMotionSettlesWithoutAdvancingTheClock() {
        val settled = mutableListOf<Boolean>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MorphIcon(
                from = outlined(),
                to = filled(),
                selected = true,
                contentDescription = "Instant",
                motionPreference = MotionPreference.INSTANT,
                onSettled = { settled.add(it) },
            )
        }

        // One frame is enough for the instant path: no animation duration is consumed.
        composeRule.mainClock.advanceTimeByFrame()

        assertEquals("instant motion must report the settled selection immediately", listOf(true), settled)
    }

    // ------------------------------------------------------------------ fixtures

    private fun outlined(): ImageVector =
        ImageVector.Builder("outlined", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = listOf(
                    PathNode.MoveTo(4f, 4f),
                    PathNode.LineTo(20f, 4f),
                    PathNode.LineTo(20f, 20f),
                    PathNode.LineTo(4f, 20f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                name = "frame",
            )
            .build()

    private fun filled(): ImageVector =
        ImageVector.Builder("filled", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = listOf(
                    PathNode.MoveTo(4f, 4f),
                    PathNode.LineTo(20f, 4f),
                    PathNode.LineTo(20f, 20f),
                    PathNode.LineTo(4f, 20f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                name = "frame",
            )
            .build()
}
