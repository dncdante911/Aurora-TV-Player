package com.aurora.tvplayer.ui.browser

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.PlaybackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

enum class SortMode(val label: String) {
    NAME("По имени"),
    NEWEST("Сначала новые"),
    LARGEST("Сначала большие"),
}

/** Disks first, then folders, then files; inside each group by [mode]. */
fun List<BrowserEntry>.sortedFor(mode: SortMode): List<BrowserEntry> = sortedWith(
    compareBy<BrowserEntry> { it.kind != EntryKind.VOLUME }
        .thenBy { it.kind != EntryKind.FOLDER }
        .then(
            when (mode) {
                SortMode.NAME -> Comparator { a, b -> Media.naturalOrder.compare(a.name, b.name) }
                SortMode.NEWEST -> compareByDescending<BrowserEntry> { it.modified }.thenComparator { a, b -> Media.naturalOrder.compare(a.name, b.name) }
                // Folders have no size of their own: they stay in name order.
                SortMode.LARGEST -> compareByDescending<BrowserEntry> { it.size }.thenComparator { a, b -> Media.naturalOrder.compare(a.name, b.name) }
            },
        ),
)

/** The sort order is shared by the USB and network browsers and kept between launches. */
class SortPreference(private val prefs: SharedPreferences) {
    var mode by mutableStateOf(runCatching { SortMode.valueOf(prefs.getString(KEY, null)!!) }.getOrDefault(SortMode.NAME))
        private set

    fun next() {
        mode = SortMode.entries[(mode.ordinal + 1) % SortMode.entries.size]
        prefs.edit { putString(KEY, mode.name) }
    }

    private companion object {
        const val KEY = "browser_sort"
    }
}

/**
 * Rename, delete, move and new folder for one browser. "Переместить" puts the entry on a
 * clipboard; the user walks to the target folder and presses «Вставить сюда». Positions and
 * history follow renamed and moved videos.
 */
class FileActions(
    private val store: FileStore,
    private val playback: PlaybackStore,
    private val scope: CoroutineScope,
    /** History folder for a video at this URI after the change. */
    private val folderOf: (String) -> String?,
    /** Called after every change so the open folder is read again. */
    private val changed: () -> Unit,
) {
    var clipboard by mutableStateOf<BrowserEntry?>(null)
        private set
    private var clipboardCompanions: List<String> = emptyList()
    /** "Переношу «Фильм» · 45%" while a long operation runs; the browser shows it with a spinner. */
    var busy by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    val working: Boolean get() = job?.isActive == true

    fun rename(entry: BrowserEntry, newName: String, companions: List<String>, report: (String) -> Unit) =
        run(report, "Переименовываю…") {
            relocate(store.rename(entry, newName, companions))
            "Переименовано в «$newName»"
        }

    fun delete(entry: BrowserEntry, companions: List<String>, report: (String) -> Unit) =
        run(report, "Удаляю «${entry.name}»…") {
            store.delete(entry, companions).forEach(playback::forget)
            "«${entry.name}» удалено"
        }

    fun cut(entry: BrowserEntry, companions: List<String>) {
        clipboard = entry
        clipboardCompanions = companions
    }

    fun cancelMove() {
        clipboard = null
        clipboardCompanions = emptyList()
    }

    fun paste(targetFolder: String, report: (String) -> Unit) {
        val entry = clipboard ?: return
        val companions = clipboardCompanions
        run(report, "Переношу «${entry.name}»…") {
            var shown = -1
            val moved = store.move(entry, targetFolder, companions) { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent != shown) {
                    shown = percent
                    busy = "Переношу «${entry.name}» · $percent%"
                }
            }
            relocate(moved)
            cancelMove()
            "«${entry.name}» перенесено сюда"
        }
    }

    fun createFolder(parent: String, name: String, report: (String) -> Unit) =
        run(report, "Создаю папку…") {
            store.createFolder(parent, name)
            "Папка «$name» создана"
        }

    /** A copy to another disk can take minutes; «Назад» does not stop it, this does. */
    fun cancel() {
        job?.cancel()
    }

    private fun relocate(moved: List<Relocated>) = moved.forEach { playback.move(it.from, it.to, it.title, folderOf(it.to)) }

    private fun run(report: (String) -> Unit, status: String, action: () -> String) {
        if (working) {
            report("Подождите, предыдущая операция ещё идёт")
            return
        }
        busy = status
        // The scope runs on the main thread; nothing suspends after the work, so a cancelled
        // operation still clears its status.
        job = scope.launch {
            val result = runCatching { runInterruptible(Dispatchers.IO) { action() } }
            busy = null
            result.onSuccess(report).onFailure { error ->
                report(if (error is CancellationException) "Операция отменена" else error.message ?: "Не получилось")
            }
            changed()
        }
    }
}
