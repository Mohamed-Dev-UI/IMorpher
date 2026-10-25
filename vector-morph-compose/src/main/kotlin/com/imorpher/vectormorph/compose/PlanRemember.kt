package com.imorpher.vectormorph.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.imorpher.vectormorph.compose.renderer.MorphRenderer
import com.imorpher.vectormorph.core.animation.EvaluatedFrame
import com.imorpher.vectormorph.core.animation.TimelineEvaluator
import com.imorpher.vectormorph.core.animation.VectorAnimation as VectorAnimationSpec
import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.MorphConfiguration
import com.imorpher.vectormorph.core.model.planningKey
import com.imorpher.vectormorph.core.model.PreparedVector
import com.imorpher.vectormorph.core.plan.MorphPlan
import com.imorpher.vectormorph.core.plan.MorphPlanCache
import com.imorpher.vectormorph.core.plan.MorphPlanner
import java.util.WeakHashMap

/**
 * Draws [plan] at [progress] on a sized canvas, wiring the timeline evaluator (when
 * [animation] or a [configuration]-embedded animation is present) to the shared [MorphRenderer].
 *
 * The evaluator is remembered per (plan, animation, color space, stagger) pair and the
 * frame buffers per plan, so repeated frames reuse every allocation.
 */
@Composable
internal fun VectorPlanCanvas(
    plan: MorphPlan,
    progress: Float,
    configuration: MorphConfiguration,
    animation: VectorAnimationSpec?,
    modifier: Modifier,
    contentDescription: String?,
    width: Dp,
    height: Dp,
    tintFrom: Any?,
    tintTo: Any?,
    alignment: Alignment,
) {
    val planningAnimation = animation ?: configuration.animation
    val evaluator = remember(
        plan, planningAnimation, configuration.colorSpace,
        configuration.staggerDelay, configuration.staggerOrder,
        configuration.customDrawOrder, configuration.timing,
    ) {
        planningAnimation?.let {
            TimelineEvaluator(
                animation = it,
                plan = plan,
                colorSpace = configuration.colorSpace,
                staggerDelay = if (configuration.staggerDelay > 0f) configuration.staggerDelay else it.staggerDelay,
                staggerOrder = if (configuration.staggerDelay > 0f) configuration.staggerOrder else it.staggerOrder,
                customDrawOrder = configuration.customDrawOrder,
                timingMode = configuration.timing,
            )
        }
    }
    val frame = remember(plan) { EvaluatedFrame(plan) }
    val renderer = remember { MorphRenderer() }
    val accessibleModifier = if (contentDescription == null) modifier else modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(modifier = accessibleModifier.size(width, height)) {
        evaluator?.evaluate(progress.coerceIn(0f, 1f), frame)
        renderer.render(this, plan, frame, progress, configuration, tintFrom, tintTo, alignment)
    }
}

/** Compiled [PreparedVector]s, keyed by ImageVector identity so equal instances share work. */
private val preparedVectors = WeakHashMap<ImageVector, PreparedVector>()

/** Process-wide plan cache; plans are immutable, so sharing them across callers is safe. */
internal val planCache = MorphPlanCache(maxEntries = 64)

/**
 * Compiles [from]/[to] and plans their morph, cached on two levels: prepared vectors in a
 * [java.util.WeakHashMap] keyed by ImageVector identity, and plans in a [MorphPlanCache]
 * keyed by (prepared from, prepared to, configuration). The animation is stripped from the
 * planning configuration because timelines never influence the plan — only its evaluation.
 */
@Composable
internal fun rememberPlan(
    from: ImageVector,
    to: ImageVector,
    configuration: MorphConfiguration,
): MorphPlan {
    val planningConfiguration = configuration.copy(animation = null)
    val key = planningConfiguration.planningKey()
    return remember(from, to, key) {
        val preparedFrom = prepared(from)
        val preparedTo = prepared(to)
        planCache.getOrPut(preparedFrom, preparedTo, key) {
            MorphPlanner.plan(preparedFrom, preparedTo, planningConfiguration)
        }
    }
}

/** Compiles [vector] once per instance, sharing the result across all morphs that use it. */
@Synchronized
internal fun prepared(vector: ImageVector): PreparedVector =
    preparedVectors.getOrPut(vector) { VectorCompiler.compile(vector) }
