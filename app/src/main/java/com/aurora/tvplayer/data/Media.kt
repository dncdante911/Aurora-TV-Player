package com.aurora.tvplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

object Media {
    private val EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "mpeg", "mpg",
        "flv", "3gp", "vob", "mts", "m2t", "wmv", "asf",
    )
    private val SUBTITLES = setOf("srt", "vtt", "ass", "ssa")

    fun isVideo(name: String?): Boolean = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in EXTENSIONS

    fun isSubtitle(name: String?): Boolean = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in SUBTITLES

    /**
     * Natural order: "Серия 2" before "Серия 10", case ignored. Walks both strings in place:
     * sorting a 2000-file folder makes ~20 000 comparisons, so nothing is allocated here.
     */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            if (x.isDigit() && y.isDigit()) {
                // Compare whole numbers: skip leading zeros, then the longer number is bigger.
                var xs = i
                while (xs < a.length && a[xs] == '0') xs++
                var ys = j
                while (ys < b.length && b[ys] == '0') ys++
                var xe = xs
                while (xe < a.length && a[xe].isDigit()) xe++
                var ye = ys
                while (ye < b.length && b[ye].isDigit()) ye++
                val lengths = (xe - xs).compareTo(ye - ys)
                if (lengths != 0) return@Comparator lengths
                for (k in 0 until xe - xs) {
                    val digit = a[xs + k].compareTo(b[ys + k])
                    if (digit != 0) return@Comparator digit
                }
                i = xe
                j = ye
            } else {
                val result = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (result != 0) return@Comparator result
                i++
                j++
            }
        }
        (a.length - i).compareTo(b.length - j)
    }

    /** "02. Рейхенбах.mkv" -> "02. Рейхенбах". */
    fun title(fileName: String): String = if (isVideo(fileName)) fileName.substringBeforeLast('.') else fileName

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentFile.fromSingleUri(context, uri)?.name?.let { return it }
            }
        }
        return uri.lastPathSegment?.let(Uri::decode)?.substringAfterLast('/') ?: "Видео"
    }

    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f ГБ", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} МБ"
        else -> "${(bytes shr 10).coerceAtLeast(1)} КБ"
    }

    fun plural(count: Int, one: String, few: String, many: String): String {
        val mod100 = count % 100
        val mod10 = count % 10
        val word = when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
        return "$count $word"
    }

    fun remaining(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return when {
            minutes < 1 -> "меньше минуты"
            minutes < 60 -> "ещё ${plural(minutes, "минута", "минуты", "минут")}"
            else -> "ещё ${minutes / 60} ч ${minutes % 60} мин"
        }
    }
}
