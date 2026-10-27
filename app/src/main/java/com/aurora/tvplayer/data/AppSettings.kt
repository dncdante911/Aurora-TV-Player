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

/** "Живое оформление": the live aurora background and the seasonal decoration together. */
enum class LiveLook(val label: String) {
    AUTO("Авто"),
    ON("Включено"),
    OFF("Выключено"),
}

data class SettingsState(
    val liveLook: LiveLook = LiveLook.AUTO,
    /** The "Включить живое оформление?" question is asked once. */
    val liveLookAsked: Boolean = false,
    val autoplayNext: Boolean = true,
    val forwardStepSec: Int = 30,
    val backStepSec: Int = 10,
    val decoder: DecoderPreference = DecoderPreference.AUTO,
    val frameRate: FrameRateMode = FrameRateMode.SEAMLESS,
    val watchedMinutes: Int = 0,
    /** Watched minutes when the support note was last shown; it returns every [Support.REMINDER_AFTER_MINUTES]. */
    val supportShownAtMinutes: Int = 0,
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val decor: DecorMode = DecorMode.AUTO,
    /** "Курсор пультом": the arrows move a mouse pointer instead of the focus. */
    val remoteCursor: Boolean = false,
)

/** App preferences; track choices stay in "library" where the player always kept them. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        SettingsState(
            // Who switched the live background off before 3.3 keeps the plain look.
            liveLook = runCatching { LiveLook.valueOf(prefs.getString("live_look", null)!!) }
                .getOrDefault(if (prefs.getBoolean("animated_background", true)) LiveLook.AUTO else LiveLook.OFF),
            liveLookAsked = prefs.getBoolean("live_look_asked", false),
            autoplayNext = prefs.getBoolean("autoplay_next", true),
            forwardStepSec = prefs.getInt("forward_step", 30),
            backStepSec = prefs.getInt("back_step", 10),
            decoder = runCatching { DecoderPreference.valueOf(prefs.getString("decoder", null)!!) }.getOrDefault(DecoderPreference.AUTO),
            frameRate = runCatching { FrameRateMode.valueOf(prefs.getString("frame_rate", null)!!) }.getOrDefault(FrameRateMode.SEAMLESS),
            watchedMinutes = prefs.getInt("watched_minutes", 0),
            supportShownAtMinutes = prefs.getInt("support_shown_at", 0),
            autoUpdate = prefs.getBoolean("auto_update", true),
            lastUpdateCheck = prefs.getLong("last_update_check", 0),
            decor = runCatching { DecorMode.valueOf(prefs.getString("decor", null)!!) }.getOrDefault(DecorMode.AUTO),
            remoteCursor = prefs.getBoolean("remote_cursor", false),
        )
    )
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()
    val current: SettingsState get() = mutableState.value

    fun setLiveLook(mode: LiveLook) = change { it.copy(liveLook = mode, liveLookAsked = true) }
    fun markLiveLookAsked() = change { it.copy(liveLookAsked = true) }
    fun setAutoplayNext(enabled: Boolean) = change { it.copy(autoplayNext = enabled) }
    fun setForwardStep(seconds: Int) = change { it.copy(forwardStepSec = seconds) }
    fun setBackStep(seconds: Int) = change { it.copy(backStepSec = seconds) }
    fun setDecoder(value: DecoderPreference) = change { it.copy(decoder = value) }
    fun setFrameRate(value: FrameRateMode) = change { it.copy(frameRate = value) }
    fun addWatchedMinute() = change { it.copy(watchedMinutes = it.watchedMinutes + 1) }
    fun markSupportReminderShown() = change { it.copy(supportShownAtMinutes = it.watchedMinutes) }
    fun setAutoUpdate(enabled: Boolean) = change { it.copy(autoUpdate = enabled) }
    fun setLastUpdateCheck(time: Long) = change { it.copy(lastUpdateCheck = time) }
    fun setDecor(mode: DecorMode) = change { it.copy(decor = mode) }
    fun setRemoteCursor(enabled: Boolean) = change { it.copy(remoteCursor = enabled) }

    private fun change(transform: (SettingsState) -> SettingsState) {
        mutableState.update(transform)
        val s = mutableState.value
        prefs.edit {
            putString("live_look", s.liveLook.name)
            putBoolean("live_look_asked", s.liveLookAsked)
            putBoolean("autoplay_next", s.autoplayNext)
            putInt("forward_step", s.forwardStepSec)
            putInt("back_step", s.backStepSec)
            putString("decoder", s.decoder.name)
            putString("frame_rate", s.frameRate.name)
            putInt("watched_minutes", s.watchedMinutes)
            putInt("support_shown_at", s.supportShownAtMinutes)
            putBoolean("auto_update", s.autoUpdate)
            putLong("last_update_check", s.lastUpdateCheck)
            putString("decor", s.decor.name)
            putBoolean("remote_cursor", s.remoteCursor)
        }
    }
}
