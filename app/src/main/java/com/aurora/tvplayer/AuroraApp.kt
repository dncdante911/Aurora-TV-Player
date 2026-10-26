package com.aurora.tvplayer

import android.app.Application
import android.content.Context
import com.aurora.tvplayer.data.AppSettings
import com.aurora.tvplayer.data.CrashLog
import com.aurora.tvplayer.data.PlaybackStore
import com.aurora.tvplayer.data.Updater
import com.aurora.tvplayer.net.SavedServers
import com.aurora.tvplayer.net.SmbConnection
import kotlin.concurrent.thread

/** Process-wide singletons, reached through [Context.aurora]. */
class AuroraApp : Application() {
    val playback by lazy { PlaybackStore(this) }
    val settings by lazy { AppSettings(this) }
    val updater by lazy { Updater(this) }
    val servers by lazy { SavedServers(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        SmbConnection.saved = servers
        thread(name = "aurora-prune", isDaemon = true, priority = Thread.MIN_PRIORITY) { runCatching { playback.prune() } }
    }
}

val Context.aurora: AuroraApp get() = applicationContext as AuroraApp
