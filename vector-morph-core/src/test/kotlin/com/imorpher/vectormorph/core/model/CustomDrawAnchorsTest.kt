package com.imorpher.vectormorph.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 5 probes (start/direction coordination plan rule 6): [MorphConfiguration.customDrawAnchors]
 * carries the `PathStart.CUSTOM` **draw** anchors, split out of [MorphConfiguration.customStartOffsets]
 * (which reverts to morph-alignment-only semantics while staying a legacy draw-anchor fallback
 * for one release). The renderer consumes both maps with precedence
 * `customDrawAnchors[from] ?: customDrawAnchors[to] ?: customStartOffsets[from] ?:
 * customStartOffsets[to] ?: 0` — that live behavior is pinned by the compose-side pixel probes
 * (`DrawAnchorMapApplicationTest`); the helper itself is renderer-private, so these core probes
 * stay at the config level: field default, map contents, and data-class equality coexistence.
 */
class CustomDrawAnchorsTest {

    @Test
    fun defaultIsEmptyLegacyMapsUnchanged() {
        val config = MorphConfiguration()
        assertTrue(config.customDrawAnchors.isEmpty())
        assertEquals(emptyMap<String, Float>(), config.customStartOffsets)
    }

    @Test
    fun drawAnchorMapPersists() {
        val config = MorphConfiguration(customDrawAnchors = mapOf("p" to 0.75f))
        assertEquals(0.75f, config.customDrawAnchors.getValue("p"))
    }

    /** Both maps ride the same data class independently; neither moves the other in `equals`. */
    @Test
    fun bothMapsCoexistInEquality() {
        val both = MorphConfiguration(
            customStartOffsets = mapOf("p" to 0.5f),
            customDrawAnchors = mapOf("p" to 0.75f),
        )
        assertEquals(both, MorphConfiguration(
            customStartOffsets = mapOf("p" to 0.5f),
            customDrawAnchors = mapOf("p" to 0.75f),
        ))
        assertNotEquals(both, MorphConfiguration(customStartOffsets = mapOf("p" to 0.5f)))
        assertNotEquals(both, MorphConfiguration(customDrawAnchors = mapOf("p" to 0.75f)))
        assertNotEquals(
            MorphConfiguration(customStartOffsets = mapOf("p" to 0.5f)),
            MorphConfiguration(customDrawAnchors = mapOf("p" to 0.5f)),
        )
    }
}
