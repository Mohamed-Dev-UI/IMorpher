package com.imorpher.vectormorph.core.geometry

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.plan.MorphPlanner
import com.imorpher.vectormorph.core.plan.PairKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contract for the Compose `Shape`/`Outline` half of the geometry adapter. Arbitrary `Path`
 * sampling is exercised under Robolectric in the compose module (Android `PathMeasure`).
 */
class PathGeometryTest {

    private val density = Density(1f, 1f)

    private class FixedShape(private val outline: Outline) : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline = outline
    }

    // ------------------------------------------------------------------ exact outlines

    @Test
    fun rectangleOutlineBecomesFourExactLines() {
        val rect = Rect(2f, 4f, 22f, 14f)
        val result = PathGeometry.contours(Outline.Rectangle(rect))

        assertTrue(result.warnings.isEmpty())
        assertEquals(0, result.openContourCount)
        val contour = result.contours.single()
        assertTrue(contour.closed)
        assertEquals(4, contour.segments.size)
        assertEquals(2f, contour.startX, 0f)
        assertEquals(4f, contour.startY, 0f)
        val bounds = bounds(contour)
        assertEquals(2f, bounds[0], 0f)
        assertEquals(4f, bounds[1], 0f)
        assertEquals(22f, bounds[2], 0f)
        assertEquals(14f, bounds[3], 0f)
        assertEquals(20f * 10f, abs(signedArea(contour)), 0.01f)
    }

    @Test
    fun roundedRectangleKeepsCornersExact() {
        val roundRect = RoundRect(
            Rect(0f, 0f, 40f, 30f),
            topLeft = CornerRadius(8f, 8f),
            topRight = CornerRadius(8f, 8f),
            bottomRight = CornerRadius(8f, 8f),
            bottomLeft = CornerRadius(8f, 8f),
        )
        val result = PathGeometry.contours(Outline.Rounded(roundRect))

        assertTrue(result.warnings.isEmpty())
        val contour = result.contours.single()
        assertTrue(contour.closed)
        // Four edges + four quarter-ellipse corners; every edge has positive length.
        assertEquals(8, contour.segments.size)
        assertEquals(40f, bounds(contour)[2], 0f)
        assertEquals(30f, bounds(contour)[3], 0f)
        // Corner starts after the top-left radius.
        assertEquals(8f, contour.startX, 0f)
        assertEquals(0f, contour.startY, 0f)
    }

    @Test
    fun fullyRoundedSquareBecomesFourArcSegments() {
        val radius = CornerRadius(50f, 50f)
        val roundRect = RoundRect(
            Rect(0f, 0f, 100f, 100f),
            topLeft = radius,
            topRight = radius,
            bottomRight = radius,
            bottomLeft = radius,
        )
        val result = PathGeometry.contours(Outline.Rounded(roundRect))

        // Zero-length straight edges collapse; only the four quarter-ellipse corners remain.
        val contour = result.contours.single()
        assertEquals(4, contour.segments.size)
        assertEquals(100f, bounds(contour)[2], 0f)
        assertEquals(100f, bounds(contour)[3], 0f)
        // Circle area within the cubic approximation.
        assertEquals(100f * 100f * Math.PI.toFloat() / 4f, abs(signedArea(contour, 32)), 20f)
    }

    @Test
    fun zeroRadiusRoundedRectangleIsAPlainRectangle() {
        val roundRect = RoundRect(
            Rect(1f, 2f, 11f, 7f),
            topLeft = CornerRadius.Zero,
            topRight = CornerRadius.Zero,
            bottomRight = CornerRadius.Zero,
            bottomLeft = CornerRadius.Zero,
        )
        val contour = PathGeometry.contours(Outline.Rounded(roundRect)).contours.single()

        assertEquals(4, contour.segments.size)
        assertTrue(contour.segments.all { abs(it.x1 - it.x0) + abs(it.y1 - it.y0) > 0f })
    }

    // ------------------------------------------------------------------ shapes + reports

    @Test
    fun shapeIsResolvedAtTheRequestedSize() {
        val shape = FixedShape(Outline.Rectangle(Rect(0f, 0f, 48f, 24f)))
        val result = PathGeometry.contours(
            shape,
            Size(48f, 24f),
            LayoutDirection.Ltr,
            density,
        )

        assertEquals(48f, bounds(result.contours.single())[2], 0f)
        assertEquals(24f, bounds(result.contours.single())[3], 0f)
    }

    @Test
    fun zeroSizedShapeReportsAndProducesNoGeometry() {
        val shape = FixedShape(Outline.Rectangle(Rect.Zero))
        val result = PathGeometry.contours(
            shape,
            Size(0f, 40f),
            LayoutDirection.Ltr,
            density,
        )

        assertTrue(result.isEmpty)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("layout size"))
    }

    @Test
    fun shapeThrowingDuringOutlineResolutionIsReported() {
        val shape = object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline = error("nope")
        }
        val result = PathGeometry.contours(shape, Size(10f, 10f), LayoutDirection.Ltr, density)

        assertTrue(result.isEmpty)
        assertTrue(result.warnings.single().contains("createOutline failed"))
    }

    // ------------------------------------------------------------------ prepared vectors

    @Test
    fun preparedVectorCarriesViewportPaintAndFeatures() {
        val geometry = PathGeometry.contours(Outline.Rectangle(Rect(0f, 0f, 20f, 10f)))
        val vector = PathGeometry.preparedVector(
            geometry = geometry,
            viewportWidth = 24f,
            viewportHeight = 24f,
            name = "card",
            fill = SolidColor(Color.Red),
            fillAlpha = 0.75f,
        )

        assertEquals(24f, vector.viewportWidth, 0f)
        assertEquals(24f, vector.viewportHeight, 0f)
        assertEquals("card", vector.name)
        val path = vector.paths.single()
        assertEquals("card", path.name)
        assertEquals(0, path.index)
        assertTrue(path.fill is BrushSpec.Solid)
        assertEquals(0.75f, path.fillAlpha, 0f)
        assertEquals(20f * 10f, path.area, 0.01f)
        assertEquals(60f, path.totalLength, 0.01f)
        assertNotNull(vector.contentKey)
    }

    @Test
    fun preparedVectorCarriesGradientFillAndStrokePaint() {
        val geometry = PathGeometry.contours(Outline.Rectangle(Rect(0f, 0f, 20f, 10f)))
        val fill = Brush.linearGradient(
            colors = listOf(Color.Red, Color.Blue),
            start = Offset(0f, 0f),
            end = Offset(20f, 0f),
        )
        val vector = PathGeometry.preparedVector(
            geometry = geometry,
            viewportWidth = 24f,
            viewportHeight = 24f,
            name = "stroked-card",
            fill = fill,
            fillAlpha = 0.6f,
            stroke = SolidColor(Color.Black),
            strokeAlpha = 0.75f,
            strokeWidth = 3f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 2f,
            pathFillType = PathFillType.EvenOdd,
        )

        val path = vector.paths.single()
        val fillSpec = path.fill as BrushSpec.Linear
        assertEquals(listOf(Color.Red, Color.Blue), fillSpec.colors)
        assertEquals(0f, fillSpec.startX, 0f)
        assertEquals(20f, fillSpec.endX, 0f)
        assertTrue(path.stroke is BrushSpec.Solid)
        assertEquals(0.6f, path.fillAlpha, 0f)
        assertEquals(0.75f, path.strokeAlpha, 0f)
        assertEquals(3f, path.strokeWidth, 0f)
        assertEquals(StrokeCap.Round, path.strokeLineCap)
        assertEquals(StrokeJoin.Round, path.strokeLineJoin)
        assertEquals(2f, path.strokeLineMiter, 0f)
        assertEquals(PathFillType.EvenOdd, path.pathFillType)
    }

    @Test
    fun adapterVectorsPlanAsAGeometryMorph() {
        val from = PathGeometry.preparedVector(
            PathGeometry.contours(Outline.Rectangle(Rect(0f, 0f, 24f, 24f))),
            24f, 24f, name = "from", fill = SolidColor(Color.Red),
        )
        val radius = CornerRadius(12f, 12f)
        val to = PathGeometry.preparedVector(
            PathGeometry.contours(
                Outline.Rounded(
                    RoundRect(
                        Rect(0f, 0f, 24f, 24f),
                        topLeft = radius,
                        topRight = radius,
                        bottomRight = radius,
                        bottomLeft = radius,
                    ),
                ),
            ),
            24f, 24f, name = "to", fill = SolidColor(Color.Blue),
        )

        val plan = MorphPlanner.plan(from, to, MorphConfiguration.Default)

        assertEquals(1, plan.pairs.size)
        assertEquals(PairKind.MORPH, plan.pairs.single().kind)
        assertTrue(plan.pairs.single().contourPairs.isNotEmpty())
    }

    @Test
    fun emptyGeometryBecomesAnEmptyVectorSoPlanningReportsTheFallback() {
        val geometry = PathGeometry.contours(Outline.Rectangle(Rect.Zero))
        val vector = PathGeometry.preparedVector(geometry, 24f, 24f, fill = SolidColor(Color.Black))

        assertTrue(vector.paths.isEmpty())
    }

    @Test
    fun invalidViewportIsCoercedToAUsableCoordinateSpace() {
        val geometry = PathGeometry.contours(Outline.Rectangle(Rect(0f, 0f, 10f, 10f)))
        val vector = PathGeometry.preparedVector(geometry, viewportWidth = 0f, viewportHeight = -1f)

        assertEquals(1f, vector.viewportWidth, 0f)
        assertEquals(1f, vector.viewportHeight, 0f)
    }
}
