package com.mohamed.dev.ui.imorpher.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Wide 48×16 demo banner used by the ViewportNormalization showcase: morphing it into a
 * square icon shows STRETCH distorting the target axes while FIT letterboxes uniformly.
 */
internal val IMorpherIcons.BannerWide: ImageVector
    get() {
        if (_BannerWide != null) {
            return _BannerWide!!
        }
        _BannerWide = ImageVector.Builder(
            name = "BannerWide",
            defaultWidth = 48.dp,
            defaultHeight = 16.dp,
            viewportWidth = 48f,
            viewportHeight = 16f
        ).apply {
            path(fill = SolidColor(Color(0xFF838383))) {
                moveTo(5f, 2f)
                horizontalLineTo(43f)
                curveTo(44.7f, 2f, 46f, 3.3f, 46f, 5f)
                verticalLineTo(11f)
                curveTo(46f, 12.7f, 44.7f, 14f, 43f, 14f)
                horizontalLineTo(5f)
                curveTo(3.3f, 14f, 2f, 12.7f, 2f, 11f)
                verticalLineTo(5f)
                curveTo(2f, 3.3f, 3.3f, 2f, 5f, 2f)
                close()
            }
        }.build()

        return _BannerWide!!
    }

@Suppress("ObjectPropertyName")
private var _BannerWide: ImageVector? = null
