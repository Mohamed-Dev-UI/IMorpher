package com.imorpher.vectormorph.core.validation

import com.imorpher.vectormorph.core.animation.AnimationTarget
import com.imorpher.vectormorph.core.animation.DrawConfig
import com.imorpher.vectormorph.core.animation.FillRevealConfig
import com.imorpher.vectormorph.core.animation.PropKey
import com.imorpher.vectormorph.core.animation.VectorAnimation
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.DrawOrderStrategy
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.FillMode
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.TimingMode
import com.imorpher.vectormorph.core.plan.IssueSeverity
import com.imorpher.vectormorph.core.plan.MorphIssue
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.PairKind
import com.imorpher.vectormorph.core.plan.PathPairPlan

/**
 * Static analysis of a morph *before* it runs: identifies incompatible paths, unmatched
 * paths, winding problems, start-point drift, unsupported features, gradient/transform
 * mismatches, and likely visual artifacts. Exposed through [validate] for IDE/tooling use
 * and through [MorphPlan.report] at runtime.
 */
object MorphValidator {

    /** Validates a raw pair of ImageVectors (compiles them first). */
    fun validate(
        from: androidx.compose.ui.graphics.vector.ImageVector,
        to: androidx.compose.ui.graphics.vector.ImageVector,
        config: com.imorpher.vectormorph.core.model.MorphConfiguration = com.imorpher.vectormorph.core.model.MorphConfiguration.Default,
    ): List<MorphIssue> {
        val compiledFrom = com.imorpher.vectormorph.core.compiler.VectorCompiler.compile(from)
        val compiledTo = com.imorpher.vectormorph.core.compiler.VectorCompiler.compile(to)
        val plan = com.imorpher.vectormorph.core.plan.MorphPlanner.plan(compiledFrom, compiledTo, config)
        return plan.report.issues
    }

