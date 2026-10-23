package com.aurora.tvplayer

import com.aurora.tvplayer.data.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    @Test
    fun episodesSortNaturally() {
        val files = listOf("Серия 10.mkv", "Серия 2.mkv", "Серия 1.mkv", "серия 3.mkv")
        assertEquals(listOf("Серия 1.mkv", "Серия 2.mkv", "серия 3.mkv", "Серия 10.mkv"), files.sortedWith(Media.naturalOrder))
    }

    @Test
    fun leadingZerosDoNotBreakOrder() {
        assertEquals(listOf("S01E02", "S01E10", "S02E01"), listOf("S02E01", "S01E10", "S01E02").sortedWith(Media.naturalOrder))
    }

    @Test
    fun recognizesVideoFiles() {
        assertTrue(Media.isVideo("02. Рейхенбах.MKV"))
        assertTrue(Media.isVideo("film.m2ts"))
        assertFalse(Media.isVideo("cover.jpg"))
        assertFalse(Media.isVideo("mkv"))
        assertTrue(Media.isSubtitle("film.ru.srt"))
    }

    @Test
    fun titlesDropOnlyVideoExtensions() {
        assertEquals("02. Рейхенбах", Media.title("02. Рейхенбах.mkv"))
        assertEquals("notes.txt", Media.title("notes.txt"))
    }

    @Test
    fun formatsTime() {
        assertEquals("00:03", Media.formatTime(3_000))
        assertEquals("42:26", Media.formatTime((42 * 60 + 26) * 1000L))
        assertEquals("1:02:03", Media.formatTime((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun russianPlurals() {
        assertEquals("1 папка", Media.plural(1, "папка", "папки", "папок"))
        assertEquals("4 папки", Media.plural(4, "папка", "папки", "папок"))
        assertEquals("12 папок", Media.plural(12, "папка", "папки", "папок"))
    }
}
