package com.aurora.tvplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import java.util.Locale;

/** A firmware capability report, not a promise that every file will play smoothly. */
final class DeviceProfile {
    private static final String PREFS = "device_profile";
    private static final String[][] VIDEO = {
        {"H.264", "video/avc"}, {"HEVC", "video/hevc"},
        {"VP9", "video/x-vnd.on2.vp9"}, {"AV1", "video/av01"},
        {"Dolby Vision", "video/dolby-vision"}
    };
    private static final int[][] SIZES = {
        {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}
    };

    static String cached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return key(context).equals(prefs.getString("key", null))
            ? prefs.getString("report", null) : null;
    }

    static String refresh(Context context) {
        String report;
        try { report = inspect(context); }
        catch (RuntimeException e) {
            report = "Телевизор не предоставил полные сведения о режимах/декодерах. "
                + "Воспроизведение продолжит работать с автоматическим выбором Media3. "
                + "Можно повторить проверку позже.";
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", key(context)).putString("report", report).apply();
        return report;
    }

    private static String key(Context context) {
        Display display = display(context);
        String mode = display == null ? "none" : String.valueOf(display.getMode().getModeId());
        return "2:" + Build.FINGERPRINT + ":" + Build.VERSION.SDK_INT + ":" + mode;
    }

    private static Display display(Context context) {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return manager == null ? null : manager.getDefaultDisplay();
    }

    private static String inspect(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("ТВ: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE).append('\n');
        Display display = display(context);
        if (display != null) {
            Display.Mode active = display.getMode();
            out.append("Режим интерфейса: ").append(active.getPhysicalWidth()).append('×')
                .append(active.getPhysicalHeight()).append(String.format(Locale.ROOT, " · %.1f Гц\n", active.getRefreshRate()));
            int maxPixels = 0;
            Display.Mode best = active;
            for (Display.Mode mode : display.getSupportedModes()) {
                int pixels = mode.getPhysicalWidth() * mode.getPhysicalHeight();
                if (pixels > maxPixels) { maxPixels = pixels; best = mode; }
            }
            out.append("Максимальный заявленный режим экрана: ")
                .append(best.getPhysicalWidth()).append('×').append(best.getPhysicalHeight()).append('\n');
            if (Build.VERSION.SDK_INT >= 24) {
                Display.HdrCapabilities hdrCapabilities = display.getHdrCapabilities();
                int[] hdr = hdrCapabilities == null ? new int[0] : hdrCapabilities.getSupportedHdrTypes();
                out.append("HDR экрана: ");
                if (hdr.length == 0) out.append("не заявлен");
                for (int i = 0; i < hdr.length; i++) {
                    if (i > 0) out.append(", ");
                    switch (hdr[i]) {
                        case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: out.append("Dolby Vision"); break;
                        case Display.HdrCapabilities.HDR_TYPE_HDR10: out.append("HDR10"); break;
                        case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: out.append("HDR10+"); break;
                        case Display.HdrCapabilities.HDR_TYPE_HLG: out.append("HLG"); break;
                        default: out.append("тип ").append(hdr[i]);
                    }
                }
                out.append('\n');
            }
        }
        out.append("\nДекодирование, заявленное прошивкой (30/60 fps):\n");
        MediaCodecInfo[] codecs;
        try { codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos(); }
        catch (RuntimeException e) { codecs = new MediaCodecInfo[0]; }
        for (int[] size : SIZES) {
            out.append(size[1]).append("p: ");
            boolean any = false;
            for (String[] format : VIDEO) {
                int fps = supportedFps(codecs, format[1], size[0], size[1]);
                if (fps == 0) continue;
                if (any) out.append(" · ");
                out.append(format[0]).append(' ').append(fps);
                any = true;
            }
            if (!any) out.append("не заявлено");
            out.append('\n');
        }
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        out.append("\nАудиовыходы, видимые Android (не обязательно активные):\n");
        boolean anyOutput = false;
        if (audio != null) {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String name = outputName(device.getType());
                if (name == null) continue;
                anyOutput = true;
                out.append(name).append(": ");
                int[] encodings = device.getEncodings();
                if (encodings == null) encodings = new int[0];
                boolean anyEncoding = false;
                for (int encoding : encodings) {
                    String codec = encodingName(encoding);
                    if (codec == null) continue;
                    if (anyEncoding) out.append(", ");
                    out.append(codec);
                    anyEncoding = true;
                }
                if (!anyEncoding) out.append("форматы не сообщены");
                out.append('\n');
            }
        }
        if (!anyOutput) out.append("Внешние цифровые выходы не сообщены\n");
        out.append("\nАвтонастройка: Media3 выбирает совместимую дорожку адаптивного потока; "
            + "системные декодеры и вывод изображения/звука остаются приоритетом. "
            + "Видео не ограничивается разрешением меню ТВ: интерфейс может быть 1080p при 4K-воспроизведении. "
            + "При ошибке запуска декодера включён запасной системный вариант.\n\n"
            + "Это данные прошивки, а не тест скорости, HDR-профиля или конкретного файла. "
            + "На Android до 10 тип декодера может быть определён неточно. "
            + "720p/1080p/1440p масштабируются экраном ТВ без искусственного добавления деталей. "
            + "Для Dolby Vision нужны подходящий файл, системный декодер и поддержка экрана. "
            + "Для AC-3, DTS и Atmos нужны подходящая дорожка и поддержка всей аудиоцепочки; "
            + "приложение не меняет настройки цифрового выхода ТВ.");
        return out.toString();
    }

    private static String outputName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "Оптика / S/PDIF";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "HDMI ARC";
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "HDMI eARC";
            default: return null;
        }
    }

    private static String encodingName(int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_AC3: return "Dolby Digital / AC-3";
            case AudioFormat.ENCODING_E_AC3: return "Dolby Digital Plus / E-AC-3";
            case AudioFormat.ENCODING_E_AC3_JOC: return "E-AC-3 JOC (Atmos)";
            case AudioFormat.ENCODING_DTS: return "DTS";
            case AudioFormat.ENCODING_DTS_HD: return "DTS-HD";
            case AudioFormat.ENCODING_DOLBY_TRUEHD: return "TrueHD";
            default: return null;
        }
    }

    private static int supportedFps(MediaCodecInfo[] codecs, String mime, int width, int height) {
        int best = 0;
        for (MediaCodecInfo codec : codecs) {
            if (codec.isEncoder() || (Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated())) continue;
            // Before API 29 the platform does not expose a reliable hardware/software flag.
            if (Build.VERSION.SDK_INT < 29 && codec.getName().toLowerCase(Locale.ROOT).startsWith("omx.google.")) continue;
            String[] types;
            try { types = codec.getSupportedTypes(); }
            catch (RuntimeException e) { continue; }
            for (String type : types) {
                if (!mime.equalsIgnoreCase(type)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities capabilities = codec.getCapabilitiesForType(type).getVideoCapabilities();
                    if (capabilities == null) continue;
                    if (capabilities.areSizeAndRateSupported(width, height, 60)) return 60;
                    if (capabilities.areSizeAndRateSupported(width, height, 30)) best = 30;
                } catch (RuntimeException ignored) { }
            }
        }
        return best;
    }

    private DeviceProfile() { }
}