    /**
     * Static audit of configuration/timeline parameter conflicts: knobs that render
     * correctly but silently do nothing (dead wires), contradict each other, or carry
     * narrow semantics easy to miss. Pure — no vectors are compiled, nothing throws — so
     * callers may run it on any derived configuration at any time. The audit complements
     * [validatePlan] (geometry diagnosis): plan-level findings still consult the shape
     * data, these consult the knobs alone.
     *
     * [animation] defaults to [MorphConfiguration.animation] so a single call audits
     * map-level conflicts AND any embedded reveal payload; pass `null` explicitly to
     * audit only the maps.
     *
     * Categories follow [validatePlan]'s lowercase style. Selector integrity warnings are
     * `custom-mapping-name-empty`, `custom-mapping-source-duplicate`,
     * `custom-mapping-target-duplicate`, `custom-order-name-empty`, and
     * `custom-order-name-duplicate`; endpoint-aware selector findings are added to the plan
     * report by [validateSelectorReferences]. Other WARNING categories are `custom-anchors-unused`,
     * `custom-start-offsets-unused`, `custom-draw-anchor-invalid`, `custom-start-offset-invalid`,
     * `custom-start-alignments-unused`, `custom-start-alignment-invalid`, `custom-start-alignment-conflict`,
     * `stagger-delay-invalid`, `custom-callback-missing`, `custom-order-unused`,
     * `missing-path-under-crossfade`, `fill-direction-ignored`, and `simplification-tolerance-invalid`;
     * INFO categories are `fallback-throw-scope`,
     * `draw-order-not-rendered`, `stagger-order-without-delay` (legacy category name; also covers
     * inactive BY_PATH ordering), `segment-budget-clamped`, `simplification-tolerance-unused`,
     * `sweep-direction-on-stroke`, and `custom-mappings-unused`.
     */
    @Suppress("DEPRECATION") // audits the legacy customStartOffsets alignment and anchor fallback
    fun validateConfiguration(
        config: MorphConfiguration,
        animation: VectorAnimation? = config.animation,
    ): List<MorphIssue> {
        val issues = ArrayList<MorphIssue>()

        // (prop, reveal) pairs the evaluator will consume — payload shapes only, no vectors.
        val reveals = animation?.tracks?.flatMap { track -> track.segments.map { track.prop to it.reveal } }.orEmpty()
        val hasCustomStart = reveals.any { (prop, reveal) ->
            prop === PropKey.DRAW_PROGRESS && reveal is DrawConfig && reveal.start == PathStart.CUSTOM
        }
        // Mirror PlanRemember's field-by-field resolution: a non-default config order
        // overrides the timeline order even when its delay is inherited from the timeline.
        val orderResolution = resolveCustomOrder(config, animation)
        val customOrderIsConsumed = orderResolution.customOrderIsConsumed

        if (config.customDrawAnchors.isNotEmpty() && !hasCustomStart) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-anchors-unused", emptyList(),
                    "customDrawAnchors is never consulted: no reveal segment requests PathStart.CUSTOM",
                ),
            )
        }

        val customOffsetsHaveConsumer = hasCustomStart || config.startPoint == StartPointStrategy.CUSTOM
        if (config.customStartOffsets.isNotEmpty() && !customOffsetsHaveConsumer) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-start-offsets-unused", emptyList(),
                    "customStartOffsets has no active consumer: it needs StartPointStrategy.CUSTOM " +
                        "for geometry alignment or a PathStart.CUSTOM draw reveal as a legacy anchor fallback",
                ),
            )
        }

        if (hasCustomStart) {
            config.customDrawAnchors.forEach { (name, value) ->
                if (!value.isFinite() || value !in 0f..1f) {
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-draw-anchor-invalid", listOf(name),
                            "customDrawAnchors['$name'] must be finite and in 0..1; finite out-of-range values are clamped, but NaN is not a valid anchor",
                        ),
                    )
                }
            }
        }
        if (customOffsetsHaveConsumer) {
            config.customStartOffsets.forEach { (name, value) ->
                if (!value.isFinite() || value !in 0f..1f) {
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-start-offset-invalid", listOf(name),
                            "customStartOffsets['$name'] must be finite and in 0..1; finite out-of-range values are clamped, but NaN is not a valid offset",
                        ),
                    )
                }
            }
        }

        // The modern alignment home: geometric alignment only, never a draw anchor — its
        // sole consumer is StartPointStrategy.CUSTOM.
        if (config.customStartAlignments.isNotEmpty() && config.startPoint != StartPointStrategy.CUSTOM) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-start-alignments-unused", emptyList(),
                    "customStartAlignments has no active consumer: it only moves geometry under StartPointStrategy.CUSTOM",
                ),
            )
        }
        if (config.startPoint == StartPointStrategy.CUSTOM) {
            config.customStartAlignments.forEach { (name, value) ->
                if (!value.isFinite() || value !in 0f..1f) {
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-start-alignment-invalid", listOf(name),
                            "customStartAlignments['$name'] must be finite and in 0..1; finite out-of-range values are clamped, but NaN is not a valid offset",
                        ),
                    )
                }
            }
        }
        config.customStartAlignments.forEach { (name, value) ->
            val legacy = config.customStartOffsets[name]
            if (legacy != null && legacy != value) {
                issues.add(
                    MorphIssue(
                        IssueSeverity.WARNING, "custom-start-alignment-conflict", listOf(name),
                        "customStartAlignments['$name']=$value and customStartOffsets['$name']=$legacy disagree; " +
                            "alignment reads customStartAlignments first, customStartOffsets keeps the legacy fallback",
                    ),
                )
            }
        }

        val invalidConfigStagger = !config.staggerDelay.isFinite() || config.staggerDelay < 0f
        val invalidAnimationStagger = animation?.staggerDelay?.let { !it.isFinite() || it < 0f } == true
        if (invalidConfigStagger || invalidAnimationStagger) {
            val invalidSources = buildList {
                if (invalidConfigStagger) add("MorphConfiguration.staggerDelay=${config.staggerDelay}")
                if (invalidAnimationStagger) add("VectorAnimation.staggerDelay=${animation?.staggerDelay}")
            }
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "stagger-delay-invalid", emptyList(),
                    "stagger delays must be finite and non-negative; invalid values can disable or corrupt the stagger schedule: ${invalidSources.joinToString()}",
                ),
            )
        }

        val needsCustomStrokeGeometry = reveals.any { (prop, reveal) ->
            prop === PropKey.DRAW_PROGRESS && reveal is DrawConfig &&
                (reveal.mode == com.imorpher.vectormorph.core.model.DrawMode.CUSTOM ||
                    reveal.direction == DrawDirection.CUSTOM)
        }
        val needsCustomFillGeometry = reveals.any { (prop, reveal) ->
            prop === PropKey.FILL_PROGRESS && reveal is FillRevealConfig &&
                reveal.fillDirection == DrawDirection.CUSTOM
        }
        val needsCustomMissingPath = config.missingPath == MissingPathBehavior.CUSTOM &&
            config.fallback != FallbackStrategy.CROSSFADE &&
            config.fallback != FallbackStrategy.SCALE_CROSSFADE
        if (animation?.customizer == null &&
            (needsCustomStrokeGeometry || needsCustomFillGeometry || needsCustomMissingPath)
        ) {
            val requirements = buildList {
                if (needsCustomStrokeGeometry) add("custom stroke reveal geometry")
                if (needsCustomFillGeometry) add("custom fill reveal geometry")
                if (needsCustomMissingPath) add("custom missing-path geometry when an orphan path is rendered")
            }
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-callback-missing", emptyList(),
                    "${requirements.joinToString(" and ")} requires a CustomVectorAnimation callback",
                ),
            )
        }

        if (config.customDrawOrder.isNotEmpty() && !customOrderIsConsumed) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-order-unused", emptyList(),
                    "customDrawOrder has no active consumer: render ordering needs " +
                        "drawOrder=CUSTOM with keepRenderOrder=false, or path-scoped tracks with " +
                        "resolved staggerOrder=CUSTOM and a positive delay or timing=BY_PATH",
                ),
            )
        }

        if (config.missingPath != MissingPathBehavior.FADE &&
            (config.fallback == FallbackStrategy.CROSSFADE || config.fallback == FallbackStrategy.SCALE_CROSSFADE)
        ) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "missing-path-under-crossfade", emptyList(),
                    "FallbackStrategy ${config.fallback} crossfades every pair; " +
                        "missingPath ${config.missingPath} is ignored",
                ),
            )
        }

        if (config.fallback == FallbackStrategy.THROW) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "fallback-throw-scope", emptyList(),
                    "FallbackStrategy.THROW throws only when one side has no paths at all; every other " +
                        "artifact degrades silently — check the plan report",
                ),
            )
        }

        if (config.drawOrder != DrawOrderStrategy.BY_INDEX && config.keepRenderOrder) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "draw-order-not-rendered", emptyList(),
                    "drawOrder ${config.drawOrder} does not change z-order while keepRenderOrder=true; " +
                        "it does not control stagger timing (staggerOrder does)",
                ),
            )
        }

        if (orderResolution.effectiveStaggerOrder != MorphConfiguration.Default.staggerOrder &&
            !orderResolution.pathOrderIsConsumed
        ) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "stagger-order-without-delay", emptyList(),
                    "resolved staggerOrder ${orderResolution.effectiveStaggerOrder} has no active path-scoped consumer: " +
                        "a resolved stagger delay or TimingMode.BY_PATH is required",
                ),
            )
        }

        // FADE/RADIAL fills have no travel axis, so a steered direction is dead ballast.
        val steeredFadeOrRadial = reveals.any { (prop, reveal) ->
            prop === PropKey.FILL_PROGRESS && reveal is FillRevealConfig &&
                (reveal.fillMode == FillMode.FADE || reveal.fillMode == FillMode.RADIAL) &&
                reveal.fillDirection != null && reveal.fillDirection != DrawDirection.AUTO
        }
        if (steeredFadeOrRadial) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "fill-direction-ignored", emptyList(),
                    "a FADE/RADIAL fill reveal declares a direction its mode ignores (no travel axis)",
                ),
            )
        }

        // Sweep "directions" on stroke reveals: DirectionMath warns per reveal at runtime;
        // the audit reports the combo once for the whole timeline.
        val sweepDirectionOnStroke = reveals.any { (prop, reveal) ->
            prop === PropKey.DRAW_PROGRESS && reveal is DrawConfig &&
                (reveal.direction == DrawDirection.CENTER_OUT || reveal.direction == DrawDirection.OUTSIDE_IN)
        }
        if (sweepDirectionOnStroke) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "sweep-direction-on-stroke", emptyList(),
                    "CENTER_OUT/OUTSIDE_IN carry no stroke-travel meaning (fill-sweep start-angle selectors)",
                ),
            )
        }

        val customMappingsAreConsumed = config.pathMatching == PathMatchingStrategy.AUTO ||
            config.pathMatching == PathMatchingStrategy.CUSTOM
        if (config.customMappings.isNotEmpty() && !customMappingsAreConsumed) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "custom-mappings-unused", emptyList(),
                    "customMappings are ignored: pathMatching is ${config.pathMatching}; only AUTO " +
                        "(as overrides) and CUSTOM consult them",
                ),
            )
        }

        if (customMappingsAreConsumed) {
            config.customMappings.forEachIndexed { index, mapping ->
                if (mapping.source.isBlank() || mapping.target.isBlank()) {
                    val emptySides = buildList {
                        if (mapping.source.isBlank()) add("source")
                        if (mapping.target.isBlank()) add("target")
                    }.joinToString(" and ")
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-mapping-name-empty",
                            listOf(mapping.source, mapping.target).filter { it.isNotBlank() },
                            "customMappings[$index] has a blank $emptySides name; this mapping cannot resolve reliably",
                        ),
                    )
                }
            }
            config.customMappings.asSequence().map { it.source }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { source ->
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-mapping-source-duplicate", listOf(source),
                            "customMappings repeats source '$source'; the first mapping for this source wins and later entries are shadowed",
                        ),
                    )
                }
            config.customMappings.asSequence().map { it.target }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { target ->
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-mapping-target-duplicate", listOf(target),
                            "multiple customMappings claim target '$target'; the first source to claim it wins and later sources remain unmatched",
                        ),
                    )
                }
        }

        if (customOrderIsConsumed) {
            config.customDrawOrder.forEachIndexed { index, name ->
                if (name.isBlank()) {
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-order-name-empty", emptyList(),
                            "customDrawOrder[$index] is blank and cannot identify a path",
                        ),
                    )
                }
            }
            config.customDrawOrder.asSequence().filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { name ->
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-order-name-duplicate", listOf(name),
                            "customDrawOrder repeats '$name'; the rank map uses its last occurrence",
                        ),
                    )
                }
        }

        if (config.segmentBudget != null && config.segmentBudget < 2) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "segment-budget-clamped", emptyList(),
                    "segmentBudget ${config.segmentBudget} is below the planner minimum of 2 and is raised to 2",
                ),
            )
        }

        if (!config.simplificationTolerance.isFinite() || config.simplificationTolerance < 0f) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "simplification-tolerance-invalid", emptyList(),
                    "simplificationTolerance must be finite and non-negative; planning treats invalid values as 0",
                ),
            )
        } else if (config.segmentBudget == null && config.simplificationTolerance != MorphConfiguration.Default.simplificationTolerance) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "simplification-tolerance-unused", emptyList(),
                    "simplificationTolerance has no effect without segmentBudget",
                ),
            )
        }

        return issues
    }

    private data class CustomOrderResolution(
        val effectiveStaggerOrder: DrawOrderStrategy,
        val pathOrderIsConsumed: Boolean,
        val customOrderIsConsumed: Boolean,
    )

    /** Resolves whether an active custom order is consumed by paint or path timeline ordering. */
    private fun resolveCustomOrder(
        config: MorphConfiguration,
        animation: VectorAnimation? = config.animation,
    ): CustomOrderResolution {
        // Mirror PlanRemember's field-by-field resolution: a non-default config order
        // overrides the timeline order even when its delay is inherited from the timeline.
        val configSuppliesStagger = config.staggerDelay > 0f ||
            config.staggerOrder != MorphConfiguration.Default.staggerOrder
        val effectiveStaggerDelay = if (config.staggerDelay > 0f) config.staggerDelay
            else animation?.staggerDelay ?: 0f
        val effectiveStaggerOrder = if (configSuppliesStagger) config.staggerOrder
            else animation?.staggerOrder ?: MorphConfiguration.Default.staggerOrder
        val hasPairScopedTracks = animation?.tracks?.any { track ->
            track.target !is AnimationTarget.Vector && track.target !is AnimationTarget.Group
        } == true || animation?.brushTracks?.any { track ->
            track.target !is AnimationTarget.Vector && track.target !is AnimationTarget.Group
        } == true
        // The evaluator uses path order for stagger offsets and for BY_PATH clock ranks;
        // group and vector tracks have their own shared clocks and do not consume this order.
        val pathOrderIsConsumed = hasPairScopedTracks &&
            (effectiveStaggerDelay > 0f || config.timing == TimingMode.BY_PATH)
        return CustomOrderResolution(
            effectiveStaggerOrder = effectiveStaggerOrder,
            pathOrderIsConsumed = pathOrderIsConsumed,
            customOrderIsConsumed =
                (config.drawOrder == DrawOrderStrategy.CUSTOM && !config.keepRenderOrder) ||
                    (effectiveStaggerOrder == DrawOrderStrategy.CUSTOM && pathOrderIsConsumed),
        )
    }

    /** Whether an active custom order is consumed by paint ordering or path timeline ordering. */
    internal fun isCustomOrderConsumed(
        config: MorphConfiguration,
        animation: VectorAnimation? = config.animation,
    ): Boolean = resolveCustomOrder(config, animation).customOrderIsConsumed

    /**
     * Reports active custom selectors that do not resolve against the supplied vectors or
     * resolved pair representatives. This is additive diagnostics only; it never changes pairs.
     */
    internal fun validateSelectorReferences(
        config: MorphConfiguration,
        from: PreparedVector,
        to: PreparedVector,
        pairs: List<PathPairPlan>,
        animation: VectorAnimation? = config.animation,
    ): List<MorphIssue> {
        val issues = ArrayList<MorphIssue>()
        if (config.pathMatching == PathMatchingStrategy.AUTO ||
            config.pathMatching == PathMatchingStrategy.CUSTOM
        ) {
            val fromNames = from.paths.mapTo(HashSet()) { it.name }
            val toNames = to.paths.mapTo(HashSet()) { it.name }
            config.customMappings.asSequence()
                .map { it.source }
                .filter { it.isNotBlank() && it !in fromNames }
                .distinct()
                .forEach { name ->
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-mapping-source-not-found", listOf(name),
                            "customMappings references missing source path '$name'; existing matching/fallback behavior is retained",
                        ),
                    )
                }
            config.customMappings.asSequence()
                .map { it.target }
                .filter { it.isNotBlank() && it !in toNames }
                .distinct()
                .forEach { name ->
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-mapping-target-not-found", listOf(name),
                            "customMappings references missing target path '$name'; existing matching/fallback behavior is retained",
                        ),
                    )
                }
        }

        if (config.customDrawOrder.isNotEmpty() && isCustomOrderConsumed(config, animation)) {
            val representedNames = pairs.mapNotNull { (it.toPath ?: it.fromPath)?.name }.toHashSet()
            config.customDrawOrder.asSequence()
                .filter { it.isNotBlank() && it !in representedNames }
                .distinct()
                .forEach { name ->
                    issues.add(
                        MorphIssue(
                            IssueSeverity.WARNING, "custom-order-name-not-found", listOf(name),
                            "customDrawOrder references '$name', which is not a resolved pair representative; the name is ignored by custom ordering",
                        ),
                    )
                }
        }
        return issues
    }

    /**
     * Validates an already-planned pair set (used by
     * [com.imorpher.vectormorph.core.plan.MorphPlanner] to fill
     * [com.imorpher.vectormorph.core.plan.MorphReport.issues]). Emits per-pair findings —
     * contour-count mismatches, winding/start-point notes, gradient and clip mismatches,
     * quality warnings — plus vector-level viewport notes. Never throws.
     */
    internal fun validatePlan(
        pairs: List<PathPairPlan>,
        from: PreparedVector,
        to: PreparedVector,
    ): List<MorphIssue> {
        val issues = ArrayList<MorphIssue>()

        if (pairs.isEmpty()) {
            issues.add(MorphIssue(IssueSeverity.WARNING, "empty", emptyList(), "no path pairs produced"))
            return issues
        }

        for (pair in pairs) {
            val names = listOfNotNull(pair.fromPath?.name, pair.toPath?.name)
            val label = names.joinToString("→")

            when (pair.kind) {
                PairKind.MORPH -> {
                    val f = pair.fromPath!!
                    val t = pair.toPath!!

                    if (f.contours.size != t.contours.size) {
                        issues.add(
                            MorphIssue(
                                IssueSeverity.WARNING, "contour-count", names,
                                "$label: contour count ${f.contours.size} → ${t.contours.size}; extra contours fade",
                            )
                        )
                    }

                    for (cp in pair.contourPairs) {
                        if (cp.normalizedDirection) {
                            issues.add(
                                MorphIssue(
                                    IssueSeverity.INFO, "winding", names,
                                    "$label: target winding reversed to match source (auto-normalized)",
                                )
                            )
                        }
                        if (cp.startAlignmentOffset > 0.01f) {
                            issues.add(
                                MorphIssue(
                                    IssueSeverity.INFO, "start-point", names,
                                    "$label: start point rotated by ${(cp.startAlignmentOffset * 100).toInt()}% to prevent rotational drift",
                                )
                            )
                        }
                    }

                    val fillMismatch = brushMismatch(f.fill, t.fill)
                    if (fillMismatch != null) {
                        issues.add(
                            MorphIssue(
                                IssueSeverity.WARNING, "gradient", names,
                                "$label: $fillMismatch — will crossfade",
                            )
                        )
                    }
                    val strokeMismatch = brushMismatch(f.stroke, t.stroke)
                    if (strokeMismatch != null) {
                        issues.add(
                            MorphIssue(
                                IssueSeverity.WARNING, "gradient", names,
                                "$label: stroke $strokeMismatch — will crossfade",
                            )
                        )
                    }

                    if (f.strokeWidth != t.strokeWidth && (f.stroke != null || t.stroke != null)) {
                        issues.add(
                            MorphIssue(
                                IssueSeverity.INFO, "stroke-width", names,
                                "$label: stroke width ${f.strokeWidth} → ${t.strokeWidth} (interpolated)",
                            )
                        )
                    }

                    if (f.clipContours.isNotEmpty() || t.clipContours.isNotEmpty()) {
                        val bothClip = f.clipContours.isNotEmpty() && t.clipContours.isNotEmpty()
                        if (!bothClip) {
                            issues.add(
                                MorphIssue(
                                    IssueSeverity.WARNING, "clip", names,
                                    "$label: clip path exists on only one side; clip fades with its side",
                                )
                            )
                        }
                    }

                    if (pair.score < 0.45f) {
                        issues.add(
                            MorphIssue(
                                IssueSeverity.WARNING, "quality", names,
                                "$label: low geometric compatibility (${(pair.score * 100).toInt()}%); " +
                                    "morph may look elastic — consider custom mapping or crossfade",
                            )
                        )
                    }
                }
                PairKind.CROSSFADE -> issues.add(
                    MorphIssue(
                        IssueSeverity.WARNING, "fallback", names,
                        "$label: pair renders as crossfade (incompatible styles)",
                    )
                )
                PairKind.FADE_IN -> issues.add(
                    MorphIssue(
                        if (pair.warnings.isEmpty()) IssueSeverity.INFO else IssueSeverity.WARNING,
                        "missing", names,
                        "target path '${pair.toPath?.name}' has no source counterpart (${pair.warnings.firstOrNull() ?: "fade/draw in"})",
                    )
                )
                PairKind.FADE_OUT -> issues.add(
                    MorphIssue(
                        if (pair.warnings.isEmpty()) IssueSeverity.INFO else IssueSeverity.WARNING,
                        "missing", names,
                        pair.warnings.firstOrNull() ?: "source path '${pair.fromPath?.name}' has no target counterpart",
                    )
                )
            }
        }

        // vector level
        if (from.viewportWidth != to.viewportWidth || from.viewportHeight != to.viewportHeight) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "viewport",
                    emptyList(),
                    "viewports differ (${from.viewportWidth}x${from.viewportHeight} → " +
                        "${to.viewportWidth}x${to.viewportHeight}); morphing in normalized viewport space",
                )
            )
        }

        return issues
    }

    /** Describes an un-interpolable fill pair, or null when the paints can interpolate. */
    private fun brushMismatch(
        a: com.imorpher.vectormorph.core.gradients.BrushSpec?,
        b: com.imorpher.vectormorph.core.gradients.BrushSpec?,
    ): String? {
        if (a == null && b == null) return null
        if (a == null || b == null) return null // fill↔stroke transitions handled by strategy
        if (a.isUnknown || b.isUnknown) return "unsupported brush kind"
        val kindA = brushKind(a)
        val kindB = brushKind(b)
        if (kindA != kindB) return "$kindA → $kindB gradient kinds differ"
        return null
    }

    /** Short kind label ("solid", "linear", "radial", "sweep", "unknown") for messages. */
    private fun brushKind(spec: com.imorpher.vectormorph.core.gradients.BrushSpec): String = when (spec) {
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Solid -> "solid"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Linear -> "linear"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Radial -> "radial"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Sweep -> "sweep"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Unknown -> "unknown"
    }
}
