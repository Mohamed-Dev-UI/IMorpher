package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrowBackUp: ImageVector
    get() {
        if (_ArrowBackUp != null) {
            return _ArrowBackUp!!
        }
        _ArrowBackUp = ImageVector.Builder(
            name = "ArrowBackUp",
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
                moveTo(9f, 14f)
                lineTo(5f, 10f)
                lineTo(9f, 6f)
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 10f)
                horizontalLineTo(16f)
                curveTo(17.061f, 10f, 18.078f, 10.421f, 18.828f, 11.172f)
                curveTo(19.579f, 11.922f, 20f, 12.939f, 20f, 14f)
                curveTo(20f, 15.061f, 19.579f, 16.078f, 18.828f, 16.828f)
                curveTo(18.078f, 17.579f, 17.061f, 18f, 16f, 18f)
                horizontalLineTo(15f)
            }
        }.build()

        return _ArrowBackUp!!
    }

@Suppress("ObjectPropertyName")
private var _ArrowBackUp: ImageVector? = null
