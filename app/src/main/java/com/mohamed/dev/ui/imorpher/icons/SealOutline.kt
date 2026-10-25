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

/**
 * Stroke-seal demo with NAMED paths (`ring`, `cross`, `dot`) and a top-half group clip.
 * Named paths give the configuration panel's anchors/mappings/order editors real names
 * instead of `path[1]` fallbacks; the one-sided clip exercises mask handover.
 */
internal val IMorpherIcons.SealOutline: ImageVector
    get() {
        if (_SealOutline != null) {
            return _SealOutline!!
        }
        _SealOutline = ImageVector.Builder(
            name = "SealOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            group(
                clipPathData = PathData {
                    moveTo(2f, 2f)
                    horizontalLineToRelative(20f)
                    verticalLineToRelative(10f)
                    horizontalLineToRelative(-20f)
                    close()
                }
            ) {
                path(
                    name = "ring",
                    stroke = SolidColor(Color(0xFF838383)),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(12f, 3f)
                    curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
                    curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
                    curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
                    curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
                    close()
                }
                path(
                    name = "cross",
                    stroke = SolidColor(Color(0xFF838383)),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(8.5f, 12f)
                    horizontalLineTo(15.5f)
                    moveTo(12f, 8.5f)
                    verticalLineTo(15.5f)
                }
                path(
                    name = "dot",
                    fill = SolidColor(Color(0xFF838383))
                ) {
                    moveTo(12f, 10.25f)
                    curveTo(12.97f, 10.25f, 13.75f, 11.03f, 13.75f, 12f)
                    curveTo(13.75f, 12.97f, 12.97f, 13.75f, 12f, 13.75f)
                    curveTo(11.03f, 13.75f, 10.25f, 12.97f, 10.25f, 12f)
                    curveTo(10.25f, 11.03f, 11.03f, 10.25f, 12f, 10.25f)
                    close()
                }
            }
        }.build()

        return _SealOutline!!
    }

@Suppress("ObjectPropertyName")
private var _SealOutline: ImageVector? = null
