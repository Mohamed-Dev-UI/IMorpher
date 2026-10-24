package com.imorpher.vectormorph.core.plan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.model.MorphConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clip masks morph with the geometry when both sides carry the same number of inherited clip
 * contours; mismatched masks keep the legacy midpoint switch.
 */
class ClipMorphTest {

    @Test
    fun clipPairsAreProducedWhenBothSidesCarryOneClip() {
        val from = VectorCompiler.compile(clipped("from", band(0f, 8f)))
        val to = VectorCompiler.compile(clipped("to", band(4f, 20f)))

        assertTrue("fixture must compile a group clip", from.paths.first().clipContours.isNotEmpty())

        val pair = MorphPlanner.plan(from, to, MorphConfiguration.Default).pairs.single()

        assertEquals(1, pair.clipPairs.size)
        val clip = pair.clipPairs.first()
        assertEquals(
            "aligned clips must interpolate segment-for-segment",
            clip.from.segmentCount,
            clip.to.segmentCount,
        )
    }

    @Test
    fun interpolatedClipGeometrySitsBetweenTheEndpoints() {
        val from = VectorCompiler.compile(clipped("from", band(0f, 8f)))
        val to = VectorCompiler.compile(clipped("to", band(4f, 20f)))
        val pair = MorphPlanner.plan(from, to, MorphConfiguration.Default).pairs.single()

        val atSource = pair.interpolatedClipContours(0f)
        val atMidpoint = pair.interpolatedClipContours(0.5f)
        val atTarget = pair.interpolatedClipContours(1f)

        assertEquals(8f, maxX(atSource), 0.01f)
        assertEquals(20f, maxX(atTarget), 0.01f)
        // Halfway between the source (0..8) and target (4..20) bands.
        assertEquals(14f, maxX(atMidpoint), 0.01f)
        assertEquals(2f, minX(atMidpoint), 0.01f)
    }

    @Test
    fun interpolatedClipGeometryAtQuarterProgressIsNotTheSourceMask() {
        val from = VectorCompiler.compile(clipped("from", band(0f, 8f)))
        val to = VectorCompiler.compile(clipped("to", band(4f, 20f)))
        val pair = MorphPlanner.plan(from, to, MorphConfiguration.Default).pairs.single()

        val quarter = pair.interpolatedClipContours(0.25f)

        // 0..8 → 4..20 at 25%: 1..11. The legacy midpoint switch would still show the 0..8 mask.
        assertEquals(1f, minX(quarter), 0.01f)
        assertEquals(11f, maxX(quarter), 0.01f)
    }

    @Test
    fun mismatchedClipCountsKeepTheLegacySwitch() {
        val from = VectorCompiler.compile(
            ImageVector.Builder("from", 24.dp, 24.dp, 24f, 24f).apply {
                addGroup(clipPathData = band(0f, 8f))
                addPath(pathData = band(0f, 24f), fill = SolidColor(Color.Black), name = "body")
                clearGroup()
            }.build(),
        )
        val to = VectorCompiler.compile(
            ImageVector.Builder("to", 24.dp, 24.dp, 24f, 24f).apply {
                addGroup(clipPathData = band(0f, 8f))
                addGroup(clipPathData = band(12f, 20f))
                addPath(pathData = band(0f, 24f), fill = SolidColor(Color.Black), name = "body")
                clearGroup()
                clearGroup()
            }.build(),
        )

        val pair = MorphPlanner.plan(from, to, MorphConfiguration.Default).pairs.single()

        assertTrue("unequal clip counts must not attempt a partial mask morph", pair.clipPairs.isEmpty())
        assertTrue(pair.interpolatedClipContours(0.5f).isEmpty())
    }

    // ------------------------------------------------------------------ fixtures

    private fun clipped(name: String, clip: List<PathNode>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addGroup(clipPathData = clip)
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

    private fun maxX(contours: List<ContourData>): Float =
        contours.flatMap { it.segments }.maxOf { maxOf(it.x0, it.x1, it.x2, it.x3) }

    private fun minX(contours: List<ContourData>): Float =
        contours.flatMap { it.segments }.minOf { minOf(it.x0, it.x1, it.x2, it.x3) }
}
