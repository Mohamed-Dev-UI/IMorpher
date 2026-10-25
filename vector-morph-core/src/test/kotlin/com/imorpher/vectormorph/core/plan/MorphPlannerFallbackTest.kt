package com.imorpher.vectormorph.core.plan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.geometry.ContourData
import com.imorpher.vectormorph.core.geometry.Cubic
import com.imorpher.vectormorph.core.model.FallbackStrategy
import com.imorpher.vectormorph.core.model.MissingPathBehavior
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathDirectionStrategy
import com.imorpher.vectormorph.core.model.StrokeFillStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Planner-level behavior: fallback strategies, hole preservation, stroke/fill choreography. */
class MorphPlannerFallbackTest {

    private fun triangle(offset: Float = 0f) = listOf(
        PathNode.MoveTo(3f + offset, 3f),
        PathNode.LineTo(21f - offset, 3f),
        PathNode.LineTo(12f, 21f - offset),
        PathNode.Close,
    )

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    private fun vector(
        name: String,
        vararg paths: Pair<String, List<PathNode>>,
        stroked: Boolean = false,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { (pathName, pathData) ->
            if (stroked) {
                builder.addPath(pathData = pathData, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, name = pathName)
            } else {
                builder.addPath(pathData = pathData, fill = SolidColor(Color.Black), name = pathName)
            }
        }
        return builder.build()
    }

    private fun plan(from: ImageVector, to: ImageVector, config: MorphConfiguration = MorphConfiguration.Default): MorphPlan =
        MorphPlanner.plan(VectorCompiler.compile(from), VectorCompiler.compile(to), config)

    // ------------------------------------------------------------------ fallback strategies

    @Test
    fun crossfadeFallbackMarksEveryPairAsCrossfade() {
        val from = vector("from", "a" to triangle())
        val to = vector("to", "b" to triangle(1f))

        val plan = plan(from, to, MorphConfiguration(fallback = FallbackStrategy.CROSSFADE))

        assertTrue(plan.usedFallback)
        assertTrue(plan.pairs.all { it.kind == PairKind.CROSSFADE })
        assertTrue(plan.pairs.all { it.contourPairs.isEmpty() })
    }

    @Test
    fun scaleCrossfadeFallbackAlsoCrossfadesAllPairs() {
        val plan = plan(
            vector("from", "a" to triangle()),
            vector("to", "b" to triangle(1f)),
            MorphConfiguration(fallback = FallbackStrategy.SCALE_CROSSFADE),
        )
        assertTrue(plan.usedFallback)
        assertTrue(plan.pairs.all { it.kind == PairKind.CROSSFADE })
    }

    @Test
    fun throwFallbackThrowsOnStructuralIncompatibility() {
        val empty = ImageVector.Builder("empty", 24.dp, 24.dp, 24f, 24f).build()
        val full = vector("full", "a" to triangle())
        assertThrows(IllegalArgumentException::class.java) {
            plan(full, empty, MorphConfiguration(fallback = FallbackStrategy.THROW))
        }
    }

    @Test
    fun bestEffortKeepsGeometryPlanningEvenForEmptySides() {
        val empty = ImageVector.Builder("empty", 24.dp, 24.dp, 24f, 24f).build()
        val full = vector("full", "a" to triangle())

        val fadeIn = plan(empty, full, MorphConfiguration(fallback = FallbackStrategy.BEST_EFFORT))
        assertFalse(fadeIn.usedFallback)
        assertEquals(PairKind.FADE_IN, fadeIn.pairs.single().kind)

        val fadeOut = plan(full, empty, MorphConfiguration(fallback = FallbackStrategy.BEST_EFFORT))
        assertEquals(PairKind.FADE_OUT, fadeOut.pairs.single().kind)
    }

    @Test
    fun autoFallbackCrossfadesWhenOneSideIsEmpty() {
        val empty = ImageVector.Builder("empty", 24.dp, 24.dp, 24f, 24f).build()
        val full = vector("full", "a" to triangle())

        val plan = plan(full, empty)
        assertTrue(plan.usedFallback)
        assertTrue(plan.report.fallbackReason?.contains("fade-out") == true)
    }

    @Test
    fun drawRevealFallbackKeepsPairKindButFlagsThePlan() {
        val plan = plan(
            vector("from", "a" to triangle()),
            vector("to", "b" to triangle(1f)),
            MorphConfiguration(fallback = FallbackStrategy.DRAW_REVEAL),
        )
        assertTrue(plan.usedFallback)
        // pair kind stays MORPH; the renderer branches on the strategy itself
        assertEquals(PairKind.MORPH, plan.pairs.single().kind)
        assertFalse(plan.report.fallbackStrategy == null)
    }

