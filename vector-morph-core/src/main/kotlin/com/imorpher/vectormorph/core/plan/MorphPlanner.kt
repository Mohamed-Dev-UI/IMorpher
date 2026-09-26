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

    fun plan(
        from: PreparedVector,
        to: PreparedVector,
        config: MorphConfiguration = MorphConfiguration.Default,
    ): MorphPlan {
        // ImageVector paths are expressed in viewport coordinates. Put both vectors in the
        // source viewport before matching so equal normalized shapes are compared and rendered
        // at the same size even when their authored viewport dimensions differ.
        val normalizedTo = normalizeViewport(to, from.viewportWidth, from.viewportHeight)
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

    /** Scales prepared target geometry into the source viewport once, before matching/planning. */
    private fun normalizeViewport(vector: PreparedVector, width: Float, height: Float): PreparedVector {
        if (vector.viewportWidth <= 0f || vector.viewportHeight <= 0f || width <= 0f || height <= 0f ||
            (vector.viewportWidth == width && vector.viewportHeight == height)
        ) return vector

        val sx = width / vector.viewportWidth
        val sy = height / vector.viewportHeight
        val strokeScale = kotlin.math.sqrt(kotlin.math.abs(sx * sy))
        val paths = vector.paths.map { path ->
            val contours = path.contours.map { com.imorpher.vectormorph.core.compiler.VectorCompiler.transformContour(it, scaleMatrix(sx, sy)) }
            val clips = path.clipContours.map { com.imorpher.vectormorph.core.compiler.VectorCompiler.transformContour(it, scaleMatrix(sx, sy)) }
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
                blendMode = path.blendMode, clipContours = clips, sourceTransform = path.sourceTransform,
                bounds = pathBounds, centroidX = cx, centroidY = cy, area = area,
                totalLength = length, contourLengths = contourLengths, windingSigns = windings,
                arcLengths = arcs, startFractions = starts,
            )
        }
        return PreparedVector(width, height, vector.defaultWidth, vector.defaultHeight, vector.name, paths)
    }

    private fun scaleMatrix(sx: Float, sy: Float) = floatArrayOf(
        sx, 0f, 0f, 0f,
        0f, sy, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
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

    private class FallbackDecision(
        val used: Boolean,
        val crossfadeAll: Boolean,
        val strategy: FallbackStrategy?,
        val reason: String?,
    )

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

    private fun structurallyIncompatible(from: PreparedVector, to: PreparedVector): String? {
        if (from.paths.isEmpty() && to.paths.isEmpty()) return "both vectors have no paths"
        if (from.paths.isEmpty()) return "source vector has no paths (pure fade-in; crossfade chosen)"
        if (to.paths.isEmpty()) return "target vector has no paths (pure fade-out; crossfade chosen)"
        return null
    }

    // ------------------------------------------------------------------ pair planning

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

    /** Greedy minimum-cost contour assignment; matching by centroid/area/length avoids index-only
     * mismatches for multi-contour icons while keeping unmatched contours available for fades. */
    private fun matchContours(from: List<ContourData>, to: List<ContourData>): List<Pair<Int, Int>> {
        if (from.isEmpty() || to.isEmpty()) return emptyList()
        data class Candidate(val from: Int, val to: Int, val cost: Float)
        val candidates = ArrayList<Candidate>(from.size * to.size)
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
        for (fi in from.indices) for (ti in to.indices) {
            val dx = fromCentroids[fi][0] - toCentroids[ti][0]
            val dy = fromCentroids[fi][1] - toCentroids[ti][1]
            val centerCost = (hypot(dx, dy) / diagonal).coerceAtMost(2f)
            val areaCost = abs(fromAreas[fi] - toAreas[ti]) / maxOf(fromAreas[fi], toAreas[ti], 1e-3f)
            val lengthCost = abs(fromLengths[fi].totalLength - toLengths[ti].totalLength) /
                maxOf(fromLengths[fi].totalLength, toLengths[ti].totalLength, 1e-3f)
            val closureCost = if (from[fi].closed == to[ti].closed) 0f else 0.75f
            candidates.add(Candidate(fi, ti, centerCost * 0.48f + areaCost * 0.27f + lengthCost * 0.2f + closureCost * 0.05f))
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

        // 3) equalize segment counts with arc-length-proportional subdivision
        val target0 = maxOf(source.segments.size, target.segments.size)
        var a = Subdivider.equalize(source, target0)
        var b = Subdivider.equalize(target, target0)

        // If subdivision ballooned (very different command counts), re-align once more so the
        // newly inserted split points also correspond well.
        if (abs(source.segments.size - target.segments.size) > 2 && target0 > 8) {
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
 * LRU-ish cache keyed by (from identity, to identity, configuration).
 * Compiled [PreparedVector]s and [MorphPlan]s are immutable, so caching is always safe.
 */
class MorphPlanCache(private val maxEntries: Int = 32) {

    private data class Key(
        val from: PreparedVector,
        val to: PreparedVector,
        val config: MorphConfiguration,
    )

    private val cache = object : LinkedHashMap<Key, MorphPlan>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, MorphPlan>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun getOrPut(from: PreparedVector, to: PreparedVector, config: MorphConfiguration, build: () -> MorphPlan): MorphPlan {
        val key = Key(from, to, config)
        return cache.getOrPut(key, build)
    }

    @Synchronized
    fun clear() = cache.clear()

    @get:Synchronized
    val size: Int get() = cache.size
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
