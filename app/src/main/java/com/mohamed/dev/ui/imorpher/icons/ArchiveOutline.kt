package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.ArchiveOutline: ImageVector
    get() {
        if (_ArchiveOutline != null) {
            return _ArchiveOutline!!
        }
        _ArchiveOutline = ImageVector.Builder(
            name = "ArchiveOutline",
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
                moveTo(9f, 22f)
                horizontalLineTo(15f)
                curveTo(20f, 22f, 22f, 20f, 22f, 15f)
                verticalLineTo(9f)
                curveTo(22f, 4f, 20f, 2f, 15f, 2f)
                horizontalLineTo(9f)
                curveTo(4f, 2f, 2f, 4f, 2f, 9f)
                verticalLineTo(15f)
                curveTo(2f, 20f, 4f, 22f, 9f, 22f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 7.75f)
                verticalLineTo(14.5f)
                curveTo(18f, 13.4f, 17.1f, 12.5f, 16f, 12.5f)
                horizontalLineTo(8f)
                curveTo(6.9f, 12.5f, 6f, 13.4f, 6f, 14.5f)
                verticalLineTo(7.75f)
                curveTo(6f, 6.65f, 6.9f, 5.75f, 8f, 5.75f)
                horizontalLineTo(16f)
                curveTo(17.1f, 5.75f, 18f, 6.65f, 18f, 7.75f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 15.75f)
                horizontalLineTo(18f)
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 15.75f)
                horizontalLineTo(5f)
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 14f)
                verticalLineTo(11f)
                curveTo(18f, 9.9f, 17.1f, 9f, 16f, 9f)
                horizontalLineTo(8f)
                curveTo(6.9f, 9f, 6f, 9.9f, 6f, 11f)
                verticalLineTo(14f)
            }
            path(
                stroke = SolidColor(Color(0xFF838383)),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 14.5f)
                verticalLineTo(15.75f)
                horizontalLineTo(14.5f)
                curveTo(14.5f, 17.13f, 13.38f, 18.25f, 12f, 18.25f)
                curveTo(10.62f, 18.25f, 9.5f, 17.13f, 9.5f, 15.75f)
                horizontalLineTo(6f)
                verticalLineTo(14.5f)
                curveTo(6f, 13.4f, 6.9f, 12.5f, 8f, 12.5f)
                horizontalLineTo(16f)
                curveTo(17.1f, 12.5f, 18f, 13.4f, 18f, 14.5f)
                close()
            }
        }.build()

        return _ArchiveOutline!!
    }

@Suppress("ObjectPropertyName")
private var _ArchiveOutline: ImageVector? = null
