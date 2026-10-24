package com.imorpher.vectormorph.compose

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.imorpher.vectormorph.core.model.MotionPreference

/**
 * Resolves the effective [MotionPreference] for a composable.
 *
 * An explicit [userPreference] always wins. Otherwise the system animator-duration scale is read
 * once per composition context: `0` (animations disabled, e.g. "Remove animations" accessibility
 * setting) maps to [MotionPreference.INSTANT], anything below `1` to
 * [MotionPreference.REDUCED], and the normal scale to [MotionPreference.FULL]. Unknown or
 * unreadable settings fall back to [MotionPreference.FULL] rather than freezing motion.
 */
@Composable
fun rememberMotionPreference(userPreference: MotionPreference? = null): MotionPreference {
    if (userPreference != null) return userPreference
    val context = LocalContext.current
    return remember(context) { resolveSystemMotionPreference(context) }
}

/** Non-composable resolution shared by [rememberMotionPreference]; exposed for tests. */
internal fun resolveSystemMotionPreference(context: android.content.Context): MotionPreference {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) {
        1f
    }
    return when {
        scale <= 0f -> MotionPreference.INSTANT
        scale < 1f -> MotionPreference.REDUCED
        else -> MotionPreference.FULL
    }
}
