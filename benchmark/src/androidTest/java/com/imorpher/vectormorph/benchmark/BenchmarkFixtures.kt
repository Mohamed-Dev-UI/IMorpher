package com.imorpher.vectormorph.benchmark

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

/** Icon pair shared by the benchmarks: same path count, different silhouettes. */
internal object BenchmarkFixtures {

    /** Rounded square outline with two contours. */
    val outline: ImageVector = ImageVector.Builder("outline", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 2f),
                PathNode.CurveTo(1.8f, 2f, 2f, 4.2f, 2f, 4f),
                PathNode.LineTo(2f, 20f),
                PathNode.CurveTo(2f, 22.2f, 4.2f, 22f, 4f, 22f),
                PathNode.LineTo(20f, 22f),
                PathNode.CurveTo(22.2f, 22f, 22f, 19.8f, 22f, 20f),
                PathNode.LineTo(22f, 4f),
                PathNode.CurveTo(22f, 1.8f, 19.8f, 2f, 20f, 2f),
                PathNode.Close,
            ),
            fill = SolidColor(Color(0xFF536DFE)),
            name = "frame",
        )
        .addPath(
            pathData = listOf(
                PathNode.MoveTo(8f, 8f),
                PathNode.LineTo(16f, 8f),
                PathNode.LineTo(16f, 16f),
                PathNode.LineTo(8f, 16f),
                PathNode.Close,
            ),
            fill = SolidColor(Color(0xFF00C853)),
            name = "core",
        )
        .build()

    /** Circle silhouette with a cut-out centre. */
    val solid: ImageVector = ImageVector.Builder("solid", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = circle(centerX = 12f, centerY = 12f, radius = 10f),
            fill = SolidColor(Color(0xFF536DFE)),
            name = "frame",
        )
        .addPath(
            pathData = circle(centerX = 12f, centerY = 12f, radius = 4f),
            fill = SolidColor(Color(0xFF00C853)),
            name = "core",
        )
        .build()

    /** A closed four-cubic circle, the shape the subdivider benchmark densifies. */
    fun circle(centerX: Float, centerY: Float, radius: Float): List<PathNode> {
        val k = radius * 0.5522848f
        return listOf(
            PathNode.MoveTo(centerX, centerY - radius),
            PathNode.CurveTo(
                centerX + k, centerY - radius,
                centerX + radius, centerY - k,
                centerX + radius, centerY,
            ),
            PathNode.CurveTo(
                centerX + radius, centerY + k,
                centerX + k, centerY + radius,
                centerX, centerY + radius,
            ),
            PathNode.CurveTo(
                centerX - k, centerY + radius,
                centerX - radius, centerY + k,
                centerX - radius, centerY,
            ),
            PathNode.CurveTo(
                centerX - radius, centerY - k,
                centerX - k, centerY - radius,
                centerX, centerY - radius,
            ),
            PathNode.Close,
        )
    }
}
