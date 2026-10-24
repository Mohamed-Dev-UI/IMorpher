package com.imorpher.vectormorph.core.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.plan.MorphPlanCache
import com.imorpher.vectormorph.core.plan.MorphPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Structural caching contract: compiled vectors are keyed by CONTENT, not identity, so
 * equal-but-distinct `ImageVector` instances share planning work (review finding #2).
 */
class StructuralKeyTest {

    @Test
    fun equalGeometryCompiledFromDistinctInstancesProducesEqualContentKey() {
        val first = VectorCompiler.compile(triangleVector("first"))
        val second = VectorCompiler.compile(triangleVector("second"))

        assertNotEquals("the two literals are distinct instances", first, second)
        assertEquals(first.contentKey, second.contentKey)
    }

    @Test
    fun numericNameDifferenceAloneDoesNotChangeContentKey() {
        val first = VectorCompiler.compile(triangleVector("a"))
        val second = VectorCompiler.compile(triangleVector("b"))

        assertEquals(
            "the ImageVector display name never reaches planning, so it must not split the cache",
            first.contentKey,
            second.contentKey,
        )
    }

    @Test
    fun differentGeometryProducesDifferentContentKey() {
        val first = VectorCompiler.compile(triangleVector("a"))
        val translated = VectorCompiler.compile(triangleVector("a", offset = 2f))

        assertNotEquals(first.contentKey, translated.contentKey)
    }

    @Test
    fun differentPaintProducesDifferentContentKey() {
        val black = VectorCompiler.compile(triangleVector("a", color = Color.Black))
        val red = VectorCompiler.compile(triangleVector("a", color = Color.Red))

        assertNotEquals(black.contentKey, red.contentKey)
    }

    @Test
    fun contentKeyIsStableAcrossReads() {
        val vector = VectorCompiler.compile(triangleVector("stable"))

        assertEquals(vector.contentKey, vector.contentKey)
    }

    @Test
    fun planCacheHitsAcrossEqualButDistinctVectors() {
        val cache = MorphPlanCache()
        val from = VectorCompiler.compile(triangleVector("from"))
        val to = VectorCompiler.compile(triangleVector("to", offset = 1f))

        var builds = 0
        val first = cache.getOrPut(from, to, MorphConfiguration.Default) {
            builds++
            MorphPlanner.plan(from, to)
        }
        val second = cache.getOrPut(
            VectorCompiler.compile(triangleVector("from")),
            VectorCompiler.compile(triangleVector("to", offset = 1f)),
            MorphConfiguration.Default,
        ) {
            builds++
            MorphPlanner.plan(from, to)
        }

        assertEquals("equal content must reuse the cached plan", 1, builds)
        assertSame(first, second)
    }

    private fun triangleVector(
        name: String,
        offset: Float = 0f,
        color: Color = Color.Black,
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = listOf(
                PathNode.MoveTo(3f + offset, 3f),
                PathNode.LineTo(21f, 3f),
                PathNode.LineTo(12f, 21f),
                PathNode.Close,
            ),
            fill = SolidColor(color),
            name = "shape",
        )
    }.build()
}
