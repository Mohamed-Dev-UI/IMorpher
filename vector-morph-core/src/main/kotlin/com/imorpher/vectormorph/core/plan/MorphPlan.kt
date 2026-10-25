package com.imorpher.vectormorph.core.plan

import com.imorpher.vectormorph.core.geometry.ArcLengthData
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.PreparedVector

/** How a source path / contour pair relates across the two vectors. */
enum class PairKind {
    /** Both sides exist and geometry interpolates. */
    MORPH,
    /** Only on the target side: appears during the animation. */
    FADE_IN,
    /** Only on the source side: disappears during the animation. */
    FADE_OUT,
    /** Non-interpolable (unknown brush, incompatible geometry) — alpha crossfade. */
    CROSSFADE,
}

/**
 * A contour pair aligned for interpolation: both sides have identical segment counts, matched
 * start points and normalized winding, so per-frame rendering is a pure control-point lerp.
 *
 * Geometry is packed as flat float arrays (8 floats per cubic segment) for allocation-free
 * per-frame interpolation into reusable scratch buffers.
 */
class AlignedContour(
    /** 8 floats per segment: x0,y0,x1,y1,x2,y2,x3,y3. */
    val coords: FloatArray,
    val closed: Boolean,
    val segmentCount: Int,
    val arcLength: ArcLengthData,
    /** +1 clockwise on screen, -1 counter-clockwise; zero for degenerate/open contours. */
    val windingSign: Int = 0,
) {
    val totalLength: Float get() = arcLength.totalLength
}

/**
 * One contour correspondence within a morphing path pair.
 *
 * @param from The source side, aligned and packed for interpolation.
 * @param to The target side, aligned and packed for interpolation.
 * @param normalizedDirection Which side was reversed to normalize winding (diagnostics).
 * @param startAlignmentOffset Arc fraction the target start was rotated to (diagnostics).
 */
class ContourPairPlan(
    val from: AlignedContour,
    val to: AlignedContour,
    /** Which side was reversed to normalize winding (diagnostics). */
    val normalizedDirection: Boolean,
    /** Arc fraction the target start was rotated to (diagnostics). */
    val startAlignmentOffset: Float,
)

/** The stroke→fill choreography the planner resolves for one pair. */
data class StrokeFillDecision(
    /** Draw-first: the pen completes the outline before the fill sweeps in behind it. */
    val draw: Boolean,
    /** Stroke-shrink: the stroke scales down while the fill grows with the morph. */
    val morph: Boolean,
)

/**
 * One source-path ↔ target-path correspondence.
 *
 * @param index Position in [MorphPlan.pairs]; timeline frames index their buffers by it.
 * @param fromPath Source-side path, or null when the path only exists on the target side.
 * @param toPath Target-side path, or null when the path only exists on the source side.
 * @param kind How the pair renders (morph, fade, or crossfade).
 * @param contourPairs Aligned contour pairs; present only for [PairKind.MORPH].
 * @param fromOnlyContours Source contours with no partner — drawn from the source leg.
 * @param toOnlyContours Target contours with no partner — drawn from the target leg.
 * @param strokeFillDraw Whether the stroke→fill transition uses draw-first choreography.
 * @param score 0..1 geometric compatibility of the pair (1 = identical structure).
 * @param warnings Human-readable diagnostics attached to this pair.
 */
class PathPairPlan(
    val index: Int,
    val fromPath: PreparedPath?,
    val toPath: PreparedPath?,
    val kind: PairKind,
    /** Present when [kind] == MORPH. */
    val contourPairs: List<ContourPairPlan>,
    /** Contours rendered only from the source side (kind FADE_OUT or partial morph). */
    val fromOnlyContours: List<ContourData>,
    /** Contours rendered only from the target side (kind FADE_IN or partial morph). */
    val toOnlyContours: List<ContourData>,
    /** Whether the stroke→fill transition uses a draw-first choreography. */
    val strokeFillDraw: Boolean = false,
    /**
     * Whether the stroke→fill transition shrinks the stroke as the fill grows with the morph
     * ([StrokeFillStrategy.MORPH]). The renderer applies the shrink only when the pair
     * carries both fill and stroke paint.
     */
    val strokeFillMorph: Boolean = false,
    /** 0..1 geometric compatibility score for this pair (MORPH pairs). */
    val score: Float = 1f,
    /** Human-readable warnings attached to this pair. */
    val warnings: List<String> = emptyList(),
    /**
     * Aligned clip-mask correspondence, present only when both sides carry the same number of
     * inherited clip contours and every one found a partner. When empty the renderer keeps the
     * historical midpoint switch between the source and target masks.
     */
    val clipPairs: List<ContourPairPlan> = emptyList(),
)

