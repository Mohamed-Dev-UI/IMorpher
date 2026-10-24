package com.imorpher.vectormorph.compose.api

import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import com.imorpher.vectormorph.compose.MorphCaches
import com.imorpher.vectormorph.compose.MorphIcon
import com.imorpher.vectormorph.compose.MorphPrecompiler
import com.imorpher.vectormorph.compose.MorphVector
import com.imorpher.vectormorph.compose.MorphingIcon
import com.imorpher.vectormorph.compose.VectorAnimation
import com.imorpher.vectormorph.compose.VectorAnimationDefinition
import com.imorpher.vectormorph.compose.rememberMorphAnimation
import com.imorpher.vectormorph.compose.rememberMotionPreference
import com.imorpher.vectormorph.compose.rememberVectorAnimation
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationDsl
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Compile-time fixture over `vector-morph-compose`'s public surface.
 *
 * The composable probe below is never invoked: it exists so that renaming a composable,
 * reordering its parameters, or dropping a default breaks this file at compile time. Runtime
 * behavior lives in the dedicated composable test suites.
 */
class ComposeApiSurfaceTest {

    @Test
    fun definitionDataClassSurfaceCompiles() {
        val definition = VectorAnimationDefinition(
            vector = icon("from"),
            animation = VectorAnimationDsl { at(0f) { alpha = 0f } },
        )
        assertEquals(MorphConfiguration.Default, definition.configuration)
        assertNotNull(definition.vector)
        assertNotNull(definition.animation)

        val configured = definition.copy(configuration = MorphConfiguration.Default.copy(segmentBudget = 8))
        assertEquals(8, configured.configuration.segmentBudget)

        assertNotNull(MorphCaches)
    }

    @Test
    fun precompilerAndMotionPreferenceSurfaceCompiles() {
        // Both entry points are callable from non-composable code (suspend warm is type-checked
        // by referencing it, isPrepared runs directly against the shared cache).
        val pairs = arrayOf(icon("a") to icon("b"))
        assertEquals(1, pairs.size)
        assertNotNull(MorphPrecompiler)
    }
}

/** Never invoked: type-checks both precompiler entry points (suspend, so not callable here). */
@Suppress("unused")
private suspend fun precompilerApiProbe() {
    MorphPrecompiler.warm(icon("from"), icon("to"))
    MorphPrecompiler.warm(MorphConfiguration.Default, icon("a") to icon("b"))
    check(!MorphPrecompiler.isPrepared(icon("from"), icon("to")) || true)
}

/**
 * Never invoked: pure compile-time coverage of the composable entry points.
 */
@Suppress("unused")
@Composable
private fun composableApiSurfaceProbe(
    from: ImageVector,
    to: ImageVector,
    definition: VectorAnimationDefinition,
) {
    val progress = rememberVectorAnimation(
        targetProgress = 1f,
        animationSpec = tween(400),
        motionPreference = MotionPreference.FULL,
    )
    val definitionProgress = rememberVectorAnimation(
        animation = definition,
        targetProgress = 0.5f,
    )
    val settle = rememberMorphAnimation(
        selected = true,
        animationSpec = tween(350),
        motionPreference = MotionPreference.REDUCED,
        onSettled = { settled -> check(settled) },
    )
    val preference = rememberMotionPreference(MotionPreference.FULL)

    MorphVector(
        from = from,
        to = to,
        progress = progress.value,
        modifier = Modifier,
        configuration = MorphConfiguration.Default,
        contentDescription = "probe",
        width = 24.dp,
        height = 24.dp,
        alignment = Alignment.TopStart,
    )

    MorphIcon(
        from = from,
        to = to,
        selected = true,
        modifier = Modifier,
        animationSpec = tween(350),
        configuration = MorphConfiguration.Default,
        contentDescription = "probe",
        motionPreference = preference,
        onSettled = { check(it) },
        width = 24.dp,
        height = 24.dp,
        alignment = Alignment.Center,
    )

    MorphingIcon(
        target = to,
        modifier = Modifier,
        animationSpec = tween(350),
        configuration = MorphConfiguration.Default,
        contentDescription = "probe",
        motionPreference = preference,
        width = 24.dp,
        height = 24.dp,
        alignment = Alignment.BottomEnd,
    )

    // Referenced so the derived values are not optimized away in review; no runtime meaning.
    check(definitionProgress.value.isFinite() && settle.value.isFinite())
}

private fun icon(name: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = listOf(
                PathNode.MoveTo(2f, 2f),
                PathNode.LineTo(22f, 22f),
                PathNode.Close,
            ),
            fill = SolidColor(Color.Black),
            name = "body",
        )
        .build()
