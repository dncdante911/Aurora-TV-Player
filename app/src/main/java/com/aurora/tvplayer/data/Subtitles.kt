package com.aurora.tvplayer.data

import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Subtitle files next to a video ("Фильм.srt", "Фильм.ru.ass") and their text encoding. */
object Subtitles {
    /** The longest subtitle file read into memory; real ones are well under 1 MB. */
    const val MAX_BYTES = 8 shl 20

    private val LANGUAGES = mapOf(
        "ru" to "ru", "rus" to "ru", "russian" to "ru", "рус" to "ru",
        "uk" to "uk", "ua" to "uk", "ukr" to "uk", "ukrainian" to "uk", "укр" to "uk",
        "en" to "en", "eng" to "en", "english" to "en", "англ" to "en",
        "de" to "de", "ger" to "de", "deu" to "de", "fr" to "fr", "fre" to "fr", "fra" to "fr",
        "es" to "es", "spa" to "es", "it" to "it", "ita" to "it", "pl" to "pl", "pol" to "pl",
        "ja" to "ja", "jpn" to "ja", "zh" to "zh", "chi" to "zh", "zho" to "zh",
    )

    /** Names among [siblings] that belong to [videoName]: same base name, subtitle extension. */
    fun matching(videoName: String, siblings: Collection<String>): List<String> {
        val base = videoName.substringBeforeLast('.').lowercase(Locale.ROOT)
        if (base.isEmpty()) return emptyList()
        return siblings.filter { name ->
            val lower = name.lowercase(Locale.ROOT)
            Media.isSubtitle(name) && (lower.substringBeforeLast('.') == base || lower.startsWith("$base."))
        }.sortedWith(Media.naturalOrder)
    }

    fun mimeType(name: String): String = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** "Фильм.ru.forced.srt" next to "Фильм.mkv" -> "ru"; null when the name carries no language. */
    fun language(videoName: String, subtitleName: String): String? =
        tags(videoName, subtitleName).firstNotNullOfOrNull { LANGUAGES[it] }

    /** What the track menu shows: "Фильм.ru.forced.srt" -> "Файл · ru · forced". */
    fun label(videoName: String, subtitleName: String): String =
        (listOf("Файл") + tags(videoName, subtitleName)).joinToString(" · ")

    private fun tags(videoName: String, subtitleName: String): List<String> {
        val base = videoName.substringBeforeLast('.')
        val middle = subtitleName.substringBeforeLast('.')
        if (middle.length <= base.length) return emptyList()
        return middle.substring(base.length).split('.', '_', ' ', '-').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
    }

    /**
     * Media3 reads SRT/SSA as UTF-8, but many Russian and Ukrainian subtitles are Windows-1251
     * and would show as garbage. UTF-8 and UTF-16 (with BOM) pass unchanged; anything else is
     * decoded as Windows-1251 when most letters are Cyrillic, otherwise as Windows-1252.
     */
    fun toUtf8(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) return bytes
        if (isUtf8(bytes)) return bytes
        return String(bytes, if (looksCyrillic(bytes)) WINDOWS_1251 else WINDOWS_1252).toByteArray(Charsets.UTF_8)
    }

    private fun isUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    /** In Windows-1251 the bytes 0xC0–0xFF are the Cyrillic alphabet; in Latin text they are rare accents. */
    private fun looksCyrillic(bytes: ByteArray): Boolean {
        var high = 0
        var latin = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                v >= 0xC0 -> high++
                v in 'A'.code..'Z'.code || v in 'a'.code..'z'.code -> latin++
            }
        }
        return high > 0 && high * 4 >= latin
    }

    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
}
