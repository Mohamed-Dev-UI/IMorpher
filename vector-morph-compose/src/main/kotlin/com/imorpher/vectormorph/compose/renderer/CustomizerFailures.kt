package com.imorpher.vectormorph.compose.renderer

/**
 * Process-wide record of [com.imorpher.vectormorph.core.animation.CustomVectorAnimation]
 * callbacks that threw and were disabled.
 *
 * The renderer never lets a failing per-frame callback crash a draw: the first exception disables
 * that callback instance and is recorded here (and as a
 * [com.imorpher.vectormorph.core.reveal.DrawResolutionWarning.CUSTOM_ANIMATION_FAILED] warning on
 * the frame). This object is a diagnostics/test seam, not a configuration knob.
 */
object CustomizerFailures {

    private var failures = 0

    /** Message of the most recent failure, or null while no callback has failed. */
    @Volatile
    var lastMessage: String? = null
        private set

    /** How many callback invocations have failed since the last [reset]. */
    val count: Int get() = synchronized(this) { failures }

    internal fun record(exception: Exception) {
        synchronized(this) { failures++ }
        lastMessage = exception.message ?: exception::class.simpleName
    }

    /** Clears the failure record (tests and long-lived process resets). */
    fun reset() {
        synchronized(this) { failures = 0 }
        lastMessage = null
    }
}
