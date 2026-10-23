package com.aurora.tvplayer.player

import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Chooses the screen mode for a video frame rate: same resolution as now, a refresh rate equal to
 * the frame rate or a small multiple of it (23.976 → 23.976/47.95; 25 → 25/50; 59.94 → 59.94).
 * Works through WindowManager.LayoutParams.preferredDisplayModeId, which exists on every
 * supported Android TV version, unlike Surface.setFrameRate (Android 11+).
 */
object RefreshRate {
    /** Same rate within rounding (23.976 vs 23.98 as some TVs report it). */
    private const val EXACT = 0.0005f
    /** Off by ~0.1% (24 Hz for 23.976): one repeated frame every ~40 s, no visible judder. */
    private const val CLOSE = 0.0015f

    private fun error(refresh: Float, fps: Float): Float? {
        if (fps <= 0f) return 0f
        val n = (refresh / fps).roundToInt()
        return if (n in 1..5) abs(refresh - n * fps) / (n * fps) else null
    }

    /** The screen shows this frame rate cadence-perfect. */
    fun exact(refresh: Float, fps: Float): Boolean = error(refresh, fps)?.let { it < EXACT } == true

    /** The screen shows this frame rate without visible judder. */
    fun fits(refresh: Float, fps: Float): Boolean = error(refresh, fps)?.let { it < CLOSE } == true

    /** Mode to switch to, or null when the current one is already right or nothing better exists. */
    fun bestMode(display: Display, fps: Float): Display.Mode? {
        if (fps < 10f) return null
        val current = display.mode
        // 25p on 50 Hz or 30p on 60 Hz is already perfect: no needless blackout.
        if (exact(current.refreshRate, fps)) return null
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && fits(it.refreshRate, fps)
        }
        return candidates
            .sortedWith(compareBy<Display.Mode> { !exact(it.refreshRate, fps) }.thenBy { (it.refreshRate / fps).roundToInt() })
            .firstOrNull()
            ?.takeIf { it.modeId != current.modeId && (exact(it.refreshRate, fps) || !fits(current.refreshRate, fps)) }
    }

    /** True for frame rates that visibly judder at the current refresh rate (24p on 60 Hz). */
    fun judders(display: Display, fps: Float): Boolean = fps >= 10f && !fits(display.mode.refreshRate, fps)
}
