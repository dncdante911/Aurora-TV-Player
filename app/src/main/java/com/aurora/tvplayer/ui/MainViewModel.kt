package com.aurora.tvplayer.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.tvplayer.aurora
import com.aurora.tvplayer.data.LiveLook
import com.aurora.tvplayer.data.HistoryEntry
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.Occasion
import com.aurora.tvplayer.data.Subtitles
import com.aurora.tvplayer.net.SmbConnection
import com.aurora.tvplayer.player.PlayItem
import com.aurora.tvplayer.player.PlayRequest
import com.aurora.tvplayer.ui.components.DialogAction
import com.aurora.tvplayer.ui.components.DialogSpec
import com.aurora.tvplayer.ui.components.FocusMemory
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Files(val initialFolder: String? = null) : Screen
    data object Network : Screen
    data object Device : Screen
    data object Capabilities : Screen
    data object Support : Screen
}

/** Things only the activity can do. */
sealed interface UiEvent {
    data class Play(val request: PlayRequest) : UiEvent
    data object PickFile : UiEvent
    data class Voice(val language: String) : UiEvent
    data class RequestStorageAccess(val volume: File) : UiEvent
    data object OpenAppSettings : UiEvent
    data object AllowInstalls : UiEvent
    data object CancelVoice : UiEvent
}

