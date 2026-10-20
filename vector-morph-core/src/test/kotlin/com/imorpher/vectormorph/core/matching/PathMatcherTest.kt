package com.imorpher.vectormorph.core.matching

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.PathMatchingStrategy
import com.imorpher.vectormorph.core.model.PathMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Path pairing strategies over compiled vectors. */
class PathMatcherTest {

    private fun triangle(offset: Float = 0f) = listOf(
        PathNode.MoveTo(3f + offset, 3f),
        PathNode.LineTo(21f - offset, 3f),
        PathNode.LineTo(12f, 21f - offset),
        PathNode.Close,
    )

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    private fun bigSquare() = listOf(
        PathNode.MoveTo(2f, 2f), PathNode.LineTo(22f, 2f), PathNode.LineTo(22f, 22f),
        PathNode.LineTo(2f, 22f), PathNode.Close,
    )

    private fun smallSquare() = listOf(
        PathNode.MoveTo(9f, 9f), PathNode.LineTo(15f, 9f), PathNode.LineTo(15f, 15f),
        PathNode.LineTo(9f, 15f), PathNode.Close,
    )

    private fun vector(
        name: String,
        vararg paths: Pair<String, List<PathNode>>,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { (pathName, pathData) ->
            builder.addPath(pathData = pathData, fill = SolidColor(Color.Black), name = pathName)
        }
        return builder.build()
    }

    private fun match(
        strategy: PathMatchingStrategy,
        from: ImageVector,
        to: ImageVector,
        mappings: List<PathMapping> = emptyList(),
    ): Pair<List<PathMatch>, List<String>> =
        PathMatcher.match(VectorCompiler.compile(from), VectorCompiler.compile(to), strategy, mappings)

    // ------------------------------------------------------------------ strategies

    @Test
    fun byNamePairsSharedNamesAndFadesTheRest() {
        val from = vector("from", "outline" to triangle(), "detail" to line())
        val to = vector("to", "outline" to triangle(2f), "extra" to bigSquare())

        val (matches, warnings) = match(PathMatchingStrategy.BY_NAME, from, to)

        val paired = matches.single { it.from?.name == "outline" }
        assertEquals("outline", paired.to?.name)
        assertEquals("MORPH", paired.kindDescriptor)
        assertNotNull(matches.singleOrNull { it.from?.name == "detail" && it.to == null })
        assertNotNull(matches.singleOrNull { it.from == null && it.to?.name == "extra" })
        assertTrue(warnings.any { it.contains("no target counterpart") })
        assertTrue(warnings.any { it.contains("no source counterpart") })
    }

    @Test
    fun byIndexPairsDeclarationOrderAndWarnsOnCountMismatch() {
        val from = vector("from", "a" to triangle(), "b" to line())
        val to = vector("to", "x" to bigSquare(), "y" to triangle(1f), "z" to smallSquare())

        val (matches, warnings) = match(PathMatchingStrategy.BY_INDEX, from, to)

        assertEquals("x", matches[0].to?.name)
        assertEquals("y", matches[1].to?.name)
        assertNull(matches[2].from)
        assertEquals("z", matches[2].to?.name)
        assertTrue(warnings.any { it.contains("different path counts") })
    }

