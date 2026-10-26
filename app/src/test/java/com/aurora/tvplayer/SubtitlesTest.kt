package com.aurora.tvplayer

import com.aurora.tvplayer.data.Subtitles
import com.aurora.tvplayer.net.SmbConnection
import com.aurora.tvplayer.player.PlayItem
import com.aurora.tvplayer.player.PlayRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubtitlesTest {
    private val folder = listOf(
        "02. Рейхенбах.mkv", "02. Рейхенбах.srt", "02. Рейхенбах.ru.srt", "02. Рейхенбах.eng.forced.ass",
        "02. Рейхенбах 2.srt", "03. Пустой катафалк.srt", "cover.jpg",
    )

    @Test
    fun findsSubtitlesOfTheSameVideoOnly() {
        assertEquals(
            listOf("02. Рейхенбах.eng.forced.ass", "02. Рейхенбах.ru.srt", "02. Рейхенбах.srt"),
            Subtitles.matching("02. Рейхенбах.mkv", folder).sorted(),
        )
    }

    @Test
    fun readsLanguageAndLabelFromTheName() {
        assertEquals("ru", Subtitles.language("Film.mkv", "Film.rus.srt"))
        assertEquals("en", Subtitles.language("Film.mkv", "Film.eng.forced.ass"))
        assertNull(Subtitles.language("Film.mkv", "Film.srt"))
        assertEquals("Файл · eng · forced", Subtitles.label("Film.mkv", "Film.eng.forced.ass"))
    }

    @Test
    fun windows1251BecomesUtf8() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nПривет, Шерлок!\n"
        assertEquals(text, String(Subtitles.toUtf8(text.toByteArray(charset("windows-1251"))), Charsets.UTF_8))
    }

    @Test
    fun utf8AndLatinStayReadable() {
        val utf8 = "Привет, Ватсон".toByteArray(Charsets.UTF_8)
        assertArrayEquals(utf8, Subtitles.toUtf8(utf8))
        val french = "Où est le café, Hélène? Le chat est là."
        assertEquals(french, String(Subtitles.toUtf8(french.toByteArray(charset("windows-1252"))), Charsets.UTF_8))
    }

    @Test
    fun smbAddressSplitsIntoHostAndShare() {
        assertEquals("nas" to "Фильмы и сериалы", SmbConnection.hostAndShare("smb://nas/%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B%20%D0%B8%20%D1%81%D0%B5%D1%80%D0%B8%D0%B0%D0%BB%D1%8B/a.mkv"))
        assertNull(SmbConnection.hostAndShare("file:///storage/a.mkv"))
        assertNull(SmbConnection.hostAndShare("smb://nas"))
    }

    @Test
    fun hugeFoldersAreCutAroundTheChosenVideo() {
        val items = List(1000) { PlayItem("file:///v/$it.mkv", "$it") }
        val request = PlayRequest.around(items, 700, "/v")
        assertEquals(300, request.items.size)
        assertEquals("700", request.items[request.index].title)
        assertTrue(request.index > 0)
        assertEquals(5, PlayRequest.around(items.take(5), 9, null).items.size)
    }
}
