package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrowDown2Outline: ImageVector
    get() {
        if (_ArrowDown2Outline != null) {
            return _ArrowDown2Outline!!
        }
        _ArrowDown2Outline = ImageVector.Builder(
            name = "ArrowDown2Outline",
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
                moveTo(11.99f, 6.01f)
                horizontalLineTo(9.32f)
                curveTo(6.01f, 6.01f, 4.65f, 8.36f, 6.31f, 11.23f)
                lineTo(7.65f, 13.54f)
                lineTo(8.99f, 15.85f)
                curveTo(10.65f, 18.72f, 13.36f, 18.72f, 15.02f, 15.85f)
                lineTo(16.36f, 13.54f)
                lineTo(17.7f, 11.23f)
                curveTo(19.33f, 8.36f, 17.98f, 6.01f, 14.66f, 6.01f)
                lineTo(11.99f, 6.01f)
                close()
            }
        }.build()

        return _ArrowDown2Outline!!
    }

@Suppress("ObjectPropertyName")
private var _ArrowDown2Outline: ImageVector? = null
