package com.aurora.tvplayer.data

import android.content.Context
import android.media.quality.MediaQualityManager
import android.os.Build
import androidx.annotation.RequiresApi

/** Uses the public Android TV 16 API only; older vendor Ambilight stays under the TV's control. */
object AmbientLighting {
    /** null when the TV does not offer the system switch. */
    fun isEnabled(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT < 36) null else runCatching { manager(context)?.isAmbientBacklightEnabled }.getOrNull()

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            val manager = manager(context) ?: return false
            manager.setAmbientBacklightEnabled(enabled)
            manager.isAmbientBacklightEnabled == enabled
        }.getOrDefault(false)
    }

    @RequiresApi(36)
    private fun manager(context: Context): MediaQualityManager? = context.getSystemService(MediaQualityManager::class.java)
}
