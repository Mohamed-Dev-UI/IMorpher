package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Filled counterpart of SealOutline — same NAMED paths (`ring`, `cross`, `dot`) as
 * SealOutline but with solid fills and no clip, so the outline→solid pair morphs
 * path-to-path by name and hands its mask over at the midpoint.
 */
internal val IMorpherIcons.SealSolid: ImageVector
    get() {
        if (_SealSolid != null) {
            return _SealSolid!!
        }
        _SealSolid = ImageVector.Builder(
            name = "SealSolid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                name = "ring",
                fill = SolidColor(Color(0xFF838383)),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(12f, 2.5f)
                curveTo(17.25f, 2.5f, 21.5f, 6.75f, 21.5f, 12f)
                curveTo(21.5f, 17.25f, 17.25f, 21.5f, 12f, 21.5f)
                curveTo(6.75f, 21.5f, 2.5f, 17.25f, 2.5f, 12f)
                curveTo(2.5f, 6.75f, 6.75f, 2.5f, 12f, 2.5f)
                close()
                moveTo(12f, 5f)
                curveTo(15.87f, 5f, 19f, 8.13f, 19f, 12f)
                curveTo(19f, 15.87f, 15.87f, 19f, 12f, 19f)
                curveTo(8.13f, 19f, 5f, 15.87f, 5f, 12f)
                curveTo(5f, 8.13f, 8.13f, 5f, 12f, 5f)
                close()
            }
            path(
                name = "cross",
                fill = SolidColor(Color(0xFF838383))
            ) {
                moveTo(10.5f, 7f)
                horizontalLineTo(13.5f)
                verticalLineTo(10.5f)
                horizontalLineTo(17f)
                verticalLineTo(13.5f)
                horizontalLineTo(13.5f)
                verticalLineTo(17f)
                horizontalLineTo(10.5f)
                verticalLineTo(13.5f)
                horizontalLineTo(7f)
                verticalLineTo(10.5f)
                horizontalLineTo(10.5f)
                close()
            }
            path(
                name = "dot",
                fill = SolidColor(Color(0xFF838383))
            ) {
                moveTo(12f, 9.25f)
                curveTo(13.52f, 9.25f, 14.75f, 10.48f, 14.75f, 12f)
                curveTo(14.75f, 13.52f, 13.52f, 14.75f, 12f, 14.75f)
                curveTo(10.48f, 14.75f, 9.25f, 13.52f, 9.25f, 12f)
                curveTo(9.25f, 10.48f, 10.48f, 9.25f, 12f, 9.25f)
                close()
            }
        }.build()

        return _SealSolid!!
    }

@Suppress("ObjectPropertyName")
private var _SealSolid: ImageVector? = null
