package com.imorpher.vectormorph.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Group clip masks morph with the icon instead of switching from source to target at the
 * midpoint: at a quarter of the morph the painted band is wider than the source mask and
 * narrower than the target mask.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ClipMorphRenderTest {

    private lateinit var harness: RenderProbeHarness

    @Before
    fun setUp() {
        harness = RenderProbeHarness(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
    }

    @After
    fun clearCache() {
        MorphCaches.clear()
    }

    @Test
    fun clipMaskInterpolatesInsteadOfSwitchingAtTheMidpoint() {
        val from = clipped("from", 0f, 8f)
        val to = clipped("to", 4f, 20f)

        val sourceCount = BitmapAsserts.paintedCount(harness.renderMorph(from, to, 0f))
        val quarterCount = BitmapAsserts.paintedCount(harness.renderMorph(from, to, 0.25f))
        val midpointCount = BitmapAsserts.paintedCount(harness.renderMorph(from, to, 0.5f))
        val targetCount = BitmapAsserts.paintedCount(harness.renderMorph(from, to, 1f))

        assertTrue("fixture must paint something at the source endpoint", sourceCount > 0)
        assertTrue(
            "quarter-progress clip (10 units wide) must be wider than the source mask (8 units): " +
                "$quarterCount vs $sourceCount",
            quarterCount > sourceCount,
        )
        assertTrue(
            "quarter-progress clip must stay narrower than the target mask: $quarterCount vs $targetCount",
            quarterCount < targetCount,
        )
        assertTrue(
            "midpoint clip (12 units) must sit between the endpoints: $midpointCount",
            midpointCount > quarterCount && midpointCount < targetCount,
        )
    }

    private fun clipped(name: String, x0: Float, x1: Float): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addGroup(clipPathData = band(x0, x1))
            addPath(pathData = band(0f, 24f), fill = SolidColor(Color.Black), name = "body")
            clearGroup()
        }.build()

    private fun band(x0: Float, x1: Float): List<PathNode> = listOf(
        PathNode.MoveTo(x0, 0f),
        PathNode.LineTo(x1, 0f),
        PathNode.LineTo(x1, 24f),
        PathNode.LineTo(x0, 24f),
        PathNode.Close,
    )
}
