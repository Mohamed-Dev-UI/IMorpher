package com.imorpher.vectormorph.compose

import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.imorpher.vectormorph.core.animation.VectorAnimation as vectorAnimationSpec
import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic rendering regression: a hand-built vector is drawn without XML resources. */
class MorphRenderingGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fixedProgressProducesRepeatablePixelsAndPreservesSemantics() {
        val tint = Color(0xFF12A4D9)
        val builder = ImageVector.Builder("golden", 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
                PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
            ),
            fill = SolidColor(tint),
            name = "body",
        )
        val vector = builder.build()

        composeRule.setContent {
            MorphVector(
                from = vector,
                to = vector,
                progress = 0.37f,
                modifier = Modifier,
                contentDescription = "deterministic icon",
                width = 24.dp,
                height = 24.dp,
            )
        }

        val node = composeRule.onNodeWithContentDescription("deterministic icon")
        val first = node.captureToImage().toPixelMap()
        val second = node.captureToImage().toPixelMap()
        val centerX = first.width / 2
        val centerY = first.height / 2

        assertEquals(tint, first[centerX, centerY])
        // Captures composite onto an opaque surface, so untouched pixels equal that
        // surface color (sampled in the corner), not Color.Transparent.
        val background = first[1, 1]
        assertTrue("corner must stay background", first[1, 1] == background && background != tint)
        assertTrue((0 until first.height).all { y ->
            (0 until first.width).all { x -> first[x, y] == second[x, y] }
        })
    }

    @Test
    fun automaticVectorAnimationStartsAtZeroAndAdvancesToTheEnd() {
        composeRule.mainClock.autoAdvance = false
        val vector = solidVector("autoplay", Color(0xFF12A4D9))
        val animation = vectorAnimationSpec { alpha(0f, 1f) }

        composeRule.setContent {
            VectorAnimation(
                vector = vector,
                animation = animation,
                animationSpec = tween(durationMillis = 200),
                contentDescription = "animated icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        // The capture composites onto an opaque surface; track how far the center pixel
        // has moved from the surface color toward the icon tint instead of reading alpha.
        val background = centerPixel()
        fun blend(pixel: Color): Float {
            val channels = listOf(pixel.red - background.red, pixel.green - background.green, pixel.blue - background.blue)
            val tintDelta = listOf(0x12 / 255f - background.red, 0xA4 / 255f - background.green, 0xD9 / 255f - background.blue)
            val denom = tintDelta.map { it * it }.sum()
            return if (denom <= 1e-6f) 0f
            else channels.zip(tintDelta) { c, t -> c * t }.sum() / denom
        }

        val start = blend(centerPixel())
        assertTrue("one-shot animation must begin transparent: $start", start < 0.02f)

        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()
        val middle = blend(centerPixel())
        assertTrue("one-shot animation must draw an intermediate frame: $middle", middle > 0.05f)
        assertTrue("intermediate frame must not already be complete: $middle", middle < 0.99f)

        composeRule.mainClock.advanceTimeBy(150)
        composeRule.waitForIdle()
        assertEquals(1f, blend(centerPixel()), 0.01f)
    }

    @Test
    fun manualProgressOverridesAutoplayAndInstantMotionStartsComplete() {
        composeRule.mainClock.autoAdvance = false
        val vector = solidVector("manual", Color(0xFF12A4D9))
        val animation = vectorAnimationSpec { alpha(0f, 1f) }
        val useManualProgress = mutableStateOf(true)

        composeRule.setContent {
            if (useManualProgress.value) {
                VectorAnimation(
                    vector = vector,
                    animation = animation,
                    progress = 0.25f,
                    contentDescription = "animated icon",
                    width = 24.dp,
                    height = 24.dp,
                )
            } else {
                VectorAnimation(
                    vector = vector,
                    animation = animation,
                    motionPreference = MotionPreference.INSTANT,
                    contentDescription = "animated icon",
                    width = 24.dp,
                    height = 24.dp,
                )
            }
        }
        composeRule.waitForIdle()
        // Alpha reads are vacuous on opaque captures — project the center pixel onto the
        // (tint − surface) axis to recover the effective alpha of the drawn icon.
        val tint = Color(0xFF12A4D9)
        fun projectedAlpha(): Float {
            val pixels = composeRule.onNodeWithContentDescription("animated icon")
                .captureToImage().toPixelMap()
            val background = pixels[1, 1]
            val center = pixels[pixels.width / 2, pixels.height / 2]
            val td = listOf(tint.red - background.red, tint.green - background.green, tint.blue - background.blue)
            val denom = td.map { it * it }.sum()
            if (denom <= 1e-6f) return 0f
            val cd = listOf(center.red - background.red, center.green - background.green, center.blue - background.blue)
            return cd.zip(td) { c, t -> c * t }.sum() / denom
        }

        assertEquals(0.25f, projectedAlpha(), 0.03f)
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertEquals("manual progress must remain caller-controlled", 0.25f, projectedAlpha(), 0.03f)

        composeRule.runOnIdle { useManualProgress.value = false }
        // With the clock pinned, waitForIdle does not tick a frame — advance so the
        // INSTANT recomposition actually renders before the capture.
        composeRule.mainClock.advanceTimeBy(16)
        composeRule.waitForIdle()
        composeRule.waitForIdle()
        assertEquals(
            "instant motion should render the final frame immediately",
            Color(0xFF12A4D9),
            centerPixel(),
        )
    }

    @Test
    fun morphIconRetargetsFromItsCurrentFrameWhenSelectionChanges() {
        composeRule.mainClock.autoAdvance = false
        val selected = mutableStateOf(false)
        val from = solidVector("from", Color.Red)
        val to = solidVector("to", Color.Blue)

        composeRule.setContent {
            MorphIcon(
                from = from,
                to = to,
                selected = selected.value,
                animationSpec = tween(durationMillis = 300),
                contentDescription = "animated icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()
        assertEquals(Color.Red, centerPixel())

        composeRule.runOnIdle { selected.value = true }
        composeRule.mainClock.advanceTimeBy(120)
        composeRule.waitForIdle()
        val movingToBlue = centerPixel()
        assertTrue(movingToBlue.red > 0f && movingToBlue.blue > 0f)

        composeRule.runOnIdle { selected.value = false }
        composeRule.waitForIdle()
        assertEquals("retargeting must not jump to the old endpoint", movingToBlue, centerPixel())

        // Step the clock in small increments (a single large jump lands mid-ease and is
        // not comparable to the retarget frame) and require a smooth return to the from-icon.
        var returned = false
        repeat(10) {
            composeRule.mainClock.advanceTimeBy(20)
            composeRule.waitForIdle()
            val pixel = centerPixel()
            assertTrue(
                "retarget must stay blended mid-flight, not jump endpoints: $pixel",
                pixel.red in 0f..1f && pixel.blue in 0f..1f,
            )
            if (pixel.red > movingToBlue.red && pixel.blue < movingToBlue.blue) returned = true
        }
        assertTrue("retarget must animate back toward the from-icon", returned)
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        assertEquals("retarget must settle on the from-icon", Color.Red, centerPixel())
    }

    @Test
    fun colorTintTransitionsBetweenEndpoints() {
        composeRule.mainClock.autoAdvance = false
        val vector = solidVector("tinted", Color.Red)
        val tintFrom = Color(0xFF12A4D9)
        val tintTo = Color(0xFF00C853)
        val animation = vectorAnimationSpec {
            at(0f) { alpha = 0.5f }
            at(1f) { alpha = 0.5f }
        }

        composeRule.setContent {
            VectorAnimator(
                animation = VectorAnimationDefinition(vector, animation),
                progress = 0.5f,
                tintFrom = tintFrom,
                tintTo = tintTo,
                contentDescription = "tinted icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithContentDescription("tinted icon").captureToImage().toPixelMap()
        val background = pixels[1, 1]
        val center = pixels[pixels.width / 2, pixels.height / 2]
        // At progress 0.5 the tint sits halfway between the endpoints: the center pixel must
        // project halfway between the surface color and that midpoint (alpha preserved), and
        // never show the icon's own red paint.
        val midpoint = Color(
            tintFrom.red * 0.5f + tintTo.red * 0.5f,
            tintFrom.green * 0.5f + tintTo.green * 0.5f,
            tintFrom.blue * 0.5f + tintTo.blue * 0.5f,
        )
        val projected = blendFraction(center, background, midpoint)
        assertEquals("tint must transition between endpoint colors at the icon's alpha", 0.5f, projected, 0.05f)
        assertTrue("tinted icon must not show red paint", center.red < midpoint.red + 0.1f)
    }

    @Test
    fun defaultGradientBrushTintsWithInfiniteSentinelEndpoints() {
        val vector = solidVector("sentinel-tinted", Color.Red)

        composeRule.setContent {
            // Brush.linearGradient(colors) leaves start/end at Compose's infinite sentinels;
            // interpolating them raw used to crash LinearGradient.nativeCreate.
            MorphVector(
                from = vector,
                to = vector,
                progress = 0.25f,
                tintFrom = Brush.linearGradient(listOf(Color.Red, Color.Blue)),
                tintTo = Brush.linearGradient(listOf(Color.Green, Color.Cyan)),
                contentDescription = "sentinel tinted icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithContentDescription("sentinel tinted icon").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        // Center of a red→blue axis is mid-blend; tintTo's green pulls the blend toward it
        // at progress 0.25 — assert a purple-ish, green-tinted mix rather than any endpoint.
        assertTrue("center must sample the resolved gradient: $center", center.red in 0.3f..0.7f)
        assertTrue("center must sample the resolved gradient: $center", center.blue in 0.3f..0.7f)
    }

    @Test
    fun brushTintTransitionsBetweenEndpointBrushes() {
        val vector = solidVector("gradient-tinted", Color.Red)

        composeRule.setContent {
            MorphVector(
                from = vector,
                to = vector,
                progress = 0.5f,
                tintFrom = Brush.horizontalGradient(listOf(Color.Red, Color.Blue)),
                tintTo = Brush.horizontalGradient(listOf(Color.Yellow, Color.Black)),
                contentDescription = "gradient tinted icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithContentDescription("gradient tinted icon").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        // Same-kind gradients interpolate per stop, so the center samples the lerp of both
        // gradients' midpoints (red↔yellow, blue↔black) — never the icon's solid red paint
        // and never either endpoint look.
        assertTrue("center must blend both gradient endpoints: $center", center.red in 0.6f..1f)
        assertTrue("center must blend both gradient endpoints: $center", center.green in 0.2f..0.65f)
        assertTrue("center must blend both gradient endpoints: $center", center.blue < 0.55f)
    }

    @Test
    fun solidIconInnerSymbolStaysHollowWhileMorphing() {
        val fill = Color(0xFF12A4D9)
        val vector = holedVector("holed", fill)

        composeRule.setContent {
            MorphVector(
                from = vector,
                to = vector,
                progress = 0.5f,
                contentDescription = "holed icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithContentDescription("holed icon").captureToImage().toPixelMap()
        val background = pixels[1, 1]
        // Solid icons encode their inner symbol as an opposite-winding contour: the symbol
        // must stay a transparent hole in the fill, not painted geometry on top of it.
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals("inner symbol must stay a transparent hole: $center", background, center)
        val ring = pixels[pixels.width / 4, pixels.height / 2]
        assertEquals("outer ring must stay filled: $ring", fill, ring)
    }

    @Test
    fun tintPreservesSolidIconInnerSymbolHole() {
        val tint = Color(0xFFE2574C)
        val vector = holedVector("holed-tinted", Color(0xFF12A4D9))

        composeRule.setContent {
            MorphVector(
                from = vector,
                to = vector,
                progress = 0.5f,
                tintFrom = tint,
                tintTo = tint,
                contentDescription = "holed tinted icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithContentDescription("holed tinted icon").captureToImage().toPixelMap()
        val background = pixels[1, 1]
        // Tint repaints every painted pixel but must preserve per-pixel alpha: the symbol
        // inside a solid icon is alpha (a hole), so it must remain background under tint.
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals("tint must keep the inner symbol transparent: $center", background, center)
        val ring = pixels[pixels.width / 4, pixels.height / 2]
        assertEquals("tint must fully paint the outer ring: $ring", 1f, blendFraction(ring, background, tint), 0.02f)
    }

    @Test
    fun outlineToSolidMorphKeepsInnerSymbolHoleOnBothSides() {
        val fill = Color(0xFF12A4D9)
        // Outline style: one contour wound so the shape reads as a ring. Solid style:
        // outer contour (opposite winding) + inner symbol contour punching the hole.
        val outline = outlinedVector("outline", fill)
        val solid = solidHoledVector("solid-holed", fill)

        composeRule.setContent {
            MorphVector(
                from = outline,
                to = solid,
                progress = 0.5f,
                contentDescription = "cross-style icon",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithContentDescription("cross-style icon").captureToImage().toPixelMap()
        val background = pixels[1, 1]
        // The inner symbol is an unmatched hole contour on the solid side: it is CARVED
        // out of the fill (never painted). The morphing geometry covers the symbol region
        // throughout, so the carve dissolves it linearly: a 50% blend mid-morph, exactly
        // transparent at the solid endpoint (asserted below).
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals(
            "symbol region must dissolve via the carve, not paint over: $center",
            0.5f, blendFraction(center, background, fill), 0.05f,
        )
        val ring = pixels[pixels.width / 4, pixels.height / 2]
        assertEquals("outer ring must stay filled mid-morph: $ring", fill, ring)

        // Endpoint: the solid side must render with its complete hole, not a filled symbol.
        composeRule.setContent {
            MorphVector(
                from = outline,
                to = solid,
                progress = 1f,
                contentDescription = "cross-style icon end",
                width = 24.dp,
                height = 24.dp,
            )
        }
        composeRule.waitForIdle()

        val endPixels = composeRule.onNodeWithContentDescription("cross-style icon end").captureToImage().toPixelMap()
        val endBackground = endPixels[1, 1]
        val endCenter = endPixels[endPixels.width / 2, endPixels.height / 2]
        assertEquals("solid endpoint must keep the inner symbol transparent: $endCenter", endBackground, endCenter)
        val endRing = endPixels[endPixels.width / 4, endPixels.height / 2]
        assertEquals("solid endpoint must keep the ring filled: $endRing", fill, endRing)
    }

    /** A single filled path of two contours: outer square (CW) + inner square hole (CCW). */
    private fun holedVector(name: String, color: Color): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
                PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
                PathNode.MoveTo(9f, 9f), PathNode.LineTo(9f, 15f),
                PathNode.LineTo(15f, 15f), PathNode.LineTo(15f, 9f), PathNode.Close,
            ),
            fill = SolidColor(color),
            name = "body",
        )
        return builder.build()
    }

    /** One contour only (no hole), wound like the solid style's enclosing contour. */
    private fun outlinedVector(name: String, color: Color): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
                PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
            ),
            fill = SolidColor(color),
            name = "body",
        )
        return builder.build()
    }

    /** Solid style with a hole: outer contour CW + inner square contour CCW. */
    private fun solidHoledVector(name: String, color: Color): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
                PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
                PathNode.MoveTo(9f, 9f), PathNode.LineTo(9f, 15f),
                PathNode.LineTo(15f, 15f), PathNode.LineTo(15f, 9f), PathNode.Close,
            ),
            fill = SolidColor(color),
            name = "body",
        )
        return builder.build()
    }

    /** Projects [pixel] onto the background→tint axis: 0 = background, 1 = full tint. */
    private fun blendFraction(pixel: Color, background: Color, tint: Color): Float {
        val delta = listOf(tint.red - background.red, tint.green - background.green, tint.blue - background.blue)
        val denom = delta.map { it * it }.sum()
        if (denom <= 1e-6f) return 0f
        val channels = listOf(pixel.red - background.red, pixel.green - background.green, pixel.blue - background.blue)
        return channels.zip(delta) { c, d -> c * d }.sum() / denom
    }

    private fun centerPixel(): Color {
        val pixels = composeRule.onNodeWithContentDescription("animated icon")
            .captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun solidVector(name: String, color: Color): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = listOf(
                PathNode.MoveTo(4f, 4f), PathNode.LineTo(20f, 4f),
                PathNode.LineTo(20f, 20f), PathNode.LineTo(4f, 20f), PathNode.Close,
            ),
            fill = SolidColor(color),
            name = "body",
        )
        return builder.build()
    }
}