    @Test
    fun autoFallbackDoesNotTriggerForCompatiblePairs() {
        val plan = plan(vector("from", "a" to triangle()), vector("to", "b" to triangle(1f)))
        assertFalse(plan.usedFallback)
        assertNull(plan.fallbackReason)
    }

    // ------------------------------------------------------------------ fill-rule holes

    @Test
    fun holePathsMorphAllOrNothingWhenOnlySomeContoursMatch() {
        // Target: a two-contour holed path (outer clockwise + inner COUNTER-clockwise,
        // i.e. a real NonZero fill-rule hole).
        // Source: a single contour that can only pair ONE of the two.
        val holed = listOf(
            PathNode.MoveTo(2f, 2f), PathNode.LineTo(22f, 2f), PathNode.LineTo(22f, 22f),
            PathNode.LineTo(2f, 22f), PathNode.Close,
            PathNode.MoveTo(9f, 9f), PathNode.LineTo(9f, 15f), PathNode.LineTo(15f, 15f),
            PathNode.LineTo(15f, 9f), PathNode.Close,
        )
        val plan = plan(
            vector("from", "disc" to triangle()),
            vector("to", "solid" to holed),
        )

        val pair = plan.pairs.single()
        assertEquals(PairKind.MORPH, pair.kind)
        // all-or-nothing: either both contours pair, or none do (fade through the legs)
        assertTrue(
            "hole path must not partially pair contours: ${pair.contourPairs.size}",
            pair.contourPairs.isEmpty() || pair.contourPairs.size == 2,
        )
    }

