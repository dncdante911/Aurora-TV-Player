package com.aurora.tvplayer;

import android.content.Context;
import android.media.quality.MediaQualityManager;
import android.os.Build;

/** Uses the public Android TV 16 API only; older vendor Ambilight remains TV-controlled. */
final class AmbientLighting {
    static Boolean isEnabled(Context context) {
        if (Build.VERSION.SDK_INT < 36) return null;
        try { return Api36.isEnabled(context); }
        catch (RuntimeException e) { return null; }
    }

    static boolean setEnabled(Context context, boolean enabled) {
        if (Build.VERSION.SDK_INT < 36) return false;
        try { return Api36.setEnabled(context, enabled); }
        catch (RuntimeException e) { return false; }
    }

    private static final class Api36 {
        static MediaQualityManager manager(Context context) {
            return context.getSystemService(MediaQualityManager.class);
        }

        static Boolean isEnabled(Context context) {
            MediaQualityManager manager = manager(context);
            return manager == null ? null : manager.isAmbientBacklightEnabled();
        }

        static boolean setEnabled(Context context, boolean enabled) {
            MediaQualityManager manager = manager(context);
            if (manager == null) return false;
            manager.setAmbientBacklightEnabled(enabled);
            return manager.isAmbientBacklightEnabled() == enabled;
        }
    }

    private AmbientLighting() { }
}
