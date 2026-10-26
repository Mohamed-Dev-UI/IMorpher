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

    /**
     * Opens a persistent [MorphVector] composition whose configuration can be flipped
     * in place — the only way to observe a stale `remember` scope, which skip-changes
     * across *recompositions* (fresh compositions always rebuild every remember block).
     */
    fun openRecompositionProbe(
        from: ImageVector,
        to: ImageVector,
        progress: Float,
        initialConfiguration: MorphConfiguration,
    ): RecompositionProbe {
        var currentConfiguration by mutableStateOf(initialConfiguration)
        // Composition-pass counter, shared by reference: compositionTick advances every
        // time the content recomposes (diagnostic for stale-remember debugging).
        val compositionTick = intArrayOf(0)
        val view = ComposeView(activity)
        view.setContent {
            compositionTick[0]++
            Box(modifier = Modifier.size(framePx.dp), contentAlignment = Alignment.Center) {
                MorphVector(
                    from = from,
                    to = to,
                    progress = progress,
                    configuration = currentConfiguration,
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
        return RecompositionProbe(this, view, compositionTick) { configuration -> currentConfiguration = configuration }
    }

    /** Flips the configuration of a live composition and captures frames across recompositions. */
    internal class RecompositionProbe(
        private val harness: RenderProbeHarness,
        private val view: ComposeView,
        private val compositionTick: IntArray,
        private val applyConfiguration: (MorphConfiguration) -> Unit,
    ) {
        /** Composition passes the probe content has run so far (see harness tick comment). */
        fun compositionPasses(): Int = compositionTick[0]

        fun update(configuration: MorphConfiguration) {
            harness.activity.runOnUiThread { applyConfiguration(configuration) }
            harness.pumpMainLoop()
        }

        fun capture(): Bitmap {
            val bitmap = Bitmap.createBitmap(harness.framePx, harness.framePx, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            harness.pumpMainLoop()
            return bitmap
        }
    }
}
