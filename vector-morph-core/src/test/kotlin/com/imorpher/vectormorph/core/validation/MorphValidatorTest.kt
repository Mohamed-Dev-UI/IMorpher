package com.imorpher.vectormorph.core.validation

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.model.MorphConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static analysis of morph plans: issue categories and severities. */
class MorphValidatorTest {

    private fun vector(vararg paths: Pair<String, List<PathNode>>, fill: Brush = SolidColor(Color.Black)): ImageVector {
        val builder = ImageVector.Builder("v", 24.dp, 24.dp, 24f, 24f)
        paths.forEach { (name, data) -> builder.addPath(pathData = data, fill = fill, name = name) }
        return builder.build()
    }

    private fun triangle(offset: Float = 0f) = listOf(
        PathNode.MoveTo(3f + offset, 3f), PathNode.LineTo(21f - offset, 3f),
        PathNode.LineTo(12f, 21f - offset), PathNode.Close,
    )

    private fun multiContour() = listOf(
        PathNode.MoveTo(2f, 2f), PathNode.LineTo(10f, 2f), PathNode.LineTo(10f, 10f),
        PathNode.LineTo(2f, 10f), PathNode.Close,
        PathNode.MoveTo(12f, 12f), PathNode.LineTo(20f, 12f), PathNode.LineTo(20f, 20f),
        PathNode.LineTo(12f, 20f), PathNode.Close,
    )

    @Test
    fun contourCountDifferencesProduceAWarning() {
        val issues = MorphValidator.validate(vector("a" to triangle()), vector("b" to multiContour()))
        assertTrue(issues.any { it.category == "contour-count" && it.severity == com.imorpher.vectormorph.core.plan.IssueSeverity.WARNING })
    }

    @Test
    fun gradientKindMismatchProducesAGradientWarning() {
        val gradientVector = vector(
            "a" to triangle(),
            fill = Brush.linearGradient(
                listOf(Color.Red, Color.Blue),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(24f, 0f),
            ),
        )
        val issues = MorphValidator.validate(gradientVector, vector("b" to triangle()))
        assertTrue(issues.any { it.category == "gradient" && it.message.contains("crossfade") })
    }

    @Test
    fun cleanMorphsProduceNoWarnings() {
        val issues = MorphValidator.validate(vector("a" to triangle()), vector("b" to triangle(1f)))
        assertTrue("only INFO issues allowed: $issues", issues.none { it.severity != com.imorpher.vectormorph.core.plan.IssueSeverity.INFO })
    }

    @Test
    fun viewportDifferencesAreInformational() {
        val toBuilder = ImageVector.Builder("to", 24.dp, 24.dp, 48f, 48f)
        toBuilder.addPath(
            pathData = listOf(
                PathNode.MoveTo(6f, 6f), PathNode.LineTo(42f, 6f), PathNode.LineTo(24f, 42f), PathNode.Close,
            ),
            fill = SolidColor(Color.Black), name = "b",
        )
        val issues = MorphValidator.validate(vector("a" to triangle()), toBuilder.build())
        assertTrue(issues.any { it.category == "viewport" && it.severity == com.imorpher.vectormorph.core.plan.IssueSeverity.INFO })
    }

    @Test
    fun validatePlanFlagsEmptyPairLists() {
        val issues = MorphValidator.validatePlan(
            emptyList(),
            com.imorpher.vectormorph.core.compiler.VectorCompiler.compile(vector()),
            com.imorpher.vectormorph.core.compiler.VectorCompiler.compile(vector()),
        )
        assertEquals(1, issues.size)
        assertEquals("empty", issues.single().category)
        assertEquals(com.imorpher.vectormorph.core.plan.IssueSeverity.WARNING, issues.single().severity)
    }

    @Test
    fun unmatchedPathsAreReportedThroughThePlannerNotTheValidator() {
        // The validator surfaces pair-level issues; unmatched path bookkeeping lives in
        // the plan report (covered by MorphPlannerFallbackTest).
        val issues = MorphValidator.validate(
            vector("only-from" to triangle()),
            vector("only-to" to triangle(1f)),
        )
        assertTrue(issues.none { it.category == "missing" && it.severity == com.imorpher.vectormorph.core.plan.IssueSeverity.ERROR })
    }

    @Test
    fun crossfadePairsAreFlaggedAsFallbacks() {
        // An explicit crossfade fallback renders every pair as a crossfade.
        val issues = MorphValidator.validate(
            vector("a" to triangle()),
            vector("b" to triangle(1f)),
            MorphConfiguration(fallback = com.imorpher.vectormorph.core.model.FallbackStrategy.CROSSFADE),
        )
        assertTrue(issues.any { it.category == "fallback" })
    }
}