data class FolderVideo(val uri: String, val title: String, val subtitles: List<String> = emptyList())

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.aurora
    val playback = app.playback
    val settings = app.settings
    val servers = app.servers

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    var dialog by mutableStateOf<DialogSpec?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var voice by mutableStateOf<com.aurora.tvplayer.VoiceState>(com.aurora.tvplayer.VoiceState.Off)

    /** Settings → "Посмотреть оформление": shows any decoration until the app restarts. */
    var decorPreview by mutableStateOf<Occasion?>(null)
    private var messageJob: Job? = null

    /** Videos of the last opened USB folder, shown on the home screen. */
    var folderVideos by mutableStateOf<List<FolderVideo>>(emptyList())
        private set
    var folderName by mutableStateOf<String?>(null)
        private set
    private var scannedFolder: String? = null

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    val homeFocus = FocusMemory()
    private var remindedThisLaunch = false

    fun open(screen: Screen) {
        if (screen == current) return
        if (screen == Screen.Home) {
            stack.clear()
            stack.add(Screen.Home)
        } else stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun show(spec: DialogSpec) { dialog = spec }
    fun dismissDialog() { dialog = null }

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(3_000)
            message = null
        }
    }

    fun send(event: UiEvent) { events.trySend(event) }

    /** Reads the last USB folder once per change; never crawls the whole disk at start. */
    fun refreshLastFolder() {
        val path = playback.lastFolder ?: return
        if (path == scannedFolder && folderVideos.isNotEmpty()) return
        folderName = friendlyFolderName(File(path))
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val folder = File(path)
                val allowed = Build.VERSION.SDK_INT < 30 || runCatching { Environment.isExternalStorageManager(folder) }.getOrDefault(false)
                if (!allowed) return@withContext null
                localPlaylist(folder)?.map { FolderVideo(it.uri, it.title, it.subtitles) }
            }
            scannedFolder = path
            folderVideos = found.orEmpty()
        }
    }

    /** A disk root is named by its volume id ("CA44AF7444AF6245", "1A2B-3C4D"); show what it is instead. */
    private fun friendlyFolderName(folder: File): String {
        val parent = folder.parentFile?.absolutePath
        return when {
            parent == "/storage/emulated" -> "Память ТВ"
            parent == "/storage" && Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8,16}").matches(folder.name) -> "USB-накопитель"
            else -> folder.name
        }
    }

    fun playFolderVideo(index: Int) {
        val items = folderVideos.map { PlayItem(it.uri, it.title, it.subtitles) }
        if (items.isNotEmpty()) send(UiEvent.Play(PlayRequest.around(items, index, playback.lastFolder)))
    }

    /** Continue a history entry with its folder as the playlist, so the next episode follows. */
    fun resume(entry: HistoryEntry) {
        viewModelScope.launch {
            if (entry.uri.startsWith("smb://") && !SmbConnection.canOpen(entry.uri)) {
                // The app was stopped since: come back with the saved login, if the user let us keep it.
                toast("Подключаюсь к сетевой папке…")
                val connected = withContext(Dispatchers.IO) { runCatching { SmbConnection.forUrl(entry.uri) }.isSuccess }
                if (!connected) {
                    toast("Сначала подключитесь к сетевой папке")
                    open(Screen.Network)
                    return@launch
                }
            }
            val request = withContext(Dispatchers.IO) { playlistAround(entry) }
            send(UiEvent.Play(request))
        }
    }

    /** Videos of a local folder in natural order, each with the subtitle files next to it. */
    private fun localPlaylist(folder: File): List<PlayItem>? {
        val files = folder.listFiles() ?: return null
        val subtitles = files.filter { it.isFile && Media.isSubtitle(it.name) }.associate { it.name to Uri.fromFile(it).toString() }
        return files.filter { it.isFile && Media.isVideo(it.name) }
            .sortedWith { a, b -> Media.naturalOrder.compare(a.name, b.name) }
            .map { PlayItem(Uri.fromFile(it).toString(), Media.title(it.name), Subtitles.matching(it.name, subtitles.keys).mapNotNull(subtitles::get)) }
    }

    private fun playlistAround(entry: HistoryEntry): PlayRequest {
        val single = PlayRequest(listOf(PlayItem(entry.uri, entry.title)), 0, entry.folder)
        val items: List<PlayItem> = runCatching {
            when {
                entry.uri.startsWith("file:") -> {
                    val folder = entry.folder?.let(::File) ?: entry.uri.toUri().path?.let { File(it).parentFile } ?: return single
                    localPlaylist(folder)
                }
                entry.uri.startsWith("smb://") -> {
                    val session = SmbConnection.active ?: return single
                    val parent = session.parentOf(entry.uri) ?: session.rootUrl
                    val listed = session.list(parent)
                    val subtitles = listed.filter { it.subtitle }.associate { it.name to it.url }
                    listed.filter { it.video }.map { PlayItem(it.url, Media.title(it.name), Subtitles.matching(it.name, subtitles.keys).mapNotNull(subtitles::get)) }
                }
                else -> null
            }
        }.getOrNull().orEmpty()
        val index = items.indexOfFirst { it.uri == entry.uri }
        return if (index >= 0) PlayRequest.around(items, index, entry.folder) else single
    }

    fun historyActions(entry: HistoryEntry) = show(DialogSpec.Message(
        title = entry.title,
        text = "Что сделать с этим видео?",
        actions = listOf(
            DialogAction("Смотреть", Icons.Play) { resume(entry) },
            DialogAction("Убрать из истории", Icons.Delete) {
                playback.forget(entry.uri)
                toast("Убрано из истории")
            },
        ),
    ))

    fun askLink() = show(DialogSpec.Input("Открыть сетевое видео", "https://example.com/video.m3u8", "", "Открыть") { raw ->
        val uri = raw.trim().toUri()
        if ((uri.scheme == "http" || uri.scheme == "https") && uri.host != null) {
            send(UiEvent.Play(PlayRequest(listOf(PlayItem(uri.toString(), uri.lastPathSegment?.let(Media::title) ?: "Сетевое видео")))))
        } else toast("Введите ссылку http или https")
    })

    fun chooseVoiceLanguage() = show(DialogSpec.Choice("Голосовая команда · язык", listOf("Русский", "Українська", "English")) { index ->
        send(UiEvent.Voice(listOf("ru-RU", "uk-UA", "en-US")[index]))
    })

    fun handleVoice(spoken: String) {
        toast("Услышал: «$spoken»")
        val command = spoken.lowercase(Locale.ROOT).trim()
        fun has(vararg words: String) = words.any { command.contains(it) }
        when {
            has("сеть", "сетев", "мереж", "nas", "network", "компьютер") -> open(Screen.Network)
            has("провод", "провід", "папк", "folder", "browse", "usb", "флешк") -> open(Screen.Files())
            has("ссыл", "посилан", "link", "url") -> askLink()
            has("продолж", "продовж", "resume", "continue") ->
                playback.history.value.firstOrNull()?.let(::resume) ?: toast("История просмотра пока пуста")
            has("звук", "аудіо", "sound", "audio", "dolby") -> showSoundHelp()
            has("телевизор", "телевізор", "устройств", "device") -> open(Screen.Device)
            command in setOf("открой видео", "відкрий відео", "open video", "open file") -> send(UiEvent.PickFile)
            else -> {
                val query = command.replaceFirst(Regex("^(найди|найти|знайди|знайти|включи|увімкни|покажи|find|play|show)\\s+"), "").trim()
                val inFolder = folderVideos.indexOfFirst { query.isNotEmpty() && it.title.lowercase(Locale.ROOT).contains(query) }
                val inHistory = playback.history.value.firstOrNull { query.isNotEmpty() && it.title.lowercase(Locale.ROOT).contains(query) }
                when {
                    inFolder >= 0 -> playFolderVideo(inFolder)
                    inHistory != null -> resume(inHistory)
                    else -> toast("Не нашлось: «$spoken». Назовите файл или команду.")
                }
            }
        }
    }

    /**
     * A thank-you note on the home screen after every ~10 hours of watching, at most once per app
     * launch. The player never shows it: this runs only when the home screen opens.
     */
    /** Asked once on the home screen after the update; never during playback. */
    fun maybeAskLiveLook() {
        if (settings.current.liveLookAsked || dialog != null) return
        settings.markLiveLookAsked()
        val modes = listOf(LiveLook.ON, LiveLook.AUTO, LiveLook.OFF)
        show(DialogSpec.Choice(
            title = "Включить живое оформление?",
            text = "Живой фон «Северное сияние», снег зимой, листопад осенью, украшения и салют к праздникам. " +
                "Изменить можно в любой момент: «Мой телевизор» → «Живое оформление».",
            options = listOf("Да, включить", "Авто — плеер сам оценит мощность приставки", "Нет, простой вид без анимации"),
            selected = 1,
        ) { index -> settings.setLiveLook(modes[index]) })
    }

    fun maybeRemindSupport() {
        if (remindedThisLaunch) return
        remindedThisLaunch = true
        val s = settings.current
        if (!com.aurora.tvplayer.data.Support.available || dialog != null) return
        if (s.watchedMinutes - s.supportShownAtMinutes < com.aurora.tvplayer.data.Support.REMINDER_AFTER_MINUTES) return
        settings.markSupportReminderShown()
        show(DialogSpec.Message(
            "Нравится Aurora TV?",
            "С плеером уже ${s.watchedMinutes / 60} ч просмотра — спасибо! Aurora TV бесплатный и без рекламы. " +
                "Если хотите, можно поддержать автора: QR-код откроется на экране, оплатить можно телефоном.",
            listOf(DialogAction("Поддержать автора", Icons.Favorite) { open(Screen.Support) }),
        ))
    }

    val updater = app.updater

    /** "Обновить": confirm with the release notes, ask for the install permission once if needed. */
    fun installUpdate() {
        val ready = updater.state.value as? com.aurora.tvplayer.data.UpdateState.Ready ?: return
        if (!updater.canInstall()) {
            show(DialogSpec.Message(
                "Разрешите установку обновлений",
                "Android один раз попросит разрешить Aurora TV устанавливать приложения. Включите переключатель и вернитесь — обновление продолжится.",
                listOf(DialogAction("Открыть настройки", Icons.Settings) { send(UiEvent.AllowInstalls) }),
            ))
            return
        }
        val notes = ready.release.notes.ifBlank { "Новая версия Aurora TV." }.take(1_200)
        show(DialogSpec.Message(
            "Обновить до ${ready.release.version}?",
            "$notes\n\nПлеер закроется на пару секунд. Настройки, история и позиции просмотра сохранятся.",
            listOf(DialogAction("Установить", Icons.Up) { updater.install() }),
        ))
    }

    fun showSoundHelp() = show(DialogSpec.Message(
        "Звук, HDR и Dolby Vision",
        "HDR10, HDR10+, HLG и Dolby Vision включаются системой автоматически, когда файл, декодер и экран совместимы. " +
            "Если поток содержит отдельные HDR- и SDR-дорожки, нужную можно выбрать в плеере: «Настройки → Качество видео».\n\n" +
            "Dolby Digital, DTS и Atmos зависят от дорожки и всей аудиоцепочки. Для саундбара по оптике выберите на ТВ " +
            "оптический выход и формат Auto или Pass-through. Если дорожка молчит — включите PCM в настройках ТВ или выберите другую дорожку в плеере. " +
            "Оптика не передаёт Dolby TrueHD/Atmos и многоканальный PCM. На скорости 1× сквозная передача звука работает надёжнее всего.",
    ))
}
