package com.imorpher.vectormorph.core.plan

import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.bounds
import com.imorpher.vectormorph.core.geometry.centroid
import com.imorpher.vectormorph.core.geometry.measureContour
import com.imorpher.vectormorph.core.geometry.signedArea
import com.imorpher.vectormorph.core.geometry.arcFractionNear
import com.imorpher.vectormorph.core.geometry.Subdivider
import com.imorpher.vectormorph.core.interpolation.GeometryInterpolator
import com.imorpher.vectormorph.core.matching.ContourAligner
import com.imorpher.vectormorph.core.matching.PathMatcher
import com.imorpher.vectormorph.core.matching.PathMatch
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.PathStart
import com.imorpher.vectormorph.core.model.StartPointStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import com.imorpher.vectormorph.core.model.ViewportNormalization
import com.imorpher.vectormorph.core.validation.MorphValidator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Builds a [MorphPlan] from two [PreparedVector]s and a [MorphConfiguration].
 *
 * Pipeline (all at plan time — none of it runs per frame):
 *
 * 1. path matching (strategy-driven, or explicit mappings)
 * 2. per-pair fallback decision (score + user strategy)
 * 3. contour correspondence (by position for multi-contour paths)
 * 4. winding normalization, start-point alignment, arc-length-balanced subdivision
 * 5. packing into flat float arrays + measurement
 * 6. validation report + compatibility scoring
 *
 * Results are cached by [MorphPlanCache] so repeat morphs of the same pair are free.
 */
object MorphPlanner {

    /**
     * Builds a [MorphPlan] from two prepared vectors and a configuration. Pure and
     * deterministic: equal inputs always produce an equal plan, so results cache well.
     * See the object documentation for the pipeline stages.
     */
    fun plan(
        from: PreparedVector,
        to: PreparedVector,
        config: MorphConfiguration = MorphConfiguration.Default,
    ): MorphPlan {
        // ImageVector paths are expressed in viewport coordinates. Put both vectors in the
        // source viewport before matching so equal normalized shapes are compared and rendered
        // at the same size even when their authored viewport dimensions differ.
        val normalizedTo = normalizeViewport(to, from.viewportWidth, from.viewportHeight, config.viewportNormalization)
        val (rawMatches, pathWarnings) = PathMatcher.match(from, normalizedTo, config.pathMatching, config.customMappings)
        val (matches, nearestWarnings) = if (config.missingPath == MissingPathBehavior.MORPH_FROM_NEAREST) {
            pairNearestOrphans(rawMatches)
        } else rawMatches to emptyList()
        val matchWarnings = buildList {
            addAll(pathWarnings)
            addAll(nearestWarnings)
            if (from.viewportWidth != to.viewportWidth || from.viewportHeight != to.viewportHeight) {
                add("target geometry normalized from ${to.viewportWidth}x${to.viewportHeight} to source viewport ${from.viewportWidth}x${from.viewportHeight}")
            }
        }

        val fallbackDecision = decideFallback(from, normalizedTo, config, matchWarnings)
        val issues = ArrayList<MorphIssue>()
        if (from.viewportWidth != to.viewportWidth || from.viewportHeight != to.viewportHeight) {
            issues.add(
                MorphIssue(
                    IssueSeverity.INFO,
                    "viewport",
                    emptyList(),
                    "target geometry normalized from ${to.viewportWidth}x${to.viewportHeight} to source viewport ${from.viewportWidth}x${from.viewportHeight}",
                ),
            )
        }
        val pairs = ArrayList<PathPairPlan>(matches.size)

        val missingBehavior = if (fallbackDecision.crossfadeAll) MissingPathBehavior.FADE else config.missingPath

        for ((index, match) in matches.withIndex()) {
            val f = match.from
            val t = match.to
            when {
                f != null && t != null -> {
                    if (fallbackDecision.crossfadeAll ||
                        (f.fill?.isUnknown == true) || (t.fill?.isUnknown == true)
                    ) {
                        pairs.add(
                            PathPairPlan(
                                index = index, fromPath = f, toPath = t,
                                kind = PairKind.CROSSFADE,
                                contourPairs = emptyList(),
                                fromOnlyContours = f.contours,
                                toOnlyContours = t.contours,
                                score = PathMatcher.scorePair(f, t),
                                warnings = listOf("Crossfade pair"),
                            )
                        )
                    } else {
                        pairs.add(planMorphPair(index, f, t, config))
                    }
                }
                t != null -> pairs.add(planAppearingPair(index, t, missingBehavior))
                else -> pairs.add(planDisappearingPair(index, f!!, missingBehavior))
            }
        }

        val validatorIssues = MorphValidator.validatePlan(pairs, from, normalizedTo)
        issues.addAll(validatorIssues)

        val scores = pairs.map { it.score }
        val compatScore = if (scores.isEmpty()) 1f else scores.sum() / scores.size
        val warnings = (matchWarnings + pairs.flatMap { it.warnings } + issues.filter {
            it.severity != com.imorpher.vectormorph.core.plan.IssueSeverity.INFO
        }.map { it.message }).distinct()

        val report = MorphReport(
            compatibility = MorphCompatibility(compatScore.coerceIn(0f, 1f), warnings),
            issues = issues,
            unmatchedSourcePaths = pairs.filter { it.kind == PairKind.FADE_OUT }.mapNotNull { it.fromPath?.name },
            unmatchedTargetPaths = pairs.filter { it.kind == PairKind.FADE_IN }.mapNotNull { it.toPath?.name },
            fallbackUsed = fallbackDecision.used || pairs.any { it.kind == PairKind.CROSSFADE },
            fallbackStrategy = fallbackDecision.strategy,
            fallbackReason = fallbackDecision.reason,
        )

        return MorphPlan(
            from = from, to = normalizedTo, pairs = pairs,
            usedFallback = fallbackDecision.used,
            fallbackReason = fallbackDecision.reason,
            report = report,
        )
    }

