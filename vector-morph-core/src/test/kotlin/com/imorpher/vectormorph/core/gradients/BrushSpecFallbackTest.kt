package com.imorpher.vectormorph.core.gradients

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies BrushSpec.from handles edge cases: SolidColor extraction,
 * null brush, and consumer-rules.pro existence for R8 safety.
 */
class BrushSpecFallbackTest {

    @Test
    fun solidColorBrushExtractsToSolidSpec() {
        val brush = SolidColor(Color.Red)
        val spec = BrushSpec.from(brush)
        assertTrue("SolidColor extracts to BrushSpec.Solid", spec is BrushSpec.Solid)
        assertEquals(Color.Red, (spec as BrushSpec.Solid).color)
    }

    @Test
    fun nullBrushReturnsNull() {
        assertNull(BrushSpec.from(null))
    }

    @Test
    fun consumerRulesFileExistsWithRequiredEntries() {
        // Find the file: try from project root (user.dir) and module root
        val projectRoot = System.getProperty("user.dir")
        val file = java.io.File(projectRoot, "vector-morph-core/consumer-rules.pro")
            .takeIf { it.exists() }
            ?: java.io.File("consumer-rules.pro")
            .takeIf { it.exists() }
            ?: java.io.File(projectRoot, "consumer-rules.pro")
        assertTrue("consumer-rules.pro exists", file.exists())
        val content = file.readText()
        assertTrue("Contains LinearGradient keep", content.contains("LinearGradient"))
        assertTrue("Contains RadialGradient keep", content.contains("RadialGradient"))
        assertTrue("Contains SweepGradient keep", content.contains("SweepGradient"))
        assertTrue("Contains keepclassmembers", content.contains("-keepclassmembers"))
    }
}
