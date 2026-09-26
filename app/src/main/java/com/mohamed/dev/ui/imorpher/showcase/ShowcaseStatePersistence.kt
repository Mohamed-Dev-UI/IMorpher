package com.mohamed.dev.ui.imorpher.showcase

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import kotlin.enums.EnumEntries

/**
 * Saves and restores [ShowcaseState] via SharedPreferences so playground experiments survive
 * app restarts. Values are stored as one JSON blob; unknown or corrupt values fall back to the
 * state's defaults, and a "Reset playgrounds" action clears the stored blob.
 */
object ShowcaseStatePersistence {

    private const val PREFS = "showcase_state"
    private const val KEY = "playground"

    /** Test hooks: serialize/apply without touching SharedPreferences. */
    internal fun toJsonForTest(state: ShowcaseState): String = toJson(state)

    internal fun applyJsonForTest(raw: String, state: ShowcaseState): Boolean = runCatching {
        fromJson(JSONObject(raw), state)
        true
    }.getOrElse { false }

    fun save(context: Context, state: ShowcaseState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, toJson(state))
            .apply()
    }

    /** Restores into [state] when a previous session was saved; false when nothing was stored. */
    fun load(context: Context, state: ShowcaseState): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return false
        return runCatching {
            fromJson(JSONObject(raw), state)
            true
        }.getOrElse { false }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ------------------------------------------------------------------ serialize

    private fun toJson(state: ShowcaseState): String = JSONObject().apply {
        put("fromIconName", state.fromIconName)
        put("toIconName", state.toIconName)
        put("primary", colorJson(state.primary))
        put("secondary", colorJson(state.secondary))
        put("accent", colorJson(state.accent))
        put("tintMode", state.tintMode.name)
        put("durationMillis", state.durationMillis.toDouble())
        put("motionPreference", state.motionPreference.name)
        put("pathMatching", state.pathMatching.name)
        put("missingPath", state.missingPath.name)
        put("fallback", state.fallback.name)
        put("timing", state.timing.name)
        put("colorSpace", state.colorSpace.name)
        put("keepRenderOrder", state.keepRenderOrder)
        put("drawMode", state.drawMode.name)
        put("drawDirection", state.drawDirection.name)
        put("pathStart", state.pathStart.name)
        put("fillMode", state.fillMode.name)
        put("staggerDelay", state.staggerDelay.toDouble())
        put("staggerOrder", state.staggerOrder.name)
        put("startScale", state.startScale.toDouble())
        put("endScale", state.endScale.toDouble())
        put("startRotation", state.startRotation.toDouble())
        put("endRotation", state.endRotation.toDouble())
        put("strokeWidth", state.strokeWidth.toDouble())
        put("revealEnabled", state.revealEnabled)
        put("colorEnabled", state.colorEnabled)
    }.toString()

    private fun fromJson(json: JSONObject, state: ShowcaseState) {
        with(state) {
            // Restored icon names must still exist in the catalog: a saved name from an
            // older build would otherwise crash the icon getter on next use.
            fromIconName = json.optString("fromIconName", fromIconName)
                .takeIf { IconCatalog.hasIcon(it) } ?: fromIconName
            toIconName = json.optString("toIconName", toIconName)
                .takeIf { IconCatalog.hasIcon(it) } ?: toIconName
            primary = colorFrom(json.optJSONObject("primary"), primary)
            secondary = colorFrom(json.optJSONObject("secondary"), secondary)
            accent = colorFrom(json.optJSONObject("accent"), accent)
            tintMode = parseEnum(json.optString("tintMode", tintMode.name), tintMode, TintMode.entries)
            durationMillis = parseNumber(json, "durationMillis", durationMillis)
            motionPreference = parseEnum(json.optString("motionPreference", motionPreference.name), motionPreference, MotionPreferenceEntries)
            pathMatching = parseEnum(json.optString("pathMatching", pathMatching.name), pathMatching, PathMatchingEntries)
            missingPath = parseEnum(json.optString("missingPath", missingPath.name), missingPath, MissingPathEntries)
            fallback = parseEnum(json.optString("fallback", fallback.name), fallback, FallbackEntries)
            timing = parseEnum(json.optString("timing", timing.name), timing, TimingEntries)
            colorSpace = parseEnum(json.optString("colorSpace", colorSpace.name), colorSpace, ColorSpaceEntries)
            keepRenderOrder = json.optBoolean("keepRenderOrder", keepRenderOrder)
            drawMode = parseEnum(json.optString("drawMode", drawMode.name), drawMode, DrawModeEntries)
            drawDirection = parseEnum(json.optString("drawDirection", drawDirection.name), drawDirection, DrawDirectionEntries)
            pathStart = parseEnum(json.optString("pathStart", pathStart.name), pathStart, PathStartEntries)
            fillMode = parseEnum(json.optString("fillMode", fillMode.name), fillMode, FillModeEntries)
            staggerDelay = parseNumber(json, "staggerDelay", staggerDelay)
            staggerOrder = parseEnum(json.optString("staggerOrder", staggerOrder.name), staggerOrder, DrawOrderEntries)
            startScale = parseNumber(json, "startScale", startScale)
            endScale = parseNumber(json, "endScale", endScale)
            startRotation = parseNumber(json, "startRotation", startRotation)
            endRotation = parseNumber(json, "endRotation", endRotation)
            strokeWidth = parseNumber(json, "strokeWidth", strokeWidth)
            revealEnabled = json.optBoolean("revealEnabled", revealEnabled)
            colorEnabled = json.optBoolean("colorEnabled", colorEnabled)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun colorJson(color: Color) = JSONObject().apply {
        put("a", color.alpha.toDouble())
        put("r", color.red.toDouble())
        put("g", color.green.toDouble())
        put("b", color.blue.toDouble())
    }

    private fun colorFrom(json: JSONObject?, fallback: Color): Color {
        json ?: return fallback
        return runCatching {
            Color(
                red = json.optDouble("r", fallback.red.toDouble()).toFloat(),
                green = json.optDouble("g", fallback.green.toDouble()).toFloat(),
                blue = json.optDouble("b", fallback.blue.toDouble()).toFloat(),
                alpha = json.optDouble("a", fallback.alpha.toDouble()).toFloat(),
            )
        }.getOrDefault(fallback)
    }

    private fun parseNumber(json: JSONObject, key: String, fallback: Float): Float =
        json.optDouble(key, fallback.toDouble()).toFloat().let { if (it.isNaN()) fallback else it }

    private inline fun <reified E : Enum<E>> parseEnum(
        raw: String,
        fallback: E,
        entries: EnumEntries<E>,
    ): E = entries.firstOrNull { it.name == raw } ?: fallback

    // Enum entry lists referenced above (kept as vals so parseEnum stays allocation-free).
    private val MotionPreferenceEntries = com.imorpher.vectormorph.core.model.MotionPreference.entries
    private val PathMatchingEntries = com.imorpher.vectormorph.core.model.PathMatchingStrategy.entries
    private val MissingPathEntries = com.imorpher.vectormorph.core.model.MissingPathBehavior.entries
    private val FallbackEntries = com.imorpher.vectormorph.core.model.FallbackStrategy.entries
    private val TimingEntries = com.imorpher.vectormorph.core.model.TimingMode.entries
    private val ColorSpaceEntries = com.imorpher.vectormorph.core.model.ColorInterpolationSpace.entries
    private val DrawModeEntries = com.imorpher.vectormorph.core.model.DrawMode.entries
    private val DrawDirectionEntries = com.imorpher.vectormorph.core.model.DrawDirection.entries
    private val PathStartEntries = com.imorpher.vectormorph.core.model.PathStart.entries
    private val FillModeEntries = com.imorpher.vectormorph.core.model.FillMode.entries
    private val DrawOrderEntries = com.imorpher.vectormorph.core.model.DrawOrderStrategy.entries
}
