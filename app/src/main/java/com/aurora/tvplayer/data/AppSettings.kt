package com.aurora.tvplayer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val animatedBackground: Boolean = true,
    val autoplayNext: Boolean = true,
    val forwardStepSec: Int = 30,
    val backStepSec: Int = 10,
)

/** App preferences; track choices stay in "library" where the player always kept them. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        SettingsState(
            animatedBackground = prefs.getBoolean("animated_background", true),
            autoplayNext = prefs.getBoolean("autoplay_next", true),
            forwardStepSec = prefs.getInt("forward_step", 30),
            backStepSec = prefs.getInt("back_step", 10),
        )
    )
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()
    val current: SettingsState get() = mutableState.value

    fun setAnimatedBackground(enabled: Boolean) = change { it.copy(animatedBackground = enabled) }
    fun setAutoplayNext(enabled: Boolean) = change { it.copy(autoplayNext = enabled) }
    fun setForwardStep(seconds: Int) = change { it.copy(forwardStepSec = seconds) }

    private fun change(transform: (SettingsState) -> SettingsState) {
        mutableState.update(transform)
        val s = mutableState.value
        prefs.edit {
            putBoolean("animated_background", s.animatedBackground)
            putBoolean("autoplay_next", s.autoplayNext)
            putInt("forward_step", s.forwardStepSec)
            putInt("back_step", s.backStepSec)
        }
    }
}