    /**
     * Scales prepared target geometry into the source viewport once, before matching/planning.
     * [ViewportNormalization.STRETCH] scales each axis independently; [ViewportNormalization.FIT]
     * uses one uniform scale and centres the letterboxed geometry.
     */
    private fun normalizeViewport(
        vector: PreparedVector,
        width: Float,
        height: Float,
        normalization: ViewportNormalization = ViewportNormalization.STRETCH,
    ): PreparedVector {
        if (vector.viewportWidth <= 0f || vector.viewportHeight <= 0f || width <= 0f || height <= 0f ||
            (vector.viewportWidth == width && vector.viewportHeight == height)
        ) return vector

        val sx = width / vector.viewportWidth
        val sy = height / vector.viewportHeight
        val matrix: FloatArray
        val strokeScale: Float
        when (normalization) {
            ViewportNormalization.STRETCH -> {
                matrix = scaleMatrix(sx, sy)
                strokeScale = kotlin.math.sqrt(kotlin.math.abs(sx * sy))
            }
            ViewportNormalization.FIT -> {
                val uniform = minOf(sx, sy)
                val offsetX = (width - vector.viewportWidth * uniform) / 2f
                val offsetY = (height - vector.viewportHeight * uniform) / 2f
                matrix = affineMatrix(uniform, uniform, offsetX, offsetY)
                strokeScale = uniform
            }
        }
        val paths = vector.paths.map { path ->
            val contours = path.contours.map { com.imorpher.vectormorph.core.compiler.VectorCompiler.transformContour(it, matrix) }
            val clips = path.clipContours.map { com.imorpher.vectormorph.core.compiler.VectorCompiler.transformContour(it, matrix) }
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var area = 0f; var length = 0f
            var cx = 0f; var cy = 0f; var weight = 0f
            val contourLengths = FloatArray(contours.size)
            val windings = IntArray(contours.size)
            val arcs = ArrayList<com.imorpher.vectormorph.core.geometry.ArcLengthData>(contours.size)
            for ((index, contour) in contours.withIndex()) {
                val b = bounds(contour)
                minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
                maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
                val signed = com.imorpher.vectormorph.core.geometry.signedArea(contour)
                val absolute = abs(signed)
                area += absolute
                windings[index] = if (signed > 1e-6f) 1 else if (signed < -1e-6f) -1 else 0
                val arc = measureContour(contour)
                arcs.add(arc); contourLengths[index] = arc.totalLength; length += arc.totalLength
                val center = centroid(contour)
                val w = absolute.coerceAtLeast(1e-3f)
                cx += center[0] * w; cy += center[1] * w; weight += w
            }
            if (weight > 0f) { cx /= weight; cy /= weight }
            val pathBounds = if (contours.isEmpty()) floatArrayOf(0f, 0f, 0f, 0f)
                else floatArrayOf(minX, minY, maxX, maxY)
            val starts = contours.mapIndexed { index, contour ->
                FloatArray(PathStart.entries.size) { ordinal ->
                    when (val start = PathStart.entries[ordinal]) {
                        PathStart.START, PathStart.CUSTOM -> 0f
                        PathStart.END -> 1f
                        PathStart.CENTER -> 0.5f
                        else -> {
                            val x = when (start) {
                                PathStart.TOP, PathStart.BOTTOM -> (minX + maxX) / 2f
                                PathStart.RIGHT, PathStart.TOP_RIGHT, PathStart.BOTTOM_RIGHT -> maxX
                                PathStart.LEFT, PathStart.TOP_LEFT, PathStart.BOTTOM_LEFT -> minX
                                else -> cx
                            }
                            val y = when (start) {
                                PathStart.LEFT, PathStart.RIGHT -> (minY + maxY) / 2f
                                PathStart.BOTTOM, PathStart.BOTTOM_LEFT, PathStart.BOTTOM_RIGHT -> maxY
                                PathStart.TOP, PathStart.TOP_LEFT, PathStart.TOP_RIGHT -> minY
                                else -> cy
                            }
                            arcFractionNear(contour, arcs[index], x, y)
                        }
                    }
                }
            }
            PreparedPath(
                name = path.name, index = path.index, groupNames = path.groupNames,
                contours = contours, fill = path.fill, fillAlpha = path.fillAlpha,
                stroke = path.stroke, strokeAlpha = path.strokeAlpha,
                strokeWidth = path.strokeWidth * strokeScale,
                strokeLineCap = path.strokeLineCap, strokeLineJoin = path.strokeLineJoin,
                strokeLineMiter = path.strokeLineMiter, pathFillType = path.pathFillType,
                clipContours = clips, sourceTransform = path.sourceTransform,
                bounds = pathBounds, centroidX = cx, centroidY = cy, area = area,
                totalLength = length, contourLengths = contourLengths, windingSigns = windings,
                arcLengths = arcs, startFractions = starts,
            )
        }
        return PreparedVector(width, height, vector.defaultWidth, vector.defaultHeight, vector.name, paths)
    }