/**
 * Interpolates this pair's aligned clip masks at [morphT] (0 = source clips, 1 = target).
 *
 * Returns an empty list when the pair has no aligned clip pairs (mismatched clip counts, a
 * clip on one side only, or a path without group clips), so callers keep the legacy endpoint
 * switch unchanged. Geometry is packed in [ContourPairPlan] and this is a pure coordinate lerp.
 */
fun PathPairPlan.interpolatedClipContours(morphT: Float): List<ContourData> {
    if (clipPairs.isEmpty()) return emptyList()
    val t = morphT.coerceIn(0f, 1f)
    val mt = 1f - t
    return clipPairs.map { cp ->
        val n = cp.from.segmentCount
        val a = cp.from.coords
        val b = cp.to.coords
        val segments = ArrayList<Cubic>(n)
        for (s in 0 until n) {
            val o = s * 8
            segments += Cubic(
                a[o] * mt + b[o] * t,
                a[o + 1] * mt + b[o + 1] * t,
                a[o + 2] * mt + b[o + 2] * t,
                a[o + 3] * mt + b[o + 3] * t,
                a[o + 4] * mt + b[o + 4] * t,
                a[o + 5] * mt + b[o + 5] * t,
                a[o + 6] * mt + b[o + 6] * t,
                a[o + 7] * mt + b[o + 7] * t,
            )
        }
        ContourData(segments, if (t < 0.5f) cp.from.closed else cp.to.closed)
    }
}

/**
 * The fully prepared morph: everything expensive (matching, alignment, subdivision, measurement)
 * is computed once here. Per-frame work is exclusively float lerps + evaluation of the timeline.
 */
class MorphPlan(
    /** Compiled source vector, in its own viewport space. */
    val from: PreparedVector,
    /** Compiled target vector, normalized into [from]'s viewport. */
    val to: PreparedVector,
    /** Every source↔target correspondence in stable order (morphs, fades, crossfades). */
    val pairs: List<PathPairPlan>,
    /** Path pairs rendered as a crossfade because geometry morphing was not viable. */
    val usedFallback: Boolean,

    /** Human-readable reason [usedFallback] was forced, or null for clean morphs. */
    val fallbackReason: String?,

    /** Full planning-time diagnostic report (scores, issues, unmatched paths). */
    val report: MorphReport,
) {
    val maxViewportWidth: Float get() = maxOf(from.viewportWidth, to.viewportWidth)
    val maxViewportHeight: Float get() = maxOf(from.viewportHeight, to.viewportHeight)
}

/** Severity of a [MorphIssue]. */
enum class IssueSeverity { INFO, WARNING, ERROR }

/**
 * One finding of [com.imorpher.vectormorph.core.validation.MorphValidator].
 *
 * @param severity From informational notes to actionable warnings.
 * @param category Machine-readable grouping ("contour-count", "gradient", "missing"…).
 * @param pathNames The involved path names (empty for vector-level findings).
 * @param message Human-readable description of the finding.
 */
data class MorphIssue(
    val severity: IssueSeverity,
    val category: String,
    val pathNames: List<String>,
    val message: String,
)

/**
 * Overall morph quality score + warnings, exposed to developers.
 *
 * @param score 0..1, weighted across path pairs (1.0 = every path pairs up with identical
 *   structure; below ~0.45 the morph may look elastic).
 * @param warnings Deduplicated planning warnings worth surfacing in an IDE or log.
 */
data class MorphCompatibility(
    /** 0..1, weighted across path pairs (1.0 = every path pairs up with identical structure). */
    val score: Float,
    val warnings: List<String>,
)

/**
 * Full diagnostic report produced at planning time.
 *
 * @param compatibility Aggregate quality score with warnings.
 * @param issues Every [MorphIssue] found while validating the plan.
 * @param unmatchedSourcePaths Names of source paths with no target counterpart.
 * @param unmatchedTargetPaths Names of target paths with no source counterpart.
 * @param fallbackUsed Whether any pair degraded to a crossfade.
 * @param fallbackStrategy The strategy that forced the fallback, when explicit.
 * @param fallbackReason Why the fallback was chosen.
 */
data class MorphReport(
    val compatibility: MorphCompatibility,
    val issues: List<MorphIssue>,
    val unmatchedSourcePaths: List<String>,
    val unmatchedTargetPaths: List<String>,
    val fallbackUsed: Boolean,
    val fallbackStrategy: FallbackStrategy?,
    val fallbackReason: String?,
)
