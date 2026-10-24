package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settle-callback contract (review focus 4) and the interruptible progress controller. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MorphProgressTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val from = square("from", 4f)
    private val to = square("to", 3f)

    @Test
    fun onSettledFiresExactlyOnceWhenAnimationCompletes() {
        val settled = mutableListOf<Boolean>()
        var selected by mutableStateOf(false)
        composeRule.setContent {
            MorphIcon(
                from = from,
                to = to,
                selected = selected,
                onSettled = { settled.add(it) },
                motionPreference = MotionPreference.FULL,
            )
        }
        composeRule.waitForIdle()
        settled.clear()

        selected = true
        composeRule.waitForIdle()

        assertEquals("exactly one settle for one completed transition", listOf(true), settled)
    }

    @Test
    fun midFlightInterruptReportsOnlyTheFinalSettledState() {
        val settled = mutableListOf<Boolean>()
        var selected by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MorphIcon(
                from = from,
                to = to,
                selected = selected,
                onSettled = { settled.add(it) },
                motionPreference = MotionPreference.FULL,
            )
        }
        composeRule.mainClock.advanceTimeBy(50)
        settled.clear()

        selected = false
        composeRule.mainClock.advanceTimeBy(100) // still running toward 0
        selected = true
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals("the interrupted target must not report a settle", listOf(true), settled)
    }

    @Test
    fun rememberMorphAnimationStartsAtTheInitialSelection() {
        var selected by mutableStateOf(true)
        var captured: State<Float>? = null
        composeRule.setContent {
            captured = rememberMorphAnimation(
                selected = selected,
                animationSpec = tween(200),
                motionPreference = MotionPreference.FULL,
            )
        }
        composeRule.mainClock.advanceTimeBy(16)

        assertEquals(1f, captured!!.value, 0f)
    }

    @Test
    fun rememberMorphAnimationRetargetsToZero() {
        var selected by mutableStateOf(true)
        var captured: State<Float>? = null
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            captured = rememberMorphAnimation(
                selected = selected,
                animationSpec = tween(200),
                motionPreference = MotionPreference.FULL,
            )
        }
        composeRule.mainClock.advanceTimeBy(16)

        selected = false
        composeRule.mainClock.advanceTimeBy(500)

        assertTrue("retargeted animation must settle at 0", captured!!.value == 0f)
    }

    @Test
    fun instantPreferenceSkipsTheTransition() {
        val settled = mutableListOf<Boolean>()
        var selected by mutableStateOf(false)
        composeRule.setContent {
            MorphIcon(
                from = from,
                to = to,
                selected = selected,
                onSettled = { settled.add(it) },
                motionPreference = MotionPreference.INSTANT,
            )
        }
        composeRule.waitForIdle()
        settled.clear()

        selected = true
        composeRule.waitForIdle()

        assertEquals(listOf(true), settled)
    }

    private fun square(name: String, inset: Float): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(inset, inset),
                    PathNode.LineTo(24f - inset, inset),
                    PathNode.LineTo(24f - inset, 24f - inset),
                    PathNode.LineTo(inset, 24f - inset),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                name = "shape",
            )
        }.build()
}