    /** Column-major 4x4 uniform 2D scale matrix matching [VectorCompiler]'s matrix layout. */
    private fun scaleMatrix(sx: Float, sy: Float) = floatArrayOf(
        sx, 0f, 0f, 0f,
        0f, sy, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Column-major 4x4 scale-then-translate matrix (translation in slots 12/13). */
    private fun affineMatrix(sx: Float, sy: Float, tx: Float, ty: Float) = floatArrayOf(
        sx, 0f, 0f, 0f,
        0f, sy, 0f, 0f,
        0f, 0f, 1f, 0f,
        tx, ty, 0f, 1f,
    )

    /** Replaces orphaned pairs with nearest one-to-one geometry morphs when requested. */
    private fun pairNearestOrphans(matches: List<PathMatch>): Pair<List<PathMatch>, List<String>> {
        val sourceOrphans = matches.filter { it.from != null && it.to == null }.mapNotNull { it.from }
        val targetOrphans = matches.filter { it.from == null && it.to != null }.mapNotNull { it.to }
        if (sourceOrphans.isEmpty() || targetOrphans.isEmpty()) return matches to emptyList()

        val consumedSources = HashSet<PreparedPath>()
        val consumedTargets = HashSet<PreparedPath>()
        val replacements = ArrayList<PathMatch>()
        val warnings = ArrayList<String>()
        for (target in targetOrphans) {
            val nearest = sourceOrphans.asSequence().filterNot { it in consumedSources }.minByOrNull { source ->
                val dx = source.centroidX - target.centroidX
                val dy = source.centroidY - target.centroidY
                dx * dx + dy * dy
            } ?: continue
            consumedSources.add(nearest)
            consumedTargets.add(target)
            replacements.add(PathMatch(nearest, target))
            warnings.add("unmatched target '${target.name}' morphs from nearest source '${nearest.name}'")
        }
        val retained = matches.filterNot { match ->
            (match.from != null && match.to == null && match.from in consumedSources) ||
                (match.from == null && match.to != null && match.to in consumedTargets)
        }
        return (retained + replacements).sortedWith(
            compareBy<PathMatch> { it.to?.index ?: Int.MAX_VALUE }.thenBy { it.from?.index ?: Int.MAX_VALUE },
        ) to warnings
    }

    // ------------------------------------------------------------------ fallback decision

    /** Immutable outcome of [decideFallback]; consumed by the pair-planning loop. */
    private class FallbackDecision(
        /** Whether the plan degraded from pure geometry morphing. */
        val used: Boolean,
        /** Whether EVERY pair should render as a crossfade. */
        val crossfadeAll: Boolean,
        /** The explicit strategy that decided this, when one was requested. */
        val strategy: FallbackStrategy?,
        /** Human-readable reason, or null for clean geometry morphs. */
        val reason: String?,
    )

    /**
     * Resolves the fallback decision for this morph: explicit strategies short-circuit
     * (CROSSFADE/SCALE_CROSSFADE force a full crossfade, DRAW_REVEAL flags draw-first,
     * THROW aborts on structural incompatibility, BEST_EFFORT always plans geometry),
     * and AUTO crossfades only when one side has no paths at all.
     */
    private fun decideFallback(
        from: PreparedVector,
        to: PreparedVector,
        config: MorphConfiguration,
        matchWarnings: List<String>,
    ): FallbackDecision {
        val explicit = config.fallback
        if (explicit == FallbackStrategy.CROSSFADE) {
            return FallbackDecision(true, true, explicit, "FallbackStrategy.CROSSFADE requested")
        }
        if (explicit == FallbackStrategy.SCALE_CROSSFADE) {
            return FallbackDecision(true, true, explicit, "FallbackStrategy.SCALE_CROSSFADE requested")
        }
        if (explicit == FallbackStrategy.DRAW_REVEAL) {
            return FallbackDecision(true, false, explicit, "FallbackStrategy.DRAW_REVEAL requested")
        }

        val structural = structurallyIncompatible(from, to)
        if (explicit == FallbackStrategy.THROW && structural != null) {
            throw IllegalArgumentException("Morph incompatible: $structural")
        }
        if (explicit == FallbackStrategy.BEST_EFFORT) {
            return FallbackDecision(false, false, explicit, null)
        }

        // AUTO
        if (structural != null) {
            return FallbackDecision(true, true, FallbackStrategy.AUTO, structural)
        }
        return FallbackDecision(false, false, null, null)
    }

    /** Human-readable reason the pair cannot morph structurally, or null when compatible. */
    private fun structurallyIncompatible(from: PreparedVector, to: PreparedVector): String? {
        if (from.paths.isEmpty() && to.paths.isEmpty()) return "both vectors have no paths"
        if (from.paths.isEmpty()) return "source vector has no paths (pure fade-in; crossfade chosen)"
        if (to.paths.isEmpty()) return "target vector has no paths (pure fade-out; crossfade chosen)"
        return null
    }

    // ------------------------------------------------------------------ pair planning

    /**
     * Plans a two-sided pair: contour correspondence, hole-preserving all-or-nothing
     * contour matching, winding/start-point alignment, subdivision, and the stroke-fill
     * choreography decision. Unpaired contours land in [PathPairPlan.fromOnlyContours] /
     * [PathPairPlan.toOnlyContours] where they fade through their own side's leg.
     */
    private fun planMorphPair(
        index: Int,
        f: com.imorpher.vectormorph.core.model.PreparedPath,
        t: com.imorpher.vectormorph.core.model.PreparedPath,
        config: MorphConfiguration,
    ): PathPairPlan {
        val contourPairs = ArrayList<ContourPairPlan>()
        val fromOnly = ArrayList<ContourData>()
        val toOnly = ArrayList<ContourData>()
        val pairWarnings = ArrayList<String>()

        var contourMatches = matchContours(f.contours, t.contours)
        // A path whose contours encode fill-rule holes (an enclosing contour with
        // opposite-winding contours inside it, e.g. a solid icon's disc + inner symbols)
        // only renders correctly when its contours stay in ONE fill path. If matching
        // pairs only some of them, the unpaired contours fill separately and the holes
        // stop cancelling — the icon tears into solid lumps at the morph endpoints. Such
        // paths therefore morph all-or-nothing: either every contour finds a partner, or
        // the whole path fades through the unmatched-contour legs.
        if (hasFillRuleHole(f.contours) || hasFillRuleHole(t.contours)) {
            val fromComplete = f.contours.isEmpty() || contourMatches.size == f.contours.size
            val toComplete = t.contours.isEmpty() || contourMatches.size == t.contours.size
            val fromOk = !hasFillRuleHole(f.contours) || fromComplete
            val toOk = !hasFillRuleHole(t.contours) || toComplete
            if (!fromOk || !toOk) contourMatches = emptyList()
        }
        if (f.contours.size != t.contours.size) {
            pairWarnings.add(
                "contour count differs (${f.contours.size} → ${t.contours.size}); " +
                    "extra contours fade"
            )
        }

        // A target path that contains fill-rule holes (e.g. the solid icon path holding
        // the enclosing disc AND the inner-symbol contours) must keep its internal
        // relative winding intact at the target endpoint — otherwise NonZero no longer
        // cancels the symbols and they paint over the fill. Winding alignment therefore
        // preserves EVERY contour of such a path: flipping only the matched enclosing
        // contour (to untwist against its source partner) would break parity against the
        // preserved holes, and flipping holes alone breaks it the other way. Endpoint
        // fill-rule integrity wins over twist-free correspondence under AUTO.
        val preserveTargetWinding = config.pathDirection == PathDirectionStrategy.AUTO &&
            t.contours.any { ContourAligner.isFillRuleHole(it, t.contours) }

        val matchedFrom = BooleanArray(f.contours.size)
        val matchedTo = BooleanArray(t.contours.size)
        for ((fromIndex, toIndex) in contourMatches) {
            matchedFrom[fromIndex] = true
            matchedTo[toIndex] = true
            contourPairs.add(alignContours(f, fromIndex, t, toIndex, config, preserveTargetWinding))
        }
        for (index in f.contours.indices) if (!matchedFrom[index]) fromOnly.add(f.contours[index])
        for (index in t.contours.indices) if (!matchedTo[index]) toOnly.add(t.contours[index])

        val strokeFillDraw = decideStrokeFill(f, t, config)

        return PathPairPlan(
            index = index,
            fromPath = f,
            toPath = t,
            kind = PairKind.MORPH,
            contourPairs = contourPairs,
            fromOnlyContours = fromOnly,
            toOnlyContours = toOnly,
            strokeFillDraw = strokeFillDraw,
            score = PathMatcher.scorePair(f, t),
            warnings = pairWarnings,
        )
    }

    /** Whether any closed contour of the set winds opposite to its enclosing contour. */
    private fun hasFillRuleHole(contours: List<ContourData>): Boolean =
        contours.any { ContourAligner.isFillRuleHole(it, contours) }

    /**
     * Minimum-cost contour assignment by centroid/area/length (exact for small multi-contour
     * paths, greedy beyond that). Avoiding index-only mismatches keeps holes paired with holes
     * while unmatched contours stay available for fades.
     */
    private fun matchContours(from: List<ContourData>, to: List<ContourData>): List<Pair<Int, Int>> {
        if (from.isEmpty() || to.isEmpty()) return emptyList()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        (from + to).forEach { contour ->
            val b = bounds(contour)
            minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
            maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
        }
        val diagonal = hypot(maxX - minX, maxY - minY).coerceAtLeast(1e-4f)
        val fromLengths = from.map(::measureContour)
        val toLengths = to.map(::measureContour)
        val fromAreas = from.map { abs(com.imorpher.vectormorph.core.geometry.signedArea(it)) }
        val toAreas = to.map { abs(com.imorpher.vectormorph.core.geometry.signedArea(it)) }
        val fromCentroids = from.map(::centroid)
        val toCentroids = to.map(::centroid)

        fun contourCost(fi: Int, ti: Int): Float {
            val dx = fromCentroids[fi][0] - toCentroids[ti][0]
            val dy = fromCentroids[fi][1] - toCentroids[ti][1]
            val centerCost = (hypot(dx, dy) / diagonal).coerceAtMost(2f)
            val areaCost = abs(fromAreas[fi] - toAreas[ti]) / maxOf(fromAreas[fi], toAreas[ti], 1e-3f)
            val lengthCost = abs(fromLengths[fi].totalLength - toLengths[ti].totalLength) /
                maxOf(fromLengths[fi].totalLength, toLengths[ti].totalLength, 1e-3f)
            val closureCost = if (from[fi].closed == to[ti].closed) 0f else 0.75f
            return centerCost * 0.48f + areaCost * 0.27f + lengthCost * 0.2f + closureCost * 0.05f
        }

        if (maxOf(from.size, to.size) <= com.imorpher.vectormorph.core.matching.Assignment.MAX_OPTIMAL_SIZE) {
            val matrix = Array(from.size) { fi -> FloatArray(to.size) { ti -> contourCost(fi, ti) } }
            val assignment = com.imorpher.vectormorph.core.matching.Assignment.solve(matrix)
            return from.indices.mapNotNull { fi ->
                val ti = assignment[fi]
                if (ti in to.indices) fi to ti else null
            }
        }

        data class Candidate(val from: Int, val to: Int, val cost: Float)
        val candidates = ArrayList<Candidate>(from.size * to.size)
        for (fi in from.indices) for (ti in to.indices) {
            candidates.add(Candidate(fi, ti, contourCost(fi, ti)))
        }
        candidates.sortWith(compareBy<Candidate> { it.cost }.thenBy { it.from }.thenBy { it.to })
        val usedFrom = BooleanArray(from.size)
        val usedTo = BooleanArray(to.size)
        val result = ArrayList<Pair<Int, Int>>(minOf(from.size, to.size))
        for (candidate in candidates) {
            if (usedFrom[candidate.from] || usedTo[candidate.to]) continue
            usedFrom[candidate.from] = true
            usedTo[candidate.to] = true
            result.add(candidate.from to candidate.to)
            if (result.size == minOf(from.size, to.size)) break
        }
        return result.sortedBy { it.first }
    }

    /**
     * Aligns one contour pair for interpolation: winding normalization (skipped when
     * [preserveTargetWinding] — see [planMorphPair]'s parity guard), start-point alignment
     * (honoring [MorphConfiguration.customStartOffsets]), arc-length-proportional subdivision
     * to a common segment count, optional re-alignment for large command-count mismatches,
     * and packing into the per-frame float arrays.
     */
    @Suppress("DEPRECATION") // customStartOffsets is the documented legacy alignment fallback
    private fun alignContours(
        f: com.imorpher.vectormorph.core.model.PreparedPath,
        ci: Int,
        t: com.imorpher.vectormorph.core.model.PreparedPath,
        ti: Int,
        config: MorphConfiguration,
        preserveTargetWinding: Boolean,
    ): ContourPairPlan {
        var source = f.contours[ci]
        var target = t.contours[ti]

        // 1) winding normalization (see the path-level parity guard in [planMorphPair]).
        val strategy = if (preserveTargetWinding) PathDirectionStrategy.PRESERVE else config.pathDirection
        val (s, u, reversed) = ContourAligner.normalizeDirection(source, target, strategy)
        source = s; target = u

        // 2) start-point alignment
        val (s2, t2, offset) = ContourAligner.alignStartPoints(
            source, target, config.startPoint,
            customSourceOffset = config.customStartOffsets[f.name],
            customTargetOffset = config.customStartOffsets[t.name],
        )
        source = s2; target = t2

        // 3) equalize segment counts: subdivide up to the natural max, or simplify both sides
        //    when the caller set a cheaper segment budget.
        val natural = maxOf(source.segments.size, target.segments.size)
        val budget = config.segmentBudget?.coerceAtLeast(2)?.let { minOf(it, natural) } ?: natural
        var a = fitToBudget(source, budget, config.simplificationTolerance)
        var b = fitToBudget(target, budget, config.simplificationTolerance)
        if (a.segments.size != b.segments.size) {
            // Error-driven reduction may stop at different counts; interpolation requires equal
            // counts, so lift the smaller side to the larger (both stay within the budget).
            val common = maxOf(a.segments.size, b.segments.size)
            a = Subdivider.equalize(a, common)
            b = Subdivider.equalize(b, common)
        }

        // If subdivision ballooned (very different command counts), re-align once more so the
        // newly inserted split points also correspond well.
        if (abs(source.segments.size - target.segments.size) > 2 && natural > 8) {
            val (ra, rb, _) = ContourAligner.alignStartPoints(a, b, StartPointStrategy.AUTO)
            a = ra; b = rb
        }

        // 4) pack + measure
        val packedA = GeometryInterpolator.pack(a.segments)
        val packedB = GeometryInterpolator.pack(b.segments)
        val arcA = measureContour(a)
        val arcB = measureContour(b)

        return ContourPairPlan(
            from = AlignedContour(
                packedA, a.closed, a.segments.size, arcA,
                if (a.closed) if (signedArea(a) >= 0f) 1 else -1 else 0,
            ),
            to = AlignedContour(
                packedB, b.closed, b.segments.size, arcB,
                if (b.closed) if (signedArea(b) >= 0f) 1 else -1 else 0,
            ),
            normalizedDirection = reversed,
            startAlignmentOffset = offset,
        )
    }

    /** Brings one contour to [budget] segments by subdividing up or simplifying down. */
    private fun fitToBudget(contour: ContourData, budget: Int, tolerance: Float): ContourData =
        if (contour.segments.size <= budget) {
            Subdivider.equalize(contour, budget)
        } else {
            Subdivider.reduce(contour, budget, tolerance)
        }

    /**
     * Whether the stroke→fill transition should draw the stroke first and sweep the fill in
     * behind it. AUTO picks the draw choreography for outline→filled transitions only.
     */
    private fun decideStrokeFill(
        f: com.imorpher.vectormorph.core.model.PreparedPath,
        t: com.imorpher.vectormorph.core.model.PreparedPath,
        config: MorphConfiguration,
    ): Boolean {
        return when (config.strokeFill) {
            StrokeFillStrategy.DRAW -> true
            StrokeFillStrategy.MORPH, StrokeFillStrategy.CROSSFADE -> false
            StrokeFillStrategy.AUTO -> {
                // outline → filled: draw the stroke first, then sweep the fill in
                val fromStroked = f.stroke != null && f.fill == null
                val toFilled = t.fill != null
                fromStroked && toFilled
            }
        }
    }

    // ------------------------------------------------------------------ appearing / disappearing pairs

    /** Plans a target-only path (fade-in side); geometry renders from the target side. */
    private fun planAppearingPair(
        index: Int,
        t: com.imorpher.vectormorph.core.model.PreparedPath,
        behavior: MissingPathBehavior,
    ): PathPairPlan {
        val warnings = when (behavior) {
            MissingPathBehavior.MORPH_FROM_NEAREST -> listOf("no unmatched source path is available; '${t.name}' fades in")
            MissingPathBehavior.CUSTOM -> listOf("'${t.name}' requires CustomVectorAnimation to provide geometry at render time")
            else -> emptyList()
        }
        return PathPairPlan(
            index = index,
            fromPath = null,
            toPath = t,
            kind = PairKind.FADE_IN,
            contourPairs = emptyList(),
            fromOnlyContours = emptyList(),
            toOnlyContours = t.contours,
            score = 0f,
            warnings = warnings,
        )
    }

    /** Plans a source-only path (fade-out side); geometry renders from the source side. */
    private fun planDisappearingPair(
        index: Int,
        f: com.imorpher.vectormorph.core.model.PreparedPath,
        behavior: MissingPathBehavior,
    ): PathPairPlan {
        val warnings = when (behavior) {
            MissingPathBehavior.CUSTOM -> listOf("'${f.name}' requires CustomVectorAnimation to provide geometry at render time")
            else -> emptyList()
        }
        return PathPairPlan(
            index = index,
            fromPath = f,
            toPath = null,
            kind = PairKind.FADE_OUT,
            contourPairs = emptyList(),
            fromOnlyContours = f.contours,
            toOnlyContours = emptyList(),
            score = 0f,
            warnings = warnings,
        )
    }
}

/**
 * LRU cache keyed by structural content ([PreparedVector.contentKey]) plus configuration.
 *
 * Keying by content rather than instance identity means equal-but-distinct `ImageVector`s share
 * one plan. Byte accounting approximates the packed geometry the plan holds, so a few enormous
 * icons cannot pin unbounded memory: every insert evicts the least-recently-used entries until
 * both the entry-count and byte budgets hold.
 *
 * Compiled [PreparedVector]s and [MorphPlan]s are immutable, so caching is always safe.
 */
class MorphPlanCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private data class Key(
        val fromKey: String,
        val toKey: String,
        val config: MorphConfiguration,
    )

