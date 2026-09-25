package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.AdditemOutline: ImageVector
    get() {
        if (_AdditemOutline != null) {
            return _AdditemOutline!!
        }
        _AdditemOutline = ImageVector.Builder(
            name = "AdditemOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            group(
                clipPathData = PathData {
                    moveTo(0f, 0f)
                    horizontalLineToRelative(24f)
                    verticalLineToRelative(24f)
                    horizontalLineToRelative(-24f)
                    close()
                }
            ) {
                path(
                    stroke = SolidColor(Color(0xFF838383)),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(8f, 16f)
                    horizontalLineTo(5.43f)
                    curveTo(3.14f, 16f, 2f, 14.86f, 2f, 12.57f)
                    verticalLineTo(5.43f)
                    curveTo(2f, 3.14f, 3.14f, 2f, 5.43f, 2f)
                    horizontalLineTo(10f)
                    curveTo(12.29f, 2f, 13.43f, 3.14f, 13.43f, 5.43f)
                }
                path(
                    stroke = SolidColor(Color(0xFF838383)),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(18.57f, 22f)
                    horizontalLineTo(14f)
                    curveTo(11.71f, 22f, 10.57f, 20.86f, 10.57f, 18.57f)
                    verticalLineTo(11.43f)
                    curveTo(10.57f, 9.14f, 11.71f, 8f, 14f, 8f)
                    horizontalLineTo(18.57f)
                    curveTo(20.86f, 8f, 22f, 9.14f, 22f, 11.43f)
                    verticalLineTo(18.57f)
                    curveTo(22f, 20.86f, 20.86f, 22f, 18.57f, 22f)
                    close()
                }
                path(
                    stroke = SolidColor(Color(0xFF838383)),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(14.869f, 15f)
                    horizontalLineTo(18.129f)
                }
                path(
                    stroke = SolidColor(Color(0xFF838383)),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(16.5f, 16.63f)
                    verticalLineTo(13.37f)
                }
            }
        }.build()

        return _AdditemOutline!!
    }

@Suppress("ObjectPropertyName")
private var _AdditemOutline: ImageVector? = null
