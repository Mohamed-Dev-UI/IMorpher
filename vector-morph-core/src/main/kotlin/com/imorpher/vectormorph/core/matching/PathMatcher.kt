package com.imorpher.vectormorph.core.matching

import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathMapping
import com.imorpher.vectormorph.core.model.PreparedPath
import com.imorpher.vectormorph.core.model.PreparedVector
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** One resolved source→target path correspondence (either side may be absent). */
data class PathMatch(
    val from: PreparedPath?,
    val to: PreparedPath?,
) {
    val kindDescriptor: String
        get() = when {
            from != null && to != null -> "MORPH"
            to != null -> "FADE_IN"
            else -> "FADE_OUT"
        }
}

/**
 * Pairs source paths with target paths according to a [PathMatchingStrategy] (or explicit
 * [PathMapping]s), using precomputed geometric features. Also produces a compatibility score
 * per pair for diagnostics and for the fallback heuristic.
 */
object PathMatcher {

    fun match(
        fromVector: PreparedVector,
        toVector: PreparedVector,
        strategy: PathMatchingStrategy,
        customMappings: List<PathMapping>,
    ): Pair<List<PathMatch>, List<String>> {
        val fromPaths = fromVector.paths
        val toPaths = toVector.paths
        val warnings = mutableListOf<String>()

        if (fromPaths.isEmpty() && toPaths.isEmpty()) return emptyList<PathMatch>() to warnings

        val matches = when (strategy) {
            PathMatchingStrategy.CUSTOM -> matchByNames(fromPaths, toPaths, customMappings, warnings)
            PathMatchingStrategy.BY_NAME -> matchByNames(fromPaths, toPaths, nameMappings(fromPaths, toPaths), warnings)
            PathMatchingStrategy.BY_INDEX -> matchByIndex(fromPaths, toPaths, warnings)
            PathMatchingStrategy.BY_ID -> matchByIndex(fromPaths, toPaths, warnings)
            PathMatchingStrategy.BY_AREA -> matchByCost(fromPaths, toPaths, ::areaCost, warnings)
            PathMatchingStrategy.BY_CENTROID -> matchByCost(fromPaths, toPaths, ::centroidCost, warnings)
            PathMatchingStrategy.BY_GEOMETRY -> matchByCost(fromPaths, toPaths, ::geometryCost, warnings)
            PathMatchingStrategy.AUTO -> matchAuto(fromPaths, toPaths, customMappings, warnings)
        }

        // sanity warnings
        val unmatchedFrom = matches.count { it.from != null && it.to == null }
        val unmatchedTo = matches.count { it.from == null && it.to != null }
        if (unmatchedFrom > 0) warnings.add("$unmatchedFrom source path(s) have no target counterpart")
        if (unmatchedTo > 0) warnings.add("$unmatchedTo target path(s) have no source counterpart")

        return matches to warnings
    }

    // ------------------------------------------------------------------ strategies

    private fun nameMappings(fromPaths: List<PreparedPath>, toPaths: List<PreparedPath>): List<PathMapping> =
        fromPaths.map { PathMapping(source = it.name, target = it.name) }

    private fun matchByNames(
        fromPaths: List<PreparedPath>,
        toPaths: List<PreparedPath>,
        mappings: List<PathMapping>,
        warnings: MutableList<String>,
    ): List<PathMatch> {
        val toByName = toPaths.associateBy { it.name }
        val usedTargets = HashSet<PreparedPath>()
        val out = ArrayList<PathMatch>()
        for (f in fromPaths) {
            val mapping = mappings.firstOrNull { it.source == f.name }
            val t = mapping?.let { toByName[it.target] }
            if (t != null && usedTargets.add(t)) {
                out.add(PathMatch(f, t))
            } else {
                if (mapping != null && t == null) {
                    warnings.add("Mapping '${f.name}' → '${mapping.target}': target path not found")
                }
                out.add(PathMatch(f, null))
            }
        }
        for (t in toPaths) {
            if (!usedTargets.contains(t)) out.add(PathMatch(null, t))
        }
        return out
    }

    private fun matchByIndex(
        fromPaths: List<PreparedPath>,
        toPaths: List<PreparedPath>,
        warnings: MutableList<String>,
    ): List<PathMatch> {
        if (fromPaths.size != toPaths.size) {
            warnings.add(
                "BY_INDEX matching with different path counts (${fromPaths.size} → ${toPaths.size}); " +
                    "consider BY_GEOMETRY or AUTO"
            )
        }
        val n = min(fromPaths.size, toPaths.size)
        val out = ArrayList<PathMatch>(maxOf(fromPaths.size, toPaths.size))
        for (i in 0 until n) out.add(PathMatch(fromPaths[i], toPaths[i]))
        for (i in n until fromPaths.size) out.add(PathMatch(fromPaths[i], null))
        for (i in n until toPaths.size) out.add(PathMatch(null, toPaths[i]))
        return out
    }