    /** Access-ordered LRU; plans are immutable so eviction only costs re-planning. */
    private val cache = LinkedHashMap<Key, MorphPlan>(16, 0.75f, true)
    private val bytesByKey = HashMap<Key, Long>()
    private var currentBytes = 0L

    /**
     * Returns the cached plan for (from, to, config), building and inserting it via [build]
     * on a miss. Access-order based, so repeated lookups keep hot entries alive.
     */
    @Synchronized
    fun getOrPut(from: PreparedVector, to: PreparedVector, config: MorphConfiguration, build: () -> MorphPlan): MorphPlan {
        val key = Key(from.contentKey, to.contentKey, config)
        cache[key]?.let { return it }
        val plan = build()
        cache[key] = plan
        val bytes = estimateBytes(plan)
        bytesByKey[key] = bytes
        currentBytes += bytes
        evictToFit()
        return plan
    }

    /** Returns the cached plan for the pair without building one, or null on a miss. */
    @Synchronized
    fun get(from: PreparedVector, to: PreparedVector, config: MorphConfiguration): MorphPlan? =
        cache[Key(from.contentKey, to.contentKey, config)]

    /**
     * Evicts least-recently-used plans until the cache holds at most [bytes] estimated bytes.
     * Wire this to `ComponentCallbacks2.onTrimMemory` (see `MorphCaches.trim` in the compose
     * module) to release memory under pressure.
     */
    @Synchronized
    fun trimToBytes(bytes: Long) {
        val target = bytes.coerceAtLeast(0L)
        val iterator = cache.entries.iterator()
        while (currentBytes > target && iterator.hasNext()) {
            val entry = iterator.next()
            currentBytes -= bytesByKey.remove(entry.key) ?: 0L
            iterator.remove()
        }
    }

