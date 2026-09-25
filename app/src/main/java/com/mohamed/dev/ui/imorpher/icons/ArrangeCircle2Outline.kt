package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArrangeCircle2Outline: ImageVector
    get() {
        if (_ArrangeCircle2Outline != null) {
            return _ArrangeCircle2Outline!!
        }
        _ArrangeCircle2Outline = ImageVector.Builder(
            name = "ArrangeCircle2Outline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(14.11f, 17.61f)
                curveTo(13.92f, 17.61f, 13.73f, 17.54f, 13.58f, 17.39f)
                curveTo(13.29f, 17.1f, 13.29f, 16.62f, 13.58f, 16.33f)
                lineTo(16.62f, 13.29f)
                curveTo(16.91f, 13f, 17.39f, 13f, 17.68f, 13.29f)
                curveTo(17.97f, 13.58f, 17.97f, 14.06f, 17.68f, 14.35f)
                lineTo(14.64f, 17.39f)
                curveTo(14.5f, 17.53f, 14.31f, 17.61f, 14.11f, 17.61f)
                close()
            }
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(17.15f, 14.57f)
                horizontalLineTo(6.84f)
                curveTo(6.43f, 14.57f, 6.09f, 14.23f, 6.09f, 13.82f)
                curveTo(6.09f, 13.41f, 6.43f, 13.07f, 6.84f, 13.07f)
                horizontalLineTo(17.15f)
                curveTo(17.56f, 13.07f, 17.9f, 13.41f, 17.9f, 13.82f)
                curveTo(17.9f, 14.23f, 17.57f, 14.57f, 17.15f, 14.57f)
                close()
            }
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(6.85f, 10.93f)
                curveTo(6.66f, 10.93f, 6.47f, 10.86f, 6.32f, 10.71f)
                curveTo(6.03f, 10.42f, 6.03f, 9.94f, 6.32f, 9.65f)
                lineTo(9.36f, 6.61f)
                curveTo(9.65f, 6.32f, 10.13f, 6.32f, 10.42f, 6.61f)
                curveTo(10.71f, 6.9f, 10.71f, 7.38f, 10.42f, 7.67f)
                lineTo(7.38f, 10.71f)
                curveTo(7.23f, 10.86f, 7.04f, 10.93f, 6.85f, 10.93f)
                close()
            }
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(17.15f, 10.93f)
                horizontalLineTo(6.84f)
                curveTo(6.43f, 10.93f, 6.09f, 10.59f, 6.09f, 10.18f)
                curveTo(6.09f, 9.77f, 6.43f, 9.43f, 6.84f, 9.43f)
                horizontalLineTo(17.15f)
                curveTo(17.56f, 9.43f, 17.9f, 9.77f, 17.9f, 10.18f)
                curveTo(17.9f, 10.59f, 17.57f, 10.93f, 17.15f, 10.93f)
                close()
            }
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(12f, 22.75f)
                curveTo(6.07f, 22.75f, 1.25f, 17.93f, 1.25f, 12f)
                curveTo(1.25f, 6.07f, 6.07f, 1.25f, 12f, 1.25f)
                curveTo(17.93f, 1.25f, 22.75f, 6.07f, 22.75f, 12f)
                curveTo(22.75f, 17.93f, 17.93f, 22.75f, 12f, 22.75f)
                close()
                moveTo(12f, 2.75f)
                curveTo(6.9f, 2.75f, 2.75f, 6.9f, 2.75f, 12f)
                curveTo(2.75f, 17.1f, 6.9f, 21.25f, 12f, 21.25f)
                curveTo(17.1f, 21.25f, 21.25f, 17.1f, 21.25f, 12f)
                curveTo(21.25f, 6.9f, 17.1f, 2.75f, 12f, 2.75f)
                close()
            }
        }.build()

        return _ArrangeCircle2Outline!!
    }

@Suppress("ObjectPropertyName")
private var _ArrangeCircle2Outline: ImageVector? = null
