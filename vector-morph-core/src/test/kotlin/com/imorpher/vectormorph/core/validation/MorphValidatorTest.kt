package com.imorpher.vectormorph.core.validation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawMode
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathMapping
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.plan.IssueSeverity
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

    // ------------------------------------------------------------------ validateConfiguration

    /** Truthy when [issues] carries ≥1 issue of exactly [severity] and [category]. */
    private fun flagged(
        issues: List<com.imorpher.vectormorph.core.plan.MorphIssue>,
        severity: IssueSeverity,
        category: String,
    ): Boolean = issues.any { it.severity == severity && it.category == category }

    /** One whole-vector stroke reveal whose draw direction is [direction]. */
    private fun strokeLine(direction: DrawDirection): VectorAnimation = VectorAnimation {
        defaultEasing(LinearEasing)
        allPaths {
            drawProgress(from = 0f, to = 1f, mode = DrawMode.FORWARD, direction = direction)
        }
    }

    /** One whole-vector stroke reveal anchored at [start]. */
    private fun strokeStart(start: PathStart): VectorAnimation = VectorAnimation {
        defaultEasing(LinearEasing)
        allPaths {
            drawProgress(from = 0f, to = 1f, start = start)
        }
    }

    /** One whole-vector fill reveal with [mode]/[direction]. */
    private fun fillLine(mode: FillMode, direction: DrawDirection? = null): VectorAnimation = VectorAnimation {
        defaultEasing(LinearEasing)
        allPaths {
            fill(mode = mode, direction = direction)
        }
    }

    @Test
    fun coherentDefaultConfigIsSilent() {
        val issues = MorphValidator.validateConfiguration(MorphConfiguration.Default)
        assertTrue("default configuration is expected to audit clean: $issues", issues.isEmpty())
    }

    @Test
    fun unusedCustomDrawAnchorsWarnWithoutCustomStart() {
        val anchored = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawAnchors = mapOf("a" to 0.5f)),
        )
        assertTrue(
            "anchors without a CUSTOM-start reveal are dead wires: $anchored",
            flagged(anchored, IssueSeverity.WARNING, "custom-anchors-unused"),
        )
    }

    @Test
    fun customDrawAnchorsStaySilentUnderACustomStartReveal() {
        val issues = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawAnchors = mapOf("a" to 0.5f), animation = strokeStart(PathStart.CUSTOM)),
        )
        assertTrue("a CUSTOM-start reveal consumes the anchors: $issues", issues.isEmpty())
    }

    @Test
    fun unusedCustomDrawOrderWarnsOutsideCustomStrategy() {
        val ordered = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawOrder = listOf("b", "a")),
        )
        assertTrue(
            "customDrawOrder only applies when drawOrder == CUSTOM: $ordered",
            flagged(ordered, IssueSeverity.WARNING, "custom-order-unused"),
        )
        val honored = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawOrder = listOf("b", "a"), drawOrder = DrawOrderStrategy.CUSTOM),
        )
        assertTrue(
            "a CUSTOM draw order consumes the list (the unrelated z-order note may still fire): $honored",
            honored.none { it.category == "custom-order-unused" },
        )
    }

    @Test
    fun crossfadeFallbackDiscardsMissingPathIsWarned() {
        for (fallback in listOf(FallbackStrategy.CROSSFADE, FallbackStrategy.SCALE_CROSSFADE)) {
            val issues = MorphValidator.validateConfiguration(
                MorphConfiguration(missingPath = MissingPathBehavior.DRAW, fallback = fallback),
            )
            assertTrue(
                "$fallback ignores missingPath!=FADE: $issues",
                flagged(issues, IssueSeverity.WARNING, "missing-path-under-crossfade"),
            )
        }
        val untouched = MorphValidator.validateConfiguration(
            MorphConfiguration(missingPath = MissingPathBehavior.DRAW, fallback = FallbackStrategy.BEST_EFFORT),
        )
        assertTrue(
            "fine fallbacks keep missing-path behavior: $untouched",
            untouched.none { it.category == "missing-path-under-crossfade" },
        )
    }

    @Test
    fun throwFallbackScopeIsReminded() {
        val issues = MorphValidator.validateConfiguration(
            MorphConfiguration(fallback = FallbackStrategy.THROW),
        )
        assertTrue(
            "THROW's narrow scope deserves a reminder: $issues",
            flagged(issues, IssueSeverity.INFO, "fallback-throw-scope"),
        )
    }

    @Test
    fun renderOrderNoteWhenRevealOrderUsesStrategy() {
        val ordered = MorphValidator.validateConfiguration(
            MorphConfiguration(drawOrder = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT),
        )
        assertTrue(
            "strategy-ordered reveals under keepRenderOrder deserve the z-order note: $ordered",
            flagged(ordered, IssueSeverity.INFO, "draw-order-not-rendered"),
        )
        val painted = MorphValidator.validateConfiguration(
            MorphConfiguration(drawOrder = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT, keepRenderOrder = false),
        )
        assertTrue(
            "opting out of keepRenderOrder puts the order in charge: $painted",
            painted.none { it.category == "draw-order-not-rendered" },
        )
    }

    @Test
    fun staggerOrderWithoutDelayIsInformational() {
        val ordered = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME),
        )
        assertTrue(
            "stagger order without a delay changes nothing: $ordered",
            flagged(ordered, IssueSeverity.INFO, "stagger-order-without-delay"),
        )
        val timed = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME, staggerDelay = 0.2f),
        )
        assertTrue(
            "a real delay activates the stagger order: $timed",
            timed.none { it.category == "stagger-order-without-delay" },
        )
    }

    @Test
    fun fillDirectionIgnoredForFadeAndRadialReveals() {
        for (mode in listOf(FillMode.FADE, FillMode.RADIAL)) {
            val issues = MorphValidator.validateConfiguration(
                MorphConfiguration(),
                animation = fillLine(mode, DrawDirection.LEFT_TO_RIGHT),
            )
            assertTrue(
                "$mode fills have no travel axis: $issues",
                flagged(issues, IssueSeverity.WARNING, "fill-direction-ignored"),
            )
        }
        val meaningful = MorphValidator.validateConfiguration(
            MorphConfiguration(),
            animation = fillLine(FillMode.DIRECTIONAL, DrawDirection.LEFT_TO_RIGHT),
        )
        assertTrue(
            "directional sweeps consume the direction: $meaningful",
            meaningful.none { it.category == "fill-direction-ignored" },
        )
        val auto = MorphValidator.validateConfiguration(
            MorphConfiguration(),
            animation = fillLine(FillMode.FADE, DrawDirection.AUTO),
        )
        assertTrue(
            "an AUTO direction carries the default policy, not a conflict: $auto",
            auto.none { it.category == "fill-direction-ignored" },
        )
    }

    @Test
    fun sweepDirectionOnStrokeRevealsIsInformational() {
        for (direction in listOf(DrawDirection.CENTER_OUT, DrawDirection.OUTSIDE_IN)) {
            val issues = MorphValidator.validateConfiguration(
                MorphConfiguration.Default,
                animation = strokeLine(direction),
            )
            assertTrue(
                "$direction on a stroke reveal mirrors the runtime warning: $issues",
                flagged(issues, IssueSeverity.INFO, "sweep-direction-on-stroke"),
            )
        }
        val literal = MorphValidator.validateConfiguration(
            MorphConfiguration.Default,
            animation = strokeLine(DrawDirection.LEFT_TO_RIGHT),
        )
        assertTrue(
            "an explicit travel direction is consumed as-is: $literal",
            literal.none { it.category == "sweep-direction-on-stroke" },
        )
    }

    @Test
    fun customMappingsUnusedOutsideAutoOrCustomMatching() {
        val mappings = listOf(PathMapping("a", "b"))
        val byName = MorphValidator.validateConfiguration(
            MorphConfiguration(customMappings = mappings, pathMatching = PathMatchingStrategy.BY_NAME),
        )
        assertTrue(
            "BY_NAME ignores customMappings: $byName",
            flagged(byName, IssueSeverity.INFO, "custom-mappings-unused"),
        )
        val auto = MorphValidator.validateConfiguration(
            MorphConfiguration(customMappings = mappings, pathMatching = PathMatchingStrategy.AUTO),
        )
        assertTrue("AUTO consults the mappings as overrides: $auto", auto.isEmpty())
        val custom = MorphValidator.validateConfiguration(
            MorphConfiguration(customMappings = mappings, pathMatching = PathMatchingStrategy.CUSTOM),
        )
        assertTrue("CUSTOM matching is built on the mappings: $custom", custom.isEmpty())
    }
}
