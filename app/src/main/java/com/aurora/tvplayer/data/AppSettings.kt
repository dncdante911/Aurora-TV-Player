package com.aurora.tvplayer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which decoders the player uses. Hardware is the only way to 4K60; FFmpeg covers what the TV lacks. */
enum class DecoderPreference(val label: String, val detail: String) {
    AUTO("Автоматически", "Аппаратный декодер ТВ, при отсутствии или сбое — программный FFmpeg"),
    HARDWARE("Только аппаратный", "Как в 2.x: только декодеры телевизора"),
    SOFTWARE("Программный FFmpeg", "Для редких форматов. 4K и 60 к/с обычно не тянет"),
}

/** Matching the screen refresh rate to the video removes judder in 24p/25p/50p films. */
enum class FrameRateMode(val label: String, val detail: String) {
    OFF("Выключено", "Экран не переключается"),
    SEAMLESS("Только плавно", "Переключается, если ТВ умеет без чёрного экрана"),
    ALWAYS("Как у видео", "24p/25p/50p без рывков; при старте возможен короткий чёрный экран"),
}

data class SettingsState(
    val animatedBackground: Boolean = true,
    val autoplayNext: Boolean = true,
    val forwardStepSec: Int = 30,
    val backStepSec: Int = 10,
    val decoder: DecoderPreference = DecoderPreference.AUTO,
    val frameRate: FrameRateMode = FrameRateMode.SEAMLESS,
    val watchedMinutes: Int = 0,
    val supportReminderShown: Boolean = false,
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
            decoder = runCatching { DecoderPreference.valueOf(prefs.getString("decoder", null)!!) }.getOrDefault(DecoderPreference.AUTO),
            frameRate = runCatching { FrameRateMode.valueOf(prefs.getString("frame_rate", null)!!) }.getOrDefault(FrameRateMode.SEAMLESS),
            watchedMinutes = prefs.getInt("watched_minutes", 0),
            supportReminderShown = prefs.getBoolean("support_reminder_shown", false),
        )
    )
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()
    val current: SettingsState get() = mutableState.value

    fun setAnimatedBackground(enabled: Boolean) = change { it.copy(animatedBackground = enabled) }
    fun setAutoplayNext(enabled: Boolean) = change { it.copy(autoplayNext = enabled) }
    fun setForwardStep(seconds: Int) = change { it.copy(forwardStepSec = seconds) }
    fun setDecoder(value: DecoderPreference) = change { it.copy(decoder = value) }
    fun setFrameRate(value: FrameRateMode) = change { it.copy(frameRate = value) }
    fun addWatchedMinute() = change { it.copy(watchedMinutes = it.watchedMinutes + 1) }
    fun markSupportReminderShown() = change { it.copy(supportReminderShown = true) }

    private fun change(transform: (SettingsState) -> SettingsState) {
        mutableState.update(transform)
        val s = mutableState.value
        prefs.edit {
            putBoolean("animated_background", s.animatedBackground)
            putBoolean("autoplay_next", s.autoplayNext)
            putInt("forward_step", s.forwardStepSec)
            putInt("back_step", s.backStepSec)
            putString("decoder", s.decoder.name)
            putString("frame_rate", s.frameRate.name)
            putInt("watched_minutes", s.watchedMinutes)
            putBoolean("support_reminder_shown", s.supportReminderShown)
        }
    }
}
