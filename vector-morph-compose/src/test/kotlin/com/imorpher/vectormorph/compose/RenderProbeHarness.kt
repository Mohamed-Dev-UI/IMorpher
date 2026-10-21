package com.imorpher.vectormorph.compose

import android.view.View
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode

import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.model.MorphConfiguration

import org.robolectric.Shadows.shadowOf

/**
 * Shared Robolectric render-probe harness: composes one [MorphVector] frame on an activity
 * and captures it to a bitmap (extracted from the copies in `MorphParameterApplicationTest`
 * and `DrawDefaultAnchorRegressionTest`), plus the image-vector builders every probe suite
 * re-creates. Bitmap sampling helpers live in [BitmapAsserts]([BitmapAsserts.litPixelsKey]).
 */
internal class RenderProbeHarness(
    /** The Robolectric activity hosting every composed probe frame. */
    val activity: ComponentActivity,
    /** Frame side length in pixels; one icon viewport spans the whole frame. */
    val framePx: Int = 96,
) {

    /** Flushes the Robolectric main looper so Compose settles before the next capture step. */
    fun pumpMainLoop() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    /** Composes [from]→[to] at [progress] (optionally animated) and captures one frame. */
    fun renderMorph(
        from: ImageVector,
        to: ImageVector,
        progress: Float,
        configuration: MorphConfiguration = MorphConfiguration.Default,
        animation: VectorAnimationSpec? = null,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(framePx.dp), contentAlignment = Alignment.Center) {
                MorphVector(
                    from = from,
                    to = to,
                    progress = progress,
                    configuration = if (animation != null) configuration.copy(animation = animation) else configuration,
                    contentDescription = null,
                    width = framePx.dp,
                    height = framePx.dp,
                )
            }
        }
        activity.setContentView(view)
        pumpMainLoop()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(framePx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, framePx, framePx)
        pumpMainLoop()
        val bitmap = Bitmap.createBitmap(framePx, framePx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        pumpMainLoop()
        return bitmap
    }

    // ------------------------------------------------------------------ builders

    /** Filled contour path with the probe-blue solid paint; several contours make one path. */
    fun filled(name: String, vararg contours: List<PathNode>, alpha: Float = 1f): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                fill = SolidColor(Color(0xFF12A4D9).copy(alpha = alpha)),
                name = "path-$index",
            )
        }
        return builder.build()
    }

    /**
     * Single stroked contour path with the probe-blue solid paint. The path name defaults
     * to "stroke" (previously hard-coded) and can be exposed for map keying — e.g.
     * `DrawAnchorMapApplicationTest` names its path "p" to key the anchor maps.
     */
    fun stroked(
        name: String,
        data: List<PathNode>,
        width: Float = 2f,
        pathName: String = "stroke",
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = data,
            stroke = SolidColor(Color(0xFF12A4D9)),
            strokeLineWidth = width,
            name = pathName,
        )
        return builder.build()
    }

    /** The closed square the fill probes morph between. */
    fun squareAt(x: Float, y: Float, size: Float = 8f) = listOf(
        PathNode.MoveTo(x, y), PathNode.LineTo(x + size, y),
        PathNode.LineTo(x + size, y + size), PathNode.LineTo(x, y + size), PathNode.Close,
    )

    /** Horizontal line, authored left→right (arc 0 sits at x=3). */
    fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))
}
