package com.imorpher.vectormorph.core.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

/** `ViewportNormalization.FIT` preserves the target's authored aspect ratio. */
class ViewportNormalizationTest {

    @Test
    fun fitPreservesAspectOnDifferentViewport() {
        // Source 24x24, target 48x24. FIT scales the target by 0.5 on both axes and centres it.
        val from = VectorCompiler.compile(squareVector("from", viewportWidth = 24f, viewportHeight = 24f))
        val to = VectorCompiler.compile(squareVector("to", viewportWidth = 48f, viewportHeight = 24f))

        val plan = MorphPlanner.plan(from, to, MorphConfiguration(viewportNormalization = ViewportNormalization.FIT))
        val bounds = plan.to.paths.single().bounds

        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        assertEquals("aspect ratio is preserved", 2f, width / height, 1e-4f)
        assertEquals("scaled uniformly to 24 wide", 24f, width, 1e-4f)
        assertEquals("letterboxed and vertically centred", 6f, bounds[1], 1e-4f)
        assertEquals(18f, bounds[3], 1e-4f)
    }

    @Test
    fun stretchStillFillsTheViewport() {
        val from = VectorCompiler.compile(squareVector("from", viewportWidth = 24f, viewportHeight = 24f))
        val to = VectorCompiler.compile(squareVector("to", viewportWidth = 48f, viewportHeight = 24f))

        val plan = MorphPlanner.plan(from, to)
        val bounds = plan.to.paths.single().bounds

        assertEquals(24f, bounds[2] - bounds[0], 1e-4f)
        assertEquals(24f, bounds[3] - bounds[1], 1e-4f)
    }

    @Test
    fun fitEqualsStretchForSameAspect() {
        val from = VectorCompiler.compile(squareVector("from", viewportWidth = 24f, viewportHeight = 24f))
        val to = VectorCompiler.compile(squareVector("to", viewportWidth = 48f, viewportHeight = 48f))

        val fitted = MorphPlanner.plan(
            from, to, MorphConfiguration(viewportNormalization = ViewportNormalization.FIT),
        ).to.paths.single().bounds
        val stretched = MorphPlanner.plan(
            from, to, MorphConfiguration(viewportNormalization = ViewportNormalization.STRETCH),
        ).to.paths.single().bounds

        for (i in 0..3) assertEquals(stretched[i], fitted[i], 1e-4f)
    }

    /** Full-viewport rectangle: two contours? No — one closed square path. */
    private fun squareVector(
        name: String,
        viewportWidth: Float,
        viewportHeight: Float,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    ).apply {
        addPath(
            pathData = listOf(
                PathNode.MoveTo(0f, 0f),
                PathNode.LineTo(viewportWidth, 0f),
                PathNode.LineTo(viewportWidth, viewportHeight),
                PathNode.LineTo(0f, viewportHeight),
                PathNode.Close,
            ),
            fill = SolidColor(Color.Black),
            name = "shape",
        )
    }.build()
}
