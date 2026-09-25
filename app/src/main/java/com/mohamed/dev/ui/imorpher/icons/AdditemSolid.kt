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

internal val IMorpherIcons.AdditemSolid: ImageVector
    get() {
        if (_AdditemSolid != null) {
            return _AdditemSolid!!
        }
        _AdditemSolid = ImageVector.Builder(
            name = "AdditemSolid",
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
                path(fill = SolidColor(Color(0xFF838383))) {
                    moveTo(18.57f, 7.25f)
                    curveTo(19.83f, 7.25f, 20.913f, 7.564f, 21.675f, 8.326f)
                    curveTo(22.436f, 9.088f, 22.75f, 10.17f, 22.75f, 11.43f)
                    verticalLineTo(18.57f)
                    curveTo(22.75f, 19.83f, 22.436f, 20.912f, 21.675f, 21.674f)
                    curveTo(20.913f, 22.436f, 19.83f, 22.75f, 18.57f, 22.75f)
                    horizontalLineTo(14f)
                    curveTo(12.74f, 22.75f, 11.658f, 22.436f, 10.896f, 21.674f)
                    curveTo(10.135f, 20.912f, 9.82f, 19.83f, 9.82f, 18.57f)
                    verticalLineTo(11.43f)
                    curveTo(9.82f, 10.17f, 10.135f, 9.088f, 10.896f, 8.326f)
                    curveTo(11.658f, 7.564f, 12.74f, 7.25f, 14f, 7.25f)
                    horizontalLineTo(18.57f)
                    close()
                    moveTo(16.5f, 12.62f)
                    curveTo(16.086f, 12.62f, 15.75f, 12.956f, 15.75f, 13.37f)
                    verticalLineTo(14.25f)
                    horizontalLineTo(14.869f)
                    curveTo(14.455f, 14.25f, 14.119f, 14.586f, 14.119f, 15f)
                    curveTo(14.119f, 15.414f, 14.455f, 15.75f, 14.869f, 15.75f)
                    horizontalLineTo(15.75f)
                    verticalLineTo(16.63f)
                    curveTo(15.75f, 17.044f, 16.086f, 17.38f, 16.5f, 17.38f)
                    curveTo(16.914f, 17.38f, 17.25f, 17.044f, 17.25f, 16.63f)
                    verticalLineTo(15.75f)
                    horizontalLineTo(18.129f)
                    curveTo(18.543f, 15.75f, 18.879f, 15.414f, 18.879f, 15f)
                    curveTo(18.879f, 14.586f, 18.543f, 14.25f, 18.129f, 14.25f)
                    horizontalLineTo(17.25f)
                    verticalLineTo(13.37f)
                    curveTo(17.25f, 12.956f, 16.914f, 12.62f, 16.5f, 12.62f)
                    close()
                }
            }
        }.build()

        return _AdditemSolid!!
    }

@Suppress("ObjectPropertyName")
private var _AdditemSolid: ImageVector? = null
