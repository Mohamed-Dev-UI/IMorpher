package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrowUp1: ImageVector
    get() {
        if (_ArrowUp1 != null) {
            return _ArrowUp1!!
        }
        _ArrowUp1 = ImageVector.Builder(
            name = "ArrowUp1",
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
                moveTo(19.92f, 15.05f)
                lineTo(13.4f, 8.53f)
                curveTo(12.63f, 7.76f, 11.37f, 7.76f, 10.6f, 8.53f)
                lineTo(4.08f, 15.05f)
            }
        }.build()

        return _ArrowUp1!!
    }

@Suppress("ObjectPropertyName")
private var _ArrowUp1: ImageVector? = null
