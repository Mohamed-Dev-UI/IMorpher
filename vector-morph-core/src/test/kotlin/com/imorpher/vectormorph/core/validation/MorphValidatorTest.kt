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
import com.imorpher.vectormorph.core.model.TimingMode
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

    /** A per-path scalar track, with timeline stagger metadata for precedence tests. */
    private fun pathAnimation(
        staggerDelay: Float = 0f,
        staggerOrder: DrawOrderStrategy = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
    ): VectorAnimation = VectorAnimation {
        allPaths { alpha(from = 0f, to = 1f) }
        if (staggerDelay > 0f || staggerOrder != DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT) {
            stagger(staggerDelay, staggerOrder)
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
    fun invalidCustomAnchorValuesAreReportedOnlyWhenTheirConsumerIsActive() {
        val values = mapOf(
            "nan" to Float.NaN,
            "infinite" to Float.POSITIVE_INFINITY,
            "below" to -0.1f,
            "above" to 1.1f,
        )
        val active = MorphValidator.validateConfiguration(
            MorphConfiguration(
                startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.CUSTOM,
                customDrawAnchors = values,
                customStartOffsets = values,
                animation = strokeStart(PathStart.CUSTOM),
            ),
        )
        assertEquals(4, active.count { it.category == "custom-draw-anchor-invalid" })
        assertEquals(4, active.count { it.category == "custom-start-offset-invalid" })

        val inactive = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawAnchors = values, customStartOffsets = values),
        )
        assertTrue("inactive maps are reported as unused, not as active value errors: $inactive",
            inactive.none { it.category == "custom-draw-anchor-invalid" || it.category == "custom-start-offset-invalid" })
    }

    @Test
    fun legacyCustomStartOffsetsWarnWhenNeitherAlignmentNorDrawUsesThem() {
        val inactive = MorphValidator.validateConfiguration(
            MorphConfiguration(customStartOffsets = mapOf("a" to 0.5f)),
        )
        assertTrue("legacy offsets need CUSTOM alignment or a CUSTOM draw start: $inactive",
            flagged(inactive, IssueSeverity.WARNING, "custom-start-offsets-unused"))

        val alignment = MorphValidator.validateConfiguration(
            MorphConfiguration(
                startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.CUSTOM,
                customStartOffsets = mapOf("a" to 0.5f),
            ),
        )
        assertTrue("CUSTOM alignment consumes legacy offsets: $alignment",
            alignment.none { it.category == "custom-start-offsets-unused" })

        val draw = MorphValidator.validateConfiguration(
            MorphConfiguration(
                customStartOffsets = mapOf("a" to 0.5f),
                animation = strokeStart(PathStart.CUSTOM),
            ),
        )
        assertTrue("legacy offsets remain a draw-anchor fallback: $draw",
            draw.none { it.category == "custom-start-offsets-unused" })
    }

