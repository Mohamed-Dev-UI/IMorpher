package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.Rule
import org.junit.Test

/** Temporary diagnostic for the retarget failure. */
class RetargetProbeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun probeRetargetPixelSequence() {
        composeRule.mainClock.autoAdvance = false
        val selected = mutableStateOf(false)
        val from = solidVector("from", Color.Red)
        val to = solidVector("to", Color.Blue)

        composeRule.setContent {
            MorphIcon(
                from = from,
                to = to,
                selected = selected.value,
                animationSpec = tween(300),
                contentDescription = "animated icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()
        printPixel("t0 selected=false", selected.value)
        composeRule.runOnIdle { selected.value = true }
        composeRule.mainClock.advanceTimeBy(120)
        composeRule.waitForIdle()
        printPixel("t1 after +120ms (moving to blue)", selected.value)
        composeRule.runOnIdle { selected.value = false }
        composeRule.waitForIdle()
        printPixel("t2 just after retarget to red", selected.value)
        for (step in intArrayOf(20, 40, 60, 80, 120, 160, 200, 300)) {
            composeRule.mainClock.advanceTimeBy(step.toLong())
            composeRule.waitForIdle()
            printPixel("t+" + step + "ms after retarget", selected.value)
        }
    }

    private fun printPixel(label: String, sel: Boolean) {
        val pixels = composeRule.onNodeWithContentDescription("animated icon").captureToImage().toPixelMap()
        val c = pixels[pixels.width / 2, pixels.height / 2]
        println("RETARGET $label pixel=(${fmt(c.red)},${fmt(c.green)},${fmt(c.blue)},${fmt(c.alpha)}) sel=$sel")
    }

    private fun fmt(v: Float) = String.format("%.3f", v)
    private fun solidVector(name: String, color: Color): androidx.compose.ui.graphics.vector.ImageVector {
        val builder = androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                androidx.compose.ui.graphics.vector.PathNode.MoveTo(4f, 4f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(20f, 4f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(20f, 20f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(4f, 20f),
                androidx.compose.ui.graphics.vector.PathNode.Close,
            ),
            fill = androidx.compose.ui.graphics.SolidColor(color),
            name = "body",
        )
        return builder.build()
    }
}
