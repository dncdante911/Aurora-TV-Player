package com.aurora.tvplayer

import android.app.Application
import android.content.Context
import com.aurora.tvplayer.data.AppSettings
import com.aurora.tvplayer.data.PlaybackStore
import com.aurora.tvplayer.data.Updater

/** Process-wide singletons, reached through [Context.aurora]. */
class AuroraApp : Application() {
    val playback by lazy { PlaybackStore(this) }
    val settings by lazy { AppSettings(this) }
    val updater by lazy { Updater(this) }
}

val Context.aurora: AuroraApp get() = applicationContext as AuroraApp
