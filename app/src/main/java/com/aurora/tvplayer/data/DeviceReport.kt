package com.aurora.tvplayer.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import java.util.Locale

data class ReportRow(val label: String, val value: String, val ok: Boolean? = null)
data class ReportSection(val title: String, val rows: List<ReportRow>)

/** What the firmware declares about screen, decoders and audio outputs. Declared, not measured. */
object DeviceReport {
    private val VIDEO = listOf(
        "H.264" to "video/avc", "HEVC" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9",
        "AV1" to "video/av01", "Dolby Vision" to "video/dolby-vision",
    )
    private val AUDIO = listOf(
        "AAC" to "audio/mp4a-latm", "AC-3" to "audio/ac3", "E-AC-3" to "audio/eac3",
        "DTS" to "audio/vnd.dts", "TrueHD" to "audio/true-hd", "FLAC" to "audio/flac", "Opus" to "audio/opus",
    )

    fun build(context: Context): List<ReportSection> = runCatching { inspect(context) }.getOrElse {
        listOf(ReportSection("Устройство", listOf(ReportRow("Сведения", "ТВ не предоставил полные данные. Воспроизведение работает с автоматическим выбором Media3."))))
    }

    private fun inspect(context: Context): List<ReportSection> {
        val sections = mutableListOf<ReportSection>()
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        sections += ReportSection("Экран", buildList {
            add(ReportRow("Устройство", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"))
            display?.let { d ->
                val mode = d.mode
                add(ReportRow("Сейчас", "${mode.physicalWidth}×${mode.physicalHeight} · ${String.format(Locale.ROOT, "%.0f", mode.refreshRate)} Гц"))
                val best = d.supportedModes.maxBy { it.physicalWidth * it.physicalHeight }
                add(ReportRow("Максимум", "${best.physicalWidth}×${best.physicalHeight}", best.physicalWidth >= 3840))
                @Suppress("DEPRECATION")
                val hdr = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
                listOf(
                    "Dolby Vision" to Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                    "HDR10" to Display.HdrCapabilities.HDR_TYPE_HDR10,
                    "HDR10+" to 4, // HDR_TYPE_HDR10_PLUS, API 29
                    "HLG" to Display.HdrCapabilities.HDR_TYPE_HLG,
                ).forEach { (name, type) ->
                    val ok = type in hdr
                    add(ReportRow(name, if (ok) "поддерживается" else "не заявлен", ok))
                }
            }
        })

        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
        sections += ReportSection("Видеодекодеры", VIDEO.map { (name, mime) ->
            val fps4k = supportedFps(codecs, mime, 3840, 2160)
            val fps1080 = supportedFps(codecs, mime, 1920, 1080)
            when {
                fps4k > 0 -> ReportRow(name, "4K · $fps4k fps", true)
                fps1080 > 0 -> ReportRow(name, "до 1080p · $fps1080 fps", true)
                hasDecoder(codecs, mime) -> ReportRow(name, "есть, без аппаратного 1080p", false)
                else -> ReportRow(name, "нет", false)
            }
        })

        sections += ReportSection("Аудиодекодеры", AUDIO.map { (name, mime) ->
            val ok = hasDecoder(codecs, mime)
            ReportRow(name, if (ok) "есть" else "нет", ok)
        })

        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().mapNotNull { device ->
            val name = outputName(device.type) ?: return@mapNotNull null
            val formats = device.encodings.toList().mapNotNull(::encodingName).distinct()
            ReportRow(name, formats.joinToString(", ").ifEmpty { "форматы не сообщены" }, formats.isNotEmpty())
        }
        sections += ReportSection("Цифровой звук (передача на ресивер)", outputs.ifEmpty { listOf(ReportRow("Выходы", "не сообщены")) })
        return sections
    }

    private fun supportedFps(codecs: Array<MediaCodecInfo>, mime: String, width: Int, height: Int): Int {
        var best = 0
        for (codec in codecs) {
            if (codec.isEncoder) continue
            if (Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated) continue
            // Before Android 10 there is no hardware flag; skip the known software decoders.
            if (Build.VERSION.SDK_INT < 29 && codec.name.lowercase(Locale.ROOT).startsWith("omx.google.")) continue
            if (codec.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { codec.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
            if (runCatching { caps.areSizeAndRateSupported(width, height, 60.0) }.getOrDefault(false)) return 60
            if (runCatching { caps.areSizeAndRateSupported(width, height, 30.0) }.getOrDefault(false)) best = 30
        }
        return best
    }

    private fun hasDecoder(codecs: Array<MediaCodecInfo>, mime: String) =
        codecs.any { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }

    private fun outputName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Оптика / S/PDIF"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        else -> null
    }

    private fun encodingName(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_AC3 -> "Dolby Digital"
        AudioFormat.ENCODING_E_AC3 -> "Dolby Digital Plus"
        AudioFormat.ENCODING_E_AC3_JOC -> "Atmos (DD+)"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        else -> null
    }
}
