package com.imorpher.vectormorph.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
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
import com.imorpher.vectormorph.core.model.DrawDirection
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.PathStart
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task 1 regression probe (start/direction coordination plan): the new `PathStart.AUTO`
 * default must be pixel-identical to the old explicit `PathStart.START` default render.
 *
 * `AUTO` means "derive the anchor from `DrawDirection`"; today's default (`START` +
 * `AUTO` direction → top-left anchor) must stay reachable exactly as before, and the
 * flipped DSL/`DrawConfig` defaults must not change output for existing API usage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawDefaultAnchorRegressionTest {

    private lateinit var activity: ComponentActivity
    private val framePx = 96

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    // ------------------------------------------------------------------ builders

    private fun stroked(name: String, data: List<PathNode>, width: Float = 2f): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        builder.addPath(
            pathData = data,
            stroke = SolidColor(Color(0xFF12A4D9)),
            strokeLineWidth = width,
            name = "stroke",
        )
        return builder.build()
    }

    private fun line() = listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 12f))

    /** The stroke pair the reveal runs on: a horizontal line morphing into a slanted one. */
    private val from by lazy { stroked("from", line(), width = 2f) }
    private val to by lazy {
        stroked(
            "to",
            listOf(PathNode.MoveTo(3f, 12f), PathNode.LineTo(21f, 6f)),
            width = 2f,
        )
    }

    // ------------------------------------------------------------------ rendering

    private fun renderMorph(
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

    private fun pumpMainLoop() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
    }

    // ------------------------------------------------------------------ parity probes

    @Test
    fun autoDefaultMatchesOldExplicitDefault() {
        val old = drawAnimation(start = PathStart.START, direction = DrawDirection.AUTO)
        val new = drawAnimation(start = PathStart.AUTO, direction = DrawDirection.AUTO)
        listOf(0.25f, 0.5f, 0.75f).forEach { t ->
            val oldFrame = renderMorph(from, to, t, animation = old)
            val newFrame = renderMorph(from, to, t, animation = new)
            // Sanity: the frames must actually paint the reveal, so parity is not vacuous.
            assertTrue(
                "mid-reveal frame at t=$t must paint pixels",
                BitmapAsserts.paintedCount(oldFrame) > 0,
            )
            assertTrue(
                "AUTO default must render identical to the old explicit default at t=$t",
                oldFrame.sameAs(newFrame),
            )
        }
    }

    @Test
    fun flippedDslDefaultStillMatchesOldExplicitDefault() {
        // drawProgress() / strokeReveal() now default to AUTO; that default must equal
        // the old explicit START default for unchanged call sites.
        val legacyDefault = drawAnimation(start = PathStart.START, direction = DrawDirection.AUTO)
        val flippedDefault = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths {
                drawProgress(from = 0f, to = 1f, interval = 0f..1f)
            }
        }
        val flippedStrokeAlias = VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths {
                strokeReveal(interval = 0f..1f)
            }
        }
        listOf(0.25f, 0.5f, 0.75f).forEach { t ->
            val legacy = renderMorph(from, to, t, animation = legacyDefault)
            val defaulted = renderMorph(from, to, t, animation = flippedDefault)
            val aliased = renderMorph(from, to, t, animation = flippedStrokeAlias)
            assertTrue("defaulted drawProgress must match the old START default at t=$t", legacy.sameAs(defaulted))
            assertTrue("defaulted strokeReveal must match the old START default at t=$t", legacy.sameAs(aliased))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun drawAnimation(start: PathStart, direction: DrawDirection): VectorAnimationSpec =
        VectorAnimationSpec {
            defaultEasing(androidx.compose.animation.core.LinearEasing)
            allPaths {
                drawProgress(
                    from = 0f, to = 1f, interval = 0f..1f,
                    start = start, direction = direction,
                )
            }
        }
}
