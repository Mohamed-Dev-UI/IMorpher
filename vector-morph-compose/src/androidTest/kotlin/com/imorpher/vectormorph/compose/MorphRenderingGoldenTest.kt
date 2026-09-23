package com.imorpher.vectormorph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic rendering regression: a hand-built vector is drawn without XML resources. */
class MorphRenderingGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fixedProgressProducesRepeatablePixelsAndPreservesSemantics() {
        val tint = Color(0xFF12A4D9)
        val builder = ImageVector.Builder("golden", 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
                PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
            ),
            fill = SolidColor(tint),
            name = "body",
        )
        val vector = builder.build()

        composeRule.setContent {
            MorphVector(
                from = vector,
                to = vector,
                progress = 0.37f,
                modifier = Modifier,
                contentDescription = "deterministic icon",
                width = 24.dp,
                height = 24.dp,
            )
        }

        val node = composeRule.onNodeWithContentDescription("deterministic icon")
        val first = node.captureToImage().toPixelMap()
        val second = node.captureToImage().toPixelMap()
        val centerX = first.width / 2
        val centerY = first.height / 2

        assertEquals(tint, first[centerX, centerY])
        assertEquals(Color.Transparent, first[1, 1])
        assertTrue((0 until first.height).all { y ->
            (0 until first.width).all { x -> first[x, y] == second[x, y] }
        })
    }
}
