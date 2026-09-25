package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrowLeft2Outline: ImageVector
    get() {
        if (_ArrowLeft2Outline != null) {
            return _ArrowLeft2Outline!!
        }
        _ArrowLeft2Outline = ImageVector.Builder(
            name = "ArrowLeft2Outline",
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
                moveTo(17.993f, 12f)
                verticalLineTo(9.33f)
                curveTo(17.993f, 6.02f, 15.642f, 4.66f, 12.773f, 6.32f)
                lineTo(10.462f, 7.66f)
                lineTo(8.152f, 9f)
                curveTo(5.282f, 10.66f, 5.282f, 13.37f, 8.152f, 15.03f)
                lineTo(10.462f, 16.37f)
                lineTo(12.773f, 17.71f)
                curveTo(15.642f, 19.34f, 17.993f, 17.99f, 17.993f, 14.67f)
                verticalLineTo(12f)
                close()
            }
        }.build()

        return _ArrowLeft2Outline!!
    }

@Suppress("ObjectPropertyName")
private var _ArrowLeft2Outline: ImageVector? = null
