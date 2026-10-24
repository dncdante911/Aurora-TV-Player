package com.aurora.tvplayer.data

import android.app.ActivityManager
import android.content.Context

/**
 * "Авто" for the live look: boxes with little memory or few cores keep the plain look so the
 * menus stay quick. 2 GB / 4-core boxes (Mi Box, Chromecast, most Amlogic S905) count as capable.
 */
object DevicePower {
    @Volatile private var weak: Boolean? = null

    fun isWeak(context: Context): Boolean = weak ?: run {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val result = manager == null || manager.isLowRamDevice || memory.totalMem in 1 until (1_500L shl 20) ||
            Runtime.getRuntime().availableProcessors() < 4
        weak = result
        result
    }

    fun liveLookOn(context: Context, mode: LiveLook): Boolean = when (mode) {
        LiveLook.ON -> true
        LiveLook.OFF -> false
        LiveLook.AUTO -> !isWeak(context)
    }
}
