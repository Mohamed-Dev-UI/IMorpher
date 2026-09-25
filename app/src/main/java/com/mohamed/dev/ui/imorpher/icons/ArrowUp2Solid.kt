package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrowUp2Solid: ImageVector
    get() {
        if (_ArrowUp2Solid != null) {
            return _ArrowUp2Solid!!
        }
        _ArrowUp2Solid = ImageVector.Builder(
            name = "ArrowUp2Solid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF838383)),
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(11.99f, 18.002f)
                horizontalLineTo(9.32f)
                curveTo(6.01f, 18.002f, 4.65f, 15.652f, 6.31f, 12.782f)
                lineTo(7.65f, 10.472f)
                lineTo(8.99f, 8.162f)
                curveTo(10.65f, 5.292f, 13.36f, 5.292f, 15.02f, 8.162f)
                lineTo(16.36f, 10.472f)
                lineTo(17.7f, 12.782f)
                curveTo(19.33f, 15.652f, 17.98f, 18.002f, 14.66f, 18.002f)
                horizontalLineTo(11.99f)
                close()
            }
        }.build()

        return _ArrowUp2Solid!!
    }

@Suppress("ObjectPropertyName")
private var _ArrowUp2Solid: ImageVector? = null
