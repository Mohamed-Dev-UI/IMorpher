package com.imorpher.vectormorph.compose

import android.view.View
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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

    /**
     * Flushes the Robolectric main looper so Compose settles before the next capture step.
     *
     * Compose paces recomposition through Choreographer frame callbacks that are posted a
     * frame interval in the future, and plain `idle()` never advances the virtual clock —
     * so a state write only recomposes when something earlier in the JVM already advanced
     * the clock past those callbacks. Earlier test classes can leave the looper with
     * future-dated messages (animations, handlers), so a short advance (100 ms) only
     * drains the backlog and never reaches Compose's own frame deadline — every
     * state-write-then-recapture test then observed stale frames depending on how many
     * classes ran before it. A large advance (2 s) makes the frames due unconditionally
     * regardless of the backlog, and the repeated idles drain the recompose/layout chain
     * plus any follow-up callbacks posted while it ran.
     */
    fun pumpMainLoop() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
        shadowOf(Looper.getMainLooper()).idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
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

    /**
     * Composes arbitrary [content] inside an exact [width] x [height] pixel frame and captures
     * it. Shared by the content-transition probes and any future non-vector entry points.
     */
    fun renderContent(
        width: Int = framePx,
        height: Int = framePx,
        content: @Composable () -> Unit,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(width.dp, height.dp)) {
                content()
            }
        }
        activity.setContentView(view)
        pumpMainLoop()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        pumpMainLoop()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        pumpMainLoop()
        return bitmap
    }

    // ------------------------------------------------------------------ builders

    /** Filled contour path with the probe-blue solid paint; several contours make one path. */
    fun filled(
        name: String,
        vararg contours: List<PathNode>,
        alpha: Float = 1f,
        /** Optional per-contour path names (index-matched); defaults to "path-$index". */
        pathNames: List<String>? = null,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                fill = SolidColor(Color(0xFF12A4D9).copy(alpha = alpha)),
                name = pathNames?.getOrNull(index) ?: "path-$index",
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

    /** Stroked contours as separate paths; per-path names expose anchor-map keys. */
    fun strokedPaths(
        name: String,
        vararg contours: List<PathNode>,
        strokeWidth: Float = 2f,
        pathNames: List<String>? = null,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                stroke = SolidColor(Color(0xFF12A4D9)),
                strokeLineWidth = strokeWidth,
                name = pathNames?.getOrNull(index) ?: "stroke-$index",
            )
        }
        return builder.build()
    }

    /** The closed square the fill probes morph between. */
    fun squareAt(x: Float, y: Float, size: Float = 8f) = listOf(
        PathNode.MoveTo(x, y), PathNode.LineTo(x + size, y),
        PathNode.LineTo(x + size, y + size), PathNode.LineTo(x, y + size), PathNode.Close,
    )

    /** Horizontal line, authored left→right (arc 0 sits at x=3). */
    fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    /** Filled + stroked contours in probe blue: the stroke→fill transition shape. */
    fun filledAndStroked(
        name: String,
        vararg contours: List<PathNode>,
        strokeWidth: Float = 4f,
    ): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        contours.forEachIndexed { index, data ->
            builder.addPath(
                pathData = data,
                fill = SolidColor(Color(0xFF12A4D9)),
                stroke = SolidColor(Color(0xFF12A4D9)),
                strokeLineWidth = strokeWidth,
                name = "path-$index",
            )
        }
        return builder.build()
    }

    /**
     * Composes the single-vector auto-play [VectorAnimation] entry point and pumps
     * [pumps] idle rounds (enough for a tween one-shot to finish under Robolectric
     * choreography). [progress] = null plays the one-shot; an explicit progress skips it
     * (settled-state reference). Parameters mirror the composable.
     */
    fun renderAutoPlayVector(
        vector: ImageVector,
        animation: VectorAnimationSpec,
        progress: Float? = null,
        animationSpec: AnimationSpec<Float> = tween(400),
        pumps: Int = 24,
    ): Bitmap {
        val view = ComposeView(activity)
        view.setContent {
            Box(modifier = Modifier.size(framePx.dp), contentAlignment = Alignment.Center) {
                VectorAnimation(
                    vector = vector,
                    animation = animation,
                    progress = progress,
                    animationSpec = animationSpec,
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
        repeat(pumps) { pumpMainLoop() }
        val bitmap = Bitmap.createBitmap(framePx, framePx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        pumpMainLoop()
        return bitmap
    }

}
