package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val IMorpherIcons.AddCircleSolid: ImageVector
    get() {
        if (_AddCircleSolid != null) {
            return _AddCircleSolid!!
        }
        _AddCircleSolid = ImageVector.Builder(
            name = "AddCircleSolid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(12f, 1.5f)
                curveTo(17.776f, 1.5f, 22.5f, 6.224f, 22.5f, 12f)
                curveTo(22.5f, 17.776f, 17.776f, 22.5f, 12f, 22.5f)
                curveTo(6.224f, 22.5f, 1.5f, 17.776f, 1.5f, 12f)
                curveTo(1.5f, 6.224f, 6.224f, 1.5f, 12f, 1.5f)
                close()
                moveTo(12f, 7.5f)
                curveTo(11.724f, 7.5f, 11.5f, 7.724f, 11.5f, 8f)
                verticalLineTo(11.5f)
                horizontalLineTo(8f)
                curveTo(7.724f, 11.5f, 7.5f, 11.724f, 7.5f, 12f)
                curveTo(7.5f, 12.276f, 7.724f, 12.5f, 8f, 12.5f)
                horizontalLineTo(11.5f)
                verticalLineTo(16f)
                curveTo(11.5f, 16.276f, 11.724f, 16.5f, 12f, 16.5f)
                curveTo(12.276f, 16.5f, 12.5f, 16.276f, 12.5f, 16f)
                verticalLineTo(12.5f)
                horizontalLineTo(16f)
                curveTo(16.276f, 12.5f, 16.5f, 12.276f, 16.5f, 12f)
                curveTo(16.5f, 11.724f, 16.276f, 11.5f, 16f, 11.5f)
                horizontalLineTo(12.5f)
                verticalLineTo(8f)
                curveTo(12.5f, 7.724f, 12.276f, 7.5f, 12f, 7.5f)
                close()
            }
        }.build()

        return _AddCircleSolid!!
    }

@Suppress("ObjectPropertyName")
private var _AddCircleSolid: ImageVector? = null
