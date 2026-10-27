package com.aurora.tvplayer

import android.net.Uri
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.ui.browser.BrowserEntry
import com.aurora.tvplayer.ui.browser.EntryKind
import com.aurora.tvplayer.ui.browser.FileStore
import com.aurora.tvplayer.ui.browser.LocalFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun video(file: File) = BrowserEntry(file.absolutePath, file.name, EntryKind.VIDEO, file.length(), Uri.fromFile(file).toString())

    @Test
    fun renameTakesSubtitlesAlong() {
        val dir = temp.newFolder("Сезон 1")
        val film = File(dir, "01. Пилот.mkv").apply { writeText("video") }
        File(dir, "01. Пилот.ru.srt").writeText("sub")
        val moved = LocalFiles.rename(video(film), "01. Начало.mkv", listOf("01. Пилот.ru.srt"))
        assertTrue(File(dir, "01. Начало.mkv").exists())
        assertTrue(File(dir, "01. Начало.ru.srt").exists())
        assertFalse(film.exists())
        assertEquals(Uri.fromFile(File(dir, "01. Начало.mkv")).toString(), moved.single().to)
    }

    @Test
    fun moveAndDelete() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val film = File(from, "Film.mkv").apply { writeText("video") }
        File(from, "Film.srt").writeText("sub")
        LocalFiles.move(video(film), to.absolutePath, listOf("Film.srt")) {}
        val arrived = File(to, "Film.mkv")
        assertTrue(arrived.exists() && File(to, "Film.srt").exists() && !film.exists())
        val deleted = LocalFiles.delete(video(arrived), listOf("Film.srt"))
        assertEquals(listOf(Uri.fromFile(arrived).toString()), deleted)
        assertTrue(to.listFiles()!!.isEmpty())
    }

    @Test(expected = IOException::class)
    fun folderCannotMoveIntoItself() {
        val parent = temp.newFolder("Сериал")
        val child = File(parent, "Сезон 2").apply { mkdir() }
        LocalFiles.move(BrowserEntry(parent.absolutePath, parent.name, EntryKind.FOLDER, 0, null), child.absolutePath, emptyList()) {}
    }

    @Test
    fun namesAreChecked() {
        assertNull(FileStore.problem("Сезон 1 (2024)"))
        assertNotNull(FileStore.problem("a/b"))
        assertNotNull(FileStore.problem("  "))
        assertNotNull(FileStore.problem(".."))
        assertEquals("Новое.eng.forced.srt", FileStore.companionName("Старое.mkv", "Новое.mkv", "Старое.eng.forced.srt"))
    }

    @Test
    fun naturalOrderKeepsEpisodesInPlace() {
        val names = listOf("Серия 10.mkv", "серия 2.mkv", "Серия 02a.mkv", "Серия 1.mkv", "Серия 100.mkv", "Серия.mkv")
        assertEquals(listOf("Серия 1.mkv", "серия 2.mkv", "Серия 02a.mkv", "Серия 10.mkv", "Серия 100.mkv", "Серия.mkv"), names.sortedWith(Media.naturalOrder))
    }
}