    @Test
    fun byCentroidPairsPathsByProximity() {
        // Source has top and bottom squares; target swaps their names but keeps positions.
        val from = vector(
            "from",
            "top" to smallSquare(), // centroid near y=12? place explicitly below
        )
        val builder = ImageVector.Builder("from", 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(9f, 2f), PathNode.LineTo(15f, 2f), PathNode.LineTo(15f, 8f),
                PathNode.LineTo(9f, 8f), PathNode.Close,
            ),
            fill = SolidColor(Color.Black), name = "top",
        )
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(9f, 16f), PathNode.LineTo(15f, 16f), PathNode.LineTo(15f, 22f),
                PathNode.LineTo(9f, 22f), PathNode.Close,
            ),
            fill = SolidColor(Color.Black), name = "bottom",
        )
        val toBuilder = ImageVector.Builder("to", 24.dp, 24.dp, 24f, 24f)
        toBuilder.addPath(
            pathData = listOf(
                PathNode.MoveTo(9f, 15f), PathNode.LineTo(15f, 15f), PathNode.LineTo(15f, 21f),
                PathNode.LineTo(9f, 21f), PathNode.Close,
            ),
            fill = SolidColor(Color.Black), name = "renamed-bottom",
        )
        toBuilder.addPath(
            pathData = listOf(
                PathNode.MoveTo(9f, 3f), PathNode.LineTo(15f, 3f), PathNode.LineTo(15f, 9f),
                PathNode.LineTo(9f, 9f), PathNode.Close,
            ),
            fill = SolidColor(Color.Black), name = "renamed-top",
        )

        val (matches, _) = match(
            PathMatchingStrategy.BY_CENTROID,
            builder.build(), toBuilder.build(),
        )

        assertEquals("renamed-top", matches.single { it.from?.name == "top" }.to?.name)
        assertEquals("renamed-bottom", matches.single { it.from?.name == "bottom" }.to?.name)
    }

    @Test
    fun byAreaPairsPathsOfMatchingSize() {
        val from = vector("from", "big" to bigSquare(), "small" to smallSquare())
        val to = vector("to", "tiny" to smallSquare(), "huge" to bigSquare())

        val (matches, _) = match(PathMatchingStrategy.BY_AREA, from, to)

        assertEquals("huge", matches.single { it.from?.name == "big" }.to?.name)
        assertEquals("tiny", matches.single { it.from?.name == "small" }.to?.name)
    }

    @Test
    fun customMappingToMissingTargetFadesTheSource() {
        val from = vector("from", "outline" to triangle())
        val to = vector("to", "body" to bigSquare())

        val (matches, warnings) = match(
            PathMatchingStrategy.CUSTOM, from, to,
            mappings = listOf(PathMapping("outline", "nonexistent")),
        )

        // the source fades out and the target fades in: two complementary pairs
        assertEquals(2, matches.size)
        val orphanSource = matches.single { it.from != null }
        assertNull(orphanSource.to)
        assertEquals("FADE_OUT", orphanSource.kindDescriptor)
        val orphanTarget = matches.single { it.to != null }
        assertNull(orphanTarget.from)
        assertTrue(warnings.any { it.contains("target path not found") })
    }

    @Test
    fun customMappingsPairExplicitlyAndLeaveUnmappedSidesToFades() {
        val from = vector("from", "outline" to triangle(), "detail" to line())
        val to = vector("to", "body" to bigSquare())

        val (matches, _) = match(
            PathMatchingStrategy.CUSTOM, from, to,
            mappings = listOf(PathMapping("outline", "body")),
        )

        assertEquals("body", matches.single { it.from?.name == "outline" }.to?.name)
        assertNotNull(matches.singleOrNull { it.from?.name == "detail" && it.to == null })
    }

    @Test
    fun autoRejectsIndexPairingWhenGeometryDisagrees() {
        // Same path count, but wildly different sizes: index order pairs big→small.
        val from = vector("from", "bigOne" to bigSquare(), "smallOne" to smallSquare())
        val to = vector("to", "smallTwo" to smallSquare(), "bigTwo" to bigSquare())

        val (matches, _) = match(PathMatchingStrategy.AUTO, from, to)

        assertEquals("bigTwo", matches.single { it.from?.name == "bigOne" }.to?.name)
        assertEquals("smallTwo", matches.single { it.from?.name == "smallOne" }.to?.name)
    }

    @Test
    fun autoUsesIndexPairingForCompatibleCounts() {
        val from = vector("from", "a" to triangle(), "b" to line())
        val to = vector("to", "x" to triangle(1f), "y" to line())

        val (matches, _) = match(PathMatchingStrategy.AUTO, from, to)

        assertEquals("x", matches[0].to?.name)
        assertEquals("y", matches[1].to?.name)
    }

    // ------------------------------------------------------------------ scoring

    @Test
    fun scorePairRatesIdenticalShapesHighest() {
        val compiled = VectorCompiler.compile(vector("v", "a" to triangle(), "b" to bigSquare()))
        val a = compiled.paths[0]
        val b = compiled.paths[1]

        val identical = PathMatcher.scorePair(a, a)
        val different = PathMatcher.scorePair(a, b)
        val orphan = PathMatcher.scorePair(a, null)

        assertTrue(identical > 0.95f)
        assertTrue("unrelated shapes should score lower: $different", different < identical)
        assertEquals(0f, orphan, 0f)
    }
}
