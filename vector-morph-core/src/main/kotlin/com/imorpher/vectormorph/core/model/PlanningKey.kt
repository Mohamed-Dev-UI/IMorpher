package com.imorpher.vectormorph.core.model

/**
 * The subset of [MorphConfiguration] that influences planning: the fields
 * [com.imorpher.vectormorph.core.plan.MorphPlanner] actually consumes.
 *
 * Render- and evaluation-only fields (draw order, stagger, timing, color space, fill
 * defaults, debug options, keepRenderOrder, directionTraversal, the anchor maps) are
 * deliberately excluded: they never change a plan, so configs equal on this key share
 * one cached plan instead of fragmenting the [com.imorpher.vectormorph.core.plan.MorphPlanCache].
 */
data class PlanningKey(
    val pathMatching: PathMatchingStrategy,
    val startPoint: StartPointStrategy,
    val pathDirection: PathDirectionStrategy,
    val customMappings: List<PathMapping>,
    val missingPath: MissingPathBehavior,
    val fallback: FallbackStrategy,
    val strokeFill: StrokeFillStrategy,
    val segmentBudget: Int?,
    val simplificationTolerance: Float,
    val viewportNormalization: ViewportNormalization,
    @Suppress("DEPRECATION") val customStartOffsets: Map<String, Float>,
)

/**
 * Extracts this configuration's [PlanningKey]: two configurations equal on planning
 * grounds produce equal keys. New MorphConfiguration fields must either join this key
 * (when the planner consumes them) or stay out (when only the evaluator/renderer does).
 */
@Suppress("DEPRECATION")
fun MorphConfiguration.planningKey(): PlanningKey = PlanningKey(
    pathMatching = pathMatching,
    startPoint = startPoint,
    pathDirection = pathDirection,
    customMappings = customMappings,
    missingPath = missingPath,
    fallback = fallback,
    strokeFill = strokeFill,
    segmentBudget = segmentBudget,
    simplificationTolerance = simplificationTolerance,
    viewportNormalization = viewportNormalization,
    customStartOffsets = customStartOffsets,
)