    /** Greedy assignment on a cost matrix (sorted by ascending cost, each path used once). */
    private fun matchByCost(
        fromPaths: List<PreparedPath>,
        toPaths: List<PreparedPath>,
        cost: (PreparedPath, PreparedPath, Float) -> Float,
        warnings: MutableList<String>,
    ): List<PathMatch> {
        val diag = maxOf(fromVectorDiag(fromPaths), fromVectorDiag(toPaths)).coerceAtLeast(1f)
        val edges = ArrayList<Triple<Int, Int, Float>>(fromPaths.size * toPaths.size)
        for ((i, f) in fromPaths.withIndex()) {
            for ((j, t) in toPaths.withIndex()) {
                edges.add(Triple(i, j, cost(f, t, diag)))
            }
        }
        edges.sortBy { it.third }

        val out = arrayOfNulls<PathMatch>(fromPaths.size)
        val usedTargets = BooleanArray(toPaths.size)
        val usedSources = BooleanArray(fromPaths.size)
        var matched = 0
        for ((i, j, c) in edges) {
            if (usedSources[i] || usedTargets[j]) continue
            usedSources[i] = true
            usedTargets[j] = true
            out[i] = PathMatch(fromPaths[i], toPaths[j])
            matched++
            if (matched == min(fromPaths.size, toPaths.size)) break
        }
        val list = ArrayList<PathMatch>(fromPaths.size + toPaths.size)
        for ((i, m) in out.withIndex()) {
            list.add(m ?: PathMatch(fromPaths[i], null))
        }
        // append unmatched targets
        for ((j, t) in toPaths.withIndex()) {
            if (!usedTargets[j]) list.add(PathMatch(null, t))
        }
        return list
    }

    private fun matchAuto(
        fromPaths: List<PreparedPath>,
        toPaths: List<PreparedPath>,
        customMappings: List<PathMapping>,
        warnings: MutableList<String>,
    ): List<PathMatch> {
        // explicit mappings first
        val base = if (customMappings.isNotEmpty()) {
            matchByNames(fromPaths, toPaths, customMappings, warnings)
        } else null

        val diag = maxOf(fromVectorDiag(fromPaths), fromVectorDiag(toPaths)).coerceAtLeast(1f)

        val result = if (base != null) {
            // re-match unmatched pairs geometrically
            val unmatchedFrom = base.filter { it.to == null }.mapNotNull { it.from }
            val unmatchedTo = base.filter { it.from == null }.mapNotNull { it.to }
            if (unmatchedFrom.isEmpty() || unmatchedTo.isEmpty()) {
                base
            } else {
                val geo = matchByCost(unmatchedFrom, unmatchedTo, ::geometryCost, warnings)
                base.mapNotNull { m ->
                    when {
                        m.to != null && m.from != null -> m
                        else -> null
                    }
                } + geo
            }
        } else if (fromPaths.size == toPaths.size) {
            // try index matching, but reject pairs whose geometry wildly disagrees
            val indexMatched = matchByIndex(fromPaths, toPaths, mutableListOf())
            val needsGeo = indexMatched.any { m ->
                val f = m.from ?: return@any false
                val t = m.to ?: return@any false
                geometryCost(f, t, diag) > 0.8f
            }
            if (needsGeo) {
                matchByCost(fromPaths, toPaths, ::geometryCost, warnings)
            } else {
                indexMatched
            }
        } else {
            matchByCost(fromPaths, toPaths, ::geometryCost, warnings)
        }
        return result
    }

    // ------------------------------------------------------------------ cost functions (0 = identical, 1 = maximally different)

    private fun fromVectorDiag(paths: List<PreparedPath>): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in paths) {
            if (p.isEmpty) continue
            if (p.bounds[0] < minX) minX = p.bounds[0]
            if (p.bounds[1] < minY) minY = p.bounds[1]
            if (p.bounds[2] > maxX) maxX = p.bounds[2]
            if (p.bounds[3] > maxY) maxY = p.bounds[3]
        }
        if (minX == Float.MAX_VALUE) return 1f
        return sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY))
    }

    internal fun centroidCost(f: PreparedPath, t: PreparedPath, diag: Float): Float {
        val dx = f.centroidX - t.centroidX
        val dy = f.centroidY - t.centroidY
        return (sqrt(dx * dx + dy * dy) / diag).coerceIn(0f, 1f)
    }

    internal fun areaCost(f: PreparedPath, t: PreparedPath, diag: Float): Float {
        val maxA = maxOf(f.area, t.area)
        if (maxA <= 0f) return if (f.area == t.area) 0f else 1f
        return (1f - min(f.area, t.area) / maxA).coerceIn(0f, 1f)
    }

    internal fun geometryCost(f: PreparedPath, t: PreparedPath, diag: Float): Float {
        val centroid = centroidCost(f, t, diag)
        val area = areaCost(f, t, diag)

        val maxLen = maxOf(f.totalLength, t.totalLength)
        val lenDiff = if (maxLen <= 0f) 0f else abs(f.totalLength - t.totalLength) / maxLen

        val contourDiff = abs(f.contours.size - t.contours.size) /
            maxOf(f.contours.size, t.contours.size, 1).toFloat()

        val boundsOverlap = overlapRatio(f.bounds, t.bounds)

        // weighted blend; bounds overlap is a strong signal for icons
        val cost = 0.38f * centroid + 0.22f * area + 0.18f * lenDiff + 0.12f * contourDiff +
            0.10f * (1f - boundsOverlap)
        return cost.coerceIn(0f, 1f)
    }

    private fun overlapRatio(a: FloatArray, b: FloatArray): Float {
        val ix = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = ix * iy
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else (inter / union).coerceIn(0f, 1f)
    }

    /** Per-pair quality score: 1 − cost, with structural bonuses/penalties. */
    fun scorePair(f: PreparedPath?, t: PreparedPath?): Float {
        if (f == null || t == null) return 0f
        val diag = maxOf(fromVectorDiag(listOf(f)), fromVectorDiag(listOf(t))).coerceAtLeast(1f)
        val cost = geometryCost(f, t, diag)
        var score = 1f - cost
        // penalize style jumps (solid ↔ gradient are crossfaded, not morphed)
        if ((f.fill?.isUnknown == true) || (t.fill?.isUnknown == true)) score -= 0.05f
        return score.coerceIn(0f, 1f)
    }
}