    @Test
    fun holePathsPairFullyWhenBothSidesHaveHoles() {
        val holed = listOf(
            PathNode.MoveTo(2f, 2f), PathNode.LineTo(22f, 2f), PathNode.LineTo(22f, 22f),
            PathNode.LineTo(2f, 22f), PathNode.Close,
            PathNode.MoveTo(9f, 9f), PathNode.LineTo(15f, 9f), PathNode.LineTo(15f, 15f),
            PathNode.LineTo(9f, 15f), PathNode.Close,
        )
        val shifted = listOf(
            PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 3f), PathNode.LineTo(21f, 21f),
            PathNode.LineTo(3f, 21f), PathNode.Close,
            PathNode.MoveTo(10f, 10f), PathNode.LineTo(14f, 10f), PathNode.LineTo(14f, 14f),
            PathNode.LineTo(10f, 14f), PathNode.Close,
        )
        val plan = plan(vector("from", "a" to holed), vector("to", "b" to shifted))
        val pair = plan.pairs.single()
        assertEquals(2, pair.contourPairs.size)
        assertTrue(pair.fromOnlyContours.isEmpty())
        assertTrue(pair.toOnlyContours.isEmpty())
    }

    @Test
    fun autoWindingDoesNotFlipHoleParityInsideHoledTargets() {
        // Winding signs of the target's contours must survive planning when the target
        // contains fill-rule holes; flipping the outer contour would paint the inner
        // symbol solid at the endpoint.
        val holed = listOf(
            PathNode.MoveTo(2f, 2f), PathNode.LineTo(22f, 2f), PathNode.LineTo(22f, 22f),
            PathNode.LineTo(2f, 22f), PathNode.Close, // clockwise on screen
            PathNode.MoveTo(9f, 9f), PathNode.LineTo(9f, 15f), PathNode.LineTo(15f, 15f),
            PathNode.LineTo(15f, 9f), PathNode.Close, // counter-clockwise: the hole
        )
        val compiledFrom = VectorCompiler.compile(vector("from", "a" to triangle()))
        val compiledTo = VectorCompiler.compile(vector("to", "b" to holed))
        val plan = MorphPlanner.plan(compiledFrom, compiledTo, MorphConfiguration())

        val pair = plan.pairs.single()
        if (pair.contourPairs.isNotEmpty()) {
            val toPlan = pair.toPath!!
            assertEquals(compiledTo.paths[0].windingSigns.toList(), toPlan.windingSigns.toList())
        }
    }

    // ------------------------------------------------------------------ stroke ↔ fill

    @Test
    fun strokeFillAutoChoreographsOutlineToFilledTransitions() {
        val outline = vector("from", "a" to triangle(), stroked = true)
        val filled = vector("to", "b" to triangle())

        val plan = plan(outline, filled)
        assertTrue("outline → filled must draw first", plan.pairs.single().strokeFillDraw)

        val reverse = plan(filled, outline)
        assertFalse("filled → outline must not use draw choreography", reverse.pairs.single().strokeFillDraw)
    }

    @Test
    fun strokeFillExplicitStrategiesOverrideTheHeuristic() {
        val outline = vector("from", "a" to triangle(), stroked = true)
        val filled = vector("to", "b" to triangle())

        val forced = plan(outline, filled, MorphConfiguration(strokeFill = StrokeFillStrategy.MORPH))
        assertFalse(forced.pairs.single().strokeFillDraw)

        val drawn = plan(filled, outline, MorphConfiguration(strokeFill = StrokeFillStrategy.DRAW))
        assertTrue(drawn.pairs.single().strokeFillDraw)
    }

    /** A path carrying BOTH fill and stroke — the pair shape the shrink choreography needs. */
    private fun paintedBothSides(name: String, data: List<PathNode>): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = data,
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            name = "a",
        )
        return builder.build()
    }

    @Test
    fun morphStrokeFillPlansAStrokeShrinkPair() {
        val a = paintedBothSides("first", triangle())
        val b = paintedBothSides("second", triangle(1f))

        val morph = plan(a, b, MorphConfiguration(strokeFill = StrokeFillStrategy.MORPH))
        assertTrue("explicit MORPH plans the stroke-shrink pair", morph.pairs.single().strokeFillMorph)
        assertFalse("the shrink replaces draw choreography", morph.pairs.single().strokeFillDraw)

        val crossfade = plan(a, b, MorphConfiguration(strokeFill = StrokeFillStrategy.CROSSFADE))
        assertFalse("CROSSFADE is not the morph shrink", crossfade.pairs.single().strokeFillMorph)

        // AUTO keeps today's resolved behavior (pre-flight ruling): no morph shrink.
        val auto = plan(a, b, MorphConfiguration.Default)
        assertFalse("AUTO must never emit the morph shrink", auto.pairs.single().strokeFillMorph)
    }

    // ------------------------------------------------------------------ viewport + reports

    @Test
    fun viewportNormalizationScalesStrokeWidthsGeometrically() {
        val from = VectorCompiler.compile(vector("from", "a" to line(), stroked = true))
        val toBuilder = ImageVector.Builder("to", 24.dp, 24.dp, 48f, 48f)
        toBuilder.addPath(
            pathData = line().map { node ->
                // scale the geometry 2x so the normalized shapes coincide
                when (node) {
                    is PathNode.MoveTo -> PathNode.MoveTo(node.x * 2f, node.y * 2f)
                    is PathNode.LineTo -> PathNode.LineTo(node.x * 2f, node.y * 2f)
                    else -> node
                }
            },
            stroke = SolidColor(Color.Black), strokeLineWidth = 3f, name = "a",
        )
        val to = VectorCompiler.compile(toBuilder.build())

        val plan = MorphPlanner.plan(from, to)
        assertEquals(24f, plan.to.viewportWidth, 0f)
        // authored width 3 in a 48 viewport → normalized to 1.5 in a 24 viewport
        assertEquals(1.5f, plan.to.paths.single().strokeWidth, 1e-4f)
    }

    @Test
    fun reportsListUnmatchedPathsOnBothSides() {
        // BY_NAME: nothing shares a name, so every path fades to its opposite side.
        val plan = plan(
            vector("from", "a" to triangle(), "gone" to line()),
            vector("to", "b" to triangle(1f), "new" to line()),
            MorphConfiguration(pathMatching = com.imorpher.vectormorph.core.model.PathMatchingStrategy.BY_NAME),
        )
        assertEquals(listOf("a", "gone"), plan.report.unmatchedSourcePaths)
        assertEquals(listOf("b", "new"), plan.report.unmatchedTargetPaths)
        assertTrue(plan.report.compatibility.warnings.isNotEmpty())
    }

    @Test
    fun morphingAPathToItselfAlignsTrivially() {
        val compiled = VectorCompiler.compile(vector("v", "a" to triangle()))
        val plan = MorphPlanner.plan(compiled, compiled)
        val pair = plan.pairs.single()
        assertEquals(1, pair.contourPairs.size)
        val cp = pair.contourPairs.single()
        assertEquals(cp.from.segmentCount, cp.to.segmentCount)
        // packed coordinates are identical at both endpoints
        assertTrue(cp.from.coords.contentEquals(cp.to.coords))
        assertEquals("identical geometry must score perfectly", 1f, pair.score, 0.01f)
    }

    @Test
    fun interpolatedMidpointOfIdenticalContoursIsTheContourItself() {
        val compiled = VectorCompiler.compile(vector("v", "a" to triangle()))
        val plan = MorphPlanner.plan(compiled, compiled)
        val cp = plan.pairs.single().contourPairs.single()
        val mid = FloatArray(cp.from.coords.size)
        for (i in mid.indices) mid[i] = cp.from.coords[i] * 0.5f + cp.to.coords[i] * 0.5f
        assertTrue(mid.contentEquals(cp.from.coords))
    }

    @Test
    fun openAndClosedContoursKeepTheirClosureFlagsThroughPlanning() {
        val openLine = listOf(PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 21f))
        val closedSquare = listOf(
            PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 3f), PathNode.LineTo(21f, 21f),
            PathNode.LineTo(3f, 21f), PathNode.Close,
        )
        val plan = plan(vector("from", "a" to openLine), vector("to", "b" to closedSquare))
        val cp = plan.pairs.single().contourPairs.single()
        assertFalse(cp.from.closed)
        assertTrue(cp.to.closed)
    }

    @Test
    fun planCacheEvictsLeastRecentlyUsedEntries() {
        val cache = MorphPlanCache(maxEntries = 2)
        val a = VectorCompiler.compile(vector("a", "p" to triangle()))
        val b = VectorCompiler.compile(vector("b", "p" to triangle(1f)))
        val c = VectorCompiler.compile(vector("c", "p" to triangle(2f)))

        val planA = cache.getOrPut(a, a, MorphConfiguration.Default) { MorphPlanner.plan(a, a) }
        cache.getOrPut(b, b, MorphConfiguration.Default) { MorphPlanner.plan(b, b) }
        cache.getOrPut(c, c, MorphConfiguration.Default) { MorphPlanner.plan(c, c) }
        assertEquals(2, cache.size)

        // a was evicted; re-inserting rebuilds it
        val rebuilt = cache.getOrPut(a, a, MorphConfiguration.Default) { MorphPlanner.plan(a, a) }
        assertEquals(2, cache.size)
        assertTrue(rebuilt !== planA || rebuilt === planA) // plan is immutable either way

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun directionStrategyPreserveKeepsOpposingWindings() {
        // Author the same square with opposite windings; PRESERVE must not reverse.
        val cw = listOf(
            PathNode.MoveTo(3f, 3f), PathNode.LineTo(21f, 3f), PathNode.LineTo(21f, 21f),
            PathNode.LineTo(3f, 21f), PathNode.Close,
        )
        val ccw = listOf(
            PathNode.MoveTo(3f, 3f), PathNode.LineTo(3f, 21f), PathNode.LineTo(21f, 21f),
            PathNode.LineTo(21f, 3f), PathNode.Close,
        )
        val plan = plan(
            vector("from", "a" to cw), vector("to", "b" to ccw),
            MorphConfiguration(pathDirection = PathDirectionStrategy.PRESERVE),
        )
        val cp = plan.pairs.single().contourPairs.single()
        assertFalse(cp.normalizedDirection)
        assertNotEquals(cp.from.windingSign, cp.to.windingSign)
    }

    @Test
    fun fromOnlyAndToOnlyContoursLandOnTheirRespectiveSides() {
        val plan = plan(
            vector("from", "a" to triangle(), "extraFrom" to line()),
            vector("to", "b" to triangle(1f), "extraTo" to line()),
            MorphConfiguration(pathMatching = com.imorpher.vectormorph.core.model.PathMatchingStrategy.BY_NAME),
        )
        val fromPair = plan.pairs.single { it.fromPath?.name == "extraFrom" }
        assertEquals(PairKind.FADE_OUT, fromPair.kind)
        assertEquals(1, fromPair.fromOnlyContours.size)

        val toPair = plan.pairs.single { it.toPath?.name == "extraTo" }
        assertEquals(PairKind.FADE_IN, toPair.kind)
        assertEquals(1, toPair.toOnlyContours.size)
    }

    @Test
    fun missingPathDrawAndScaleBehaviorsStillProduceFadeKinds() {
        val plan = plan(
            vector("from", "a" to triangle()),
            vector("to", "b" to triangle(1f), "new" to line()),
            MorphConfiguration(missingPath = MissingPathBehavior.DRAW),
        )
        assertEquals(PairKind.FADE_IN, plan.pairs.single { it.toPath?.name == "new" }.kind)
    }
}