    /** Evicts eldest entries until the entry-count and byte budgets both hold. */
    private fun evictToFit() {
        val iterator = cache.entries.iterator()
        while ((cache.size > maxEntries || currentBytes > maxBytes) && iterator.hasNext()) {
            val entry = iterator.next()
            currentBytes -= bytesByKey.remove(entry.key) ?: 0L
            iterator.remove()
        }
    }

    /** Drops every cached plan (call after large icon-set changes to release memory). */
    @Synchronized
    fun clear() {
        cache.clear()
        bytesByKey.clear()
        currentBytes = 0L
    }

    /** Current number of cached plans. */
    @get:Synchronized
    val size: Int get() = cache.size

    /** Current estimated bytes held by cached plans. */
    @get:Synchronized
    val byteSize: Long get() = currentBytes

    companion object {
        /** Default LRU entry cap. */
        const val DEFAULT_MAX_ENTRIES = 32

        /** Default estimated-byte cap (16 MiB) — a guard rail, not a hard memory limit. */
        const val DEFAULT_MAX_BYTES: Long = 16L * 1024 * 1024

        /** Per-pair bookkeeping overhead (pair + contour-pair objects) in bytes. */
        private const val PAIR_OVERHEAD_BYTES = 220L

        /**
         * Approximates the heap held by [plan]'s packed geometry: 4 bytes per coordinate plus
         * a fixed per-pair overhead. Used for eviction decisions only, never for correctness.
         */
        fun estimateBytes(plan: MorphPlan): Long {
            var bytes = plan.pairs.size * PAIR_OVERHEAD_BYTES
            for (pair in plan.pairs) {
                for (contourPair in pair.contourPairs) {
                    bytes += (contourPair.from.coords.size + contourPair.to.coords.size) * 4L
                    bytes += (contourPair.from.arcLength.segmentLengths.size + contourPair.to.arcLength.segmentLengths.size) * 4L
                }
            }
            return bytes
        }
    }
}

/** Convenience: compile both vectors and plan. */
fun planMorph(
    from: androidx.compose.ui.graphics.vector.ImageVector,
    to: androidx.compose.ui.graphics.vector.ImageVector,
    config: MorphConfiguration = MorphConfiguration.Default,
): MorphPlan = MorphPlanner.plan(
    VectorCompiler.compile(from),
    VectorCompiler.compile(to),
    config,
)
