package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrowRight2Outline: ImageVector
    get() {
        if (_ArrowRight2Outline != null) {
            return _ArrowRight2Outline!!
        }
        _ArrowRight2Outline = ImageVector.Builder(
            name = "ArrowRight2Outline",
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
                moveTo(6f, 12f)
                verticalLineTo(9.33f)
                curveTo(6f, 6.02f, 8.35f, 4.66f, 11.22f, 6.32f)
                lineTo(13.53f, 7.66f)
                lineTo(15.84f, 9f)
                curveTo(18.71f, 10.66f, 18.71f, 13.37f, 15.84f, 15.03f)
                lineTo(13.53f, 16.37f)
                lineTo(11.22f, 17.71f)
                curveTo(8.35f, 19.34f, 6f, 17.99f, 6f, 14.67f)
                verticalLineTo(12f)
                close()
            }
        }.build()

        return _ArrowRight2Outline!!
    }

@Suppress("ObjectPropertyName")
private var _ArrowRight2Outline: ImageVector? = null
