package com.imorpher.vectormorph.core.plan

import com.imorpher.vectormorph.core.geometry.ArcLengthData
import com.imorpher.vectormorph.core.geometry.ContourData
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

/** One contour correspondence within a morphing path pair. */
class ContourPairPlan(
    val from: AlignedContour,
    val to: AlignedContour,
    /** Which side was reversed to normalize winding (diagnostics). */
    val normalizedDirection: Boolean,
    /** Arc fraction the target start was rotated to (diagnostics). */
    val startAlignmentOffset: Float,
)

/** One source-path ↔ target-path correspondence. */
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
    /** 0..1 geometric compatibility score for this pair (MORPH pairs). */
    val score: Float = 1f,
    /** Human-readable warnings attached to this pair. */
    val warnings: List<String> = emptyList(),
)

/**
 * The fully prepared morph: everything expensive (matching, alignment, subdivision, measurement)
 * is computed once here. Per-frame work is exclusively float lerps + evaluation of the timeline.
 */
class MorphPlan(
    val from: PreparedVector,
    val to: PreparedVector,
    val pairs: List<PathPairPlan>,
    /** Path pairs rendered as a crossfade because geometry morphing was not viable. */
    val usedFallback: Boolean,
    val fallbackReason: String?,
    val report: MorphReport,
) {
    val maxViewportWidth: Float get() = maxOf(from.viewportWidth, to.viewportWidth)
    val maxViewportHeight: Float get() = maxOf(from.viewportHeight, to.viewportHeight)
}

/** Severity of a [MorphIssue]. */
enum class IssueSeverity { INFO, WARNING, ERROR }

/** One finding of [com.imorpher.vectormorph.core.validation.MorphValidator]. */
data class MorphIssue(
    val severity: IssueSeverity,
    val category: String,
    val pathNames: List<String>,
    val message: String,
)

/** Overall morph quality score + warnings, exposed to developers. */
data class MorphCompatibility(
    /** 0..1, weighted across path pairs (1.0 = every path pairs up with identical structure). */
    val score: Float,
    val warnings: List<String>,
)

/** Full diagnostic report produced at planning time. */
data class MorphReport(
    val compatibility: MorphCompatibility,
    val issues: List<MorphIssue>,
    val unmatchedSourcePaths: List<String>,
    val unmatchedTargetPaths: List<String>,
    val fallbackUsed: Boolean,
    val fallbackStrategy: FallbackStrategy?,
    val fallbackReason: String?,
)