@Test
    fun customStartAlignmentsAreGeometryOnlyAndWarnWhenIdle() {
        val inactive = MorphValidator.validateConfiguration(
            MorphConfiguration(customStartAlignments = mapOf("a" to 0.5f)),
        )
        assertTrue("alignments need StartPointStrategy.CUSTOM: $inactive",
            flagged(inactive, IssueSeverity.WARNING, "custom-start-alignments-unused"))
        assertEquals(1, inactive.count { it.category == "custom-start-alignments-unused" })

        // A PathStart.CUSTOM draw reveal is NOT enough: alignments never anchor the pen,
        // that remains the customDrawAnchors/customStartOffsets fallback job.
        val drawOnly = MorphValidator.validateConfiguration(
            MorphConfiguration(
                customStartAlignments = mapOf("a" to 0.5f),
                animation = strokeStart(PathStart.CUSTOM),
            ),
        )
        assertTrue(drawOnly.any { it.category == "custom-start-alignments-unused" })

        val active = MorphValidator.validateConfiguration(
            MorphConfiguration(
                startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.CUSTOM,
                customStartAlignments = mapOf("a" to 0.5f),
            ),
        )
        assertTrue("CUSTOM alignment consumes the new map: $active",
            active.none { it.category == "custom-start-alignments-unused" })
    }

    @Test
    fun conflictingAlignmentMapsAreReportedAndAgreementIsSilent() {
        val conflict = MorphValidator.validateConfiguration(
            MorphConfiguration(
                startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.CUSTOM,
                customStartAlignments = mapOf("a" to 0.25f),
                customStartOffsets = mapOf("a" to 0.75f),
            ),
        )
        assertEquals(1, conflict.count { it.category == "custom-start-alignment-conflict" })

        // Equal values are redundant but not contradictory.
        val agrees = MorphValidator.validateConfiguration(
            MorphConfiguration(
                customStartAlignments = mapOf("a" to 0.5f),
                customStartOffsets = mapOf("a" to 0.5f),
            ),
        )
        assertTrue(agrees.none { it.category == "custom-start-alignment-conflict" })
    }

    @Test
    fun invalidAlignmentValuesAreReportedOnlyWhenCustomStartIsActive() {
        val values = mapOf(
            "nan" to Float.NaN,
            "below" to -0.1f,
            "above" to 1.1f,
        )
        val active = MorphValidator.validateConfiguration(
            MorphConfiguration(
                startPoint = com.imorpher.vectormorph.core.model.StartPointStrategy.CUSTOM,
                customStartAlignments = values,
            ),
        )
        assertEquals(3, active.count { it.category == "custom-start-alignment-invalid" })

        val inactive = MorphValidator.validateConfiguration(MorphConfiguration(customStartAlignments = values))
        assertTrue(inactive.none { it.category == "custom-start-alignment-invalid" })
    }

    @Test
    fun invalidStaggerDelaysAreReportedForConfigurationAndAnimation() {
        val badValues = listOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY)
        for (delay in badValues) {
            val configIssue = MorphValidator.validateConfiguration(MorphConfiguration(staggerDelay = delay))
            assertTrue("invalid configuration stagger $delay: $configIssue",
                flagged(configIssue, IssueSeverity.WARNING, "stagger-delay-invalid"))

            val animationIssue = MorphValidator.validateConfiguration(
                MorphConfiguration.Default,
                animation = VectorAnimation {
                    allPaths { alpha(from = 0f, to = 1f) }
                    stagger(delay, DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT)
                },
            )
            assertTrue("invalid animation stagger $delay: $animationIssue",
                flagged(animationIssue, IssueSeverity.WARNING, "stagger-delay-invalid"))
        }
    }

    @Test
    fun customRevealAndMissingPathModesRequireAnAnimationCallback() {
        val missingStrokeCallback = MorphValidator.validateConfiguration(
            MorphConfiguration(animation = VectorAnimation {
                allPaths { drawProgress(mode = DrawMode.CUSTOM) }
            }),
        )
        assertTrue("CUSTOM stroke reveal needs a callback: $missingStrokeCallback",
            flagged(missingStrokeCallback, IssueSeverity.WARNING, "custom-callback-missing"))

        val missingFillCallback = MorphValidator.validateConfiguration(
            MorphConfiguration(animation = fillLine(FillMode.DIRECTIONAL, DrawDirection.CUSTOM)),
        )
        assertTrue("CUSTOM fill direction needs a callback: $missingFillCallback",
            flagged(missingFillCallback, IssueSeverity.WARNING, "custom-callback-missing"))

        val missingPathCallback = MorphValidator.validateConfiguration(
            MorphConfiguration(missingPath = MissingPathBehavior.CUSTOM),
        )
        assertTrue("CUSTOM missing-path behavior needs a callback when an orphan is rendered: $missingPathCallback",
            flagged(missingPathCallback, IssueSeverity.WARNING, "custom-callback-missing"))

        val configuredCallback = MorphValidator.validateConfiguration(
            MorphConfiguration(animation = VectorAnimation {
                customAnimation { null }
                allPaths { drawProgress(mode = DrawMode.CUSTOM) }
            }),
        )
        assertTrue("a callback is present, even though its output is runtime-dependent: $configuredCallback",
            configuredCallback.none { it.category == "custom-callback-missing" })
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
        val renderOrder = MorphValidator.validateConfiguration(
            MorphConfiguration(
                customDrawOrder = listOf("b", "a"),
                drawOrder = DrawOrderStrategy.CUSTOM,
                keepRenderOrder = false,
            ),
        )
        assertTrue(
            "CUSTOM render ordering consumes the list when render order is enabled: $renderOrder",
            renderOrder.none { it.category == "custom-order-unused" },
        )
        val noRenderConsumer = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawOrder = listOf("b", "a"), drawOrder = DrawOrderStrategy.CUSTOM),
        )
        assertTrue(
            "CUSTOM draw order is not consumed when declaration paint order is retained: $noRenderConsumer",
            flagged(noRenderConsumer, IssueSeverity.WARNING, "custom-order-unused"),
        )
        val timelineConsumer = MorphValidator.validateConfiguration(
            MorphConfiguration(customDrawOrder = listOf("b", "a")),
            animation = pathAnimation(staggerDelay = 0.2f, staggerOrder = DrawOrderStrategy.CUSTOM),
        )
        assertTrue(
            "a CUSTOM timeline stagger consumes the list even when drawOrder is not CUSTOM: $timelineConsumer",
            timelineConsumer.none { it.category == "custom-order-unused" },
        )
    }

    @Test
    fun geometryBudgetAndToleranceConflictsAreReported() {
        val clampedBudget = MorphValidator.validateConfiguration(
            MorphConfiguration(segmentBudget = 1),
        )
        assertTrue(
            "budgets below the planner minimum are raised to two: $clampedBudget",
            flagged(clampedBudget, IssueSeverity.INFO, "segment-budget-clamped"),
        )

        val invalidTolerances = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f)
        for (tolerance in invalidTolerances) {
            val issues = MorphValidator.validateConfiguration(
                MorphConfiguration(segmentBudget = 8, simplificationTolerance = tolerance),
            )
            assertTrue(
                "invalid tolerance $tolerance must report the safe planning fallback: $issues",
                flagged(issues, IssueSeverity.WARNING, "simplification-tolerance-invalid"),
            )
        }

        val unusedTolerance = MorphValidator.validateConfiguration(
            MorphConfiguration(simplificationTolerance = 0.5f),
        )
        assertTrue(
            "a non-default tolerance has no effect without a segment budget: $unusedTolerance",
            flagged(unusedTolerance, IssueSeverity.INFO, "simplification-tolerance-unused"),
        )

        val activeTolerance = MorphValidator.validateConfiguration(
            MorphConfiguration(segmentBudget = 8, simplificationTolerance = 0.5f),
        )
        assertTrue(
            "a valid tolerance is potentially consumed when a segment budget is configured: $activeTolerance",
            activeTolerance.none {
                it.category == "simplification-tolerance-unused" ||
                    it.category == "simplification-tolerance-invalid"
            },
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
        val configDelayWithoutAnimation = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME, staggerDelay = 0.2f),
        )
        assertTrue(
            "a config delay without a path timeline still has no consumer: $configDelayWithoutAnimation",
            flagged(configDelayWithoutAnimation, IssueSeverity.INFO, "stagger-order-without-delay"),
        )
        val configDelay = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME, staggerDelay = 0.2f),
            animation = pathAnimation(),
        )
        assertTrue(
            "a config delay activates path-scoped timeline ordering: $configDelay",
            configDelay.none { it.category == "stagger-order-without-delay" },
        )
        val timelineDelay = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME),
            animation = pathAnimation(staggerDelay = 0.2f),
        )
        assertTrue(
            "a timeline delay activates the config's resolved stagger order: $timelineDelay",
            timelineDelay.none { it.category == "stagger-order-without-delay" },
        )
        val configOrderOverridesTimeline = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME),
            animation = pathAnimation(staggerDelay = 0.2f, staggerOrder = DrawOrderStrategy.CUSTOM),
        )
        assertTrue(
            "the non-default config order overrides the timeline order while inheriting its delay: $configOrderOverridesTimeline",
            configOrderOverridesTimeline.none { it.category == "stagger-order-without-delay" },
        )
        val timingOrder = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME, timing = TimingMode.BY_PATH),
            animation = pathAnimation(),
        )
        assertTrue(
            "BY_PATH timing consumes its rank order without a stagger delay: $timingOrder",
            timingOrder.none { it.category == "stagger-order-without-delay" },
        )
        val groupOnly = MorphValidator.validateConfiguration(
            MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME, timing = TimingMode.BY_PATH),
            animation = VectorAnimation { group("g") { alpha(from = 0f, to = 1f) } },
        )
        assertTrue(
            "group tracks use a shared group clock, not path order ranks: $groupOnly",
            flagged(groupOnly, IssueSeverity.INFO, "stagger-order-without-delay"),
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

    @Test
    fun activeCustomMappingsReportEmptyAndDuplicateSelectors() {
        for (strategy in listOf(PathMatchingStrategy.AUTO, PathMatchingStrategy.CUSTOM)) {
            val issues = MorphValidator.validateConfiguration(
                MorphConfiguration(
                    pathMatching = strategy,
                    customMappings = listOf(
                        PathMapping("", "target"),
                        PathMapping("source", "  "),
                        PathMapping("duplicate-source", "first-target"),
                        PathMapping("duplicate-source", "second-target"),
                        PathMapping("first-source", "duplicate-target"),
                        PathMapping("second-source", "duplicate-target"),
                    ),
                ),
            )
            assertEquals(2, issues.count { it.category == "custom-mapping-name-empty" })
            assertEquals(1, issues.count { it.category == "custom-mapping-source-duplicate" })
            assertEquals(1, issues.count { it.category == "custom-mapping-target-duplicate" })
            assertTrue(issues.all { it.severity == IssueSeverity.WARNING })
        }
    }

    @Test
    fun activeCustomOrderReportsEmptyAndDuplicateSelectors() {
        val issues = MorphValidator.validateConfiguration(
            MorphConfiguration(
                customDrawOrder = listOf("", "  ", "repeat", "repeat"),
                drawOrder = DrawOrderStrategy.CUSTOM,
                keepRenderOrder = false,
            ),
        )

        assertEquals(2, issues.count { it.category == "custom-order-name-empty" })
        assertEquals(1, issues.count { it.category == "custom-order-name-duplicate" })
        assertTrue(issues.all { it.severity == IssueSeverity.WARNING })
    }

    @Test
    fun inactiveMalformedSelectorsOnlyReportUnusedConfiguration() {
        val issues = MorphValidator.validateConfiguration(
            MorphConfiguration(
                pathMatching = PathMatchingStrategy.BY_NAME,
                customMappings = listOf(PathMapping("", ""), PathMapping("", "target")),
                customDrawOrder = listOf("", "repeat", "repeat"),
            ),
        )

        assertTrue(issues.any { it.category == "custom-mappings-unused" })
        assertTrue(issues.any { it.category == "custom-order-unused" })
        assertTrue(issues.none { it.category.startsWith("custom-mapping-") && it.category != "custom-mappings-unused" })
        assertTrue(issues.none { it.category.startsWith("custom-order-name-") })
    }
}
