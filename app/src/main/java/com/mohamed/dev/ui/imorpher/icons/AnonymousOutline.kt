package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.AnonymousOutline: ImageVector
    get() {
        if (_AnonymousOutline != null) {
            return _AnonymousOutline!!
        }
        _AnonymousOutline = ImageVector.Builder(
            name = "AnonymousOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 15f)
                curveTo(5.343f, 15f, 4f, 16.343f, 4f, 18f)
                curveTo(4f, 19.657f, 5.343f, 21f, 7f, 21f)
                curveTo(8.657f, 21f, 10f, 19.657f, 10f, 18f)
                curveTo(10f, 16.343f, 8.657f, 15f, 7f, 15f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 15f)
                curveTo(15.343f, 15f, 14f, 16.343f, 14f, 18f)
                curveTo(14f, 19.657f, 15.343f, 21f, 17f, 21f)
                curveTo(18.657f, 21f, 20f, 19.657f, 20f, 18f)
                curveTo(20f, 16.343f, 18.657f, 15f, 17f, 15f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14f, 17f)
                horizontalLineTo(10f)
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(22f, 13f)
                curveTo(19.543f, 11.773f, 15.973f, 11f, 12f, 11f)
                curveTo(8.027f, 11f, 4.457f, 11.773f, 2f, 13f)
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 11.5f)
                lineTo(17.942f, 4.712f)
                curveTo(17.727f, 3.328f, 16.223f, 2.578f, 15.009f, 3.249f)
                lineTo(14.394f, 3.589f)
                curveTo(12.902f, 4.414f, 11.098f, 4.414f, 9.606f, 3.589f)
                lineTo(8.991f, 3.249f)
                curveTo(7.777f, 2.578f, 6.273f, 3.328f, 6.058f, 4.712f)
                lineTo(5f, 11.5f)
            }
        }.build()

        return _AnonymousOutline!!
    }

@Suppress("ObjectPropertyName")
private var _AnonymousOutline: ImageVector? = null
