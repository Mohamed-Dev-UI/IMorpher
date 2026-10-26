package com.imorpher.vectormorph.core.validation

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
     * Categories follow [validatePlan]'s lowercase style:
     * WARNING `custom-anchors-unused`, `custom-order-unused`, `missing-path-under-crossfade`,
     * `fill-direction-ignored`; INFO `fallback-throw-scope`, `draw-order-not-rendered`,
     * `stagger-order-without-delay`, `sweep-direction-on-stroke`, `custom-mappings-unused`.
     */
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

        if (config.customDrawAnchors.isNotEmpty() && !hasCustomStart) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-anchors-unused", emptyList(),
                    "customDrawAnchors is never consulted: no reveal segment requests PathStart.CUSTOM " +
                        "(the legacy customStartOffsets fallback is equally idle without one)",
                ),
            )
        }

        if (config.customDrawOrder.isNotEmpty() && config.drawOrder != DrawOrderStrategy.CUSTOM) {
            issues.add(
                MorphIssue(
                    IssueSeverity.WARNING, "custom-order-unused", emptyList(),
                    "customDrawOrder is ignored: drawOrder is ${config.drawOrder}, but only " +
                        "DrawOrderStrategy.CUSTOM consults the list",
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
                    "drawOrder ${config.drawOrder} shapes the reveal timing; keepRenderOrder=true keeps " +
                        "painting in declaration order (z-order unchanged)",
                ),
            )
        }

        if (config.staggerDelay == 0f && config.staggerOrder != DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "stagger-order-without-delay", emptyList(),
                    "staggerOrder ${config.staggerOrder} needs a staggerDelay > 0 to have any effect",
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

        if (config.customMappings.isNotEmpty() &&
            config.pathMatching != PathMatchingStrategy.AUTO && config.pathMatching != PathMatchingStrategy.CUSTOM
        ) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO, "custom-mappings-unused", emptyList(),
                    "customMappings are ignored: pathMatching is ${config.pathMatching}; only AUTO " +
                        "(as overrides) and CUSTOM consult them",
                ),
            )
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
