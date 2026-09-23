package com.imorpher.vectormorph.core.validation

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

    private fun brushKind(spec: com.imorpher.vectormorph.core.gradients.BrushSpec): String = when (spec) {
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Solid -> "solid"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Linear -> "linear"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Radial -> "radial"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Sweep -> "sweep"
        is com.imorpher.vectormorph.core.gradients.BrushSpec.Unknown -> "unknown"
    }
}
