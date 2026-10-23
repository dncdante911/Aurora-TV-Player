package com.aurora.tvplayer.ui.browser

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.tvplayer.aurora
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.net.SmbConnection
import com.aurora.tvplayer.player.PlayItem
import com.aurora.tvplayer.player.PlayRequest
import com.aurora.tvplayer.ui.components.FocusMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class EntryKind { VOLUME, FOLDER, VIDEO, OTHER }

/** One row of a file browser; [key] is the path or URL and identifies it for focus memory. */
data class BrowserEntry(val key: String, val name: String, val kind: EntryKind, val size: Long, val playUri: String?)

private fun isSystemFolder(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.startsWith(".") || lower.startsWith("$") || lower.startsWith("found.") ||
        lower == "android" || lower == "lost.dir" || lower == "system volume information"
}

private val browserOrder = compareBy<BrowserEntry> { it.kind != EntryKind.VOLUME }
    .thenBy { it.kind != EntryKind.FOLDER }
    .thenComparator { a, b -> Media.naturalOrder.compare(a.name, b.name) }

/** USB and internal storage, read folder by folder (never the whole disk). */
class LocalBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val playback = application.aurora.playback

    var title by mutableStateOf("Накопители")
        private set
    var root by mutableStateOf<File?>(null)
        private set
    var folder by mutableStateOf<File?>(null)
        private set
    var entries by mutableStateOf<List<BrowserEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var showAll by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    /** Volume waiting for the storage permission. */
    var pendingVolume: File? = null
        private set
    private var pendingTarget: File? = null
    private var job: Job? = null
    val focus = FocusMemory()

    val visible: List<BrowserEntry> by derivedStateOf {
        val needle = query.lowercase(Locale.ROOT)
        entries.filter { entry ->
            val shown = when (entry.kind) {
                EntryKind.VOLUME, EntryKind.VIDEO -> true
                EntryKind.FOLDER -> showAll || !isSystemFolder(entry.name)
                EntryKind.OTHER -> showAll
            }
            shown && entry.name.lowercase(Locale.ROOT).contains(needle)
        }
    }

    val atVolumes: Boolean get() = folder == null

    init { showVolumes() }

    fun showVolumes() {
        job?.cancel()
        root = null
        folder = null
        query = ""
        title = "Накопители"
        loading = false
        val volumes = mutableListOf<BrowserEntry>()
        runCatching {
            val manager = app.getSystemService(StorageManager::class.java)
            if (Build.VERSION.SDK_INT >= 30 && manager != null) {
                manager.storageVolumes.forEach { volume ->
                    val dir = volume.directory ?: return@forEach
                    val name = volume.getDescription(app)?.takeIf { it.isNotBlank() } ?: if (volume.isPrimary) "Память ТВ" else "USB-накопитель"
                    volumes += BrowserEntry(dir.absolutePath, name, EntryKind.VOLUME, 0, null)
                }
            } else {
                app.getExternalFilesDirs(null).forEachIndexed { i, appDir ->
                    var cursor: File? = appDir
                    while (cursor != null && cursor.name != "Android") cursor = cursor.parentFile
                    val dir = cursor?.parentFile ?: return@forEachIndexed
                    if (volumes.none { it.key == dir.absolutePath }) {
                        volumes += BrowserEntry(dir.absolutePath, if (i == 0) "Память ТВ" else "USB-накопитель", EntryKind.VOLUME, 0, null)
                    }
                }
            }
        }
        entries = volumes
        message = if (volumes.isEmpty()) "Телевизор не сообщил о накопителях. Проверьте подключение USB." else null
    }

    /** Opens the folder a previous session ended in, if its disk is still there. */
    fun restore(path: String) {
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        val volume = entries.firstOrNull { it.kind == EntryKind.VOLUME && (target.path == it.key || target.path.startsWith(it.key + File.separator)) } ?: return
        pendingTarget = target
        openVolume(File(volume.key))
    }

    fun open(entry: BrowserEntry): PlayRequest? = when (entry.kind) {
        EntryKind.VOLUME -> { openVolume(File(entry.key)); null }
        EntryKind.FOLDER -> { openFolder(File(entry.key)); null }
        EntryKind.VIDEO -> playlistFrom(entry)
        EntryKind.OTHER -> null
    }

    fun hasAccess(dir: File): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> runCatching { Environment.isExternalStorageManager(dir) }.getOrDefault(false)
        else -> app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Returns false and remembers the volume when the storage permission is still missing. */
    fun openVolume(dir: File): Boolean {
        if (!hasAccess(dir)) {
            pendingVolume = dir
            return false
        }
        pendingVolume = null
        root = dir
        app.getSharedPreferences("library", 0).edit { putString("folder_path", dir.absolutePath) }
        val target = pendingTarget?.takeIf { it.isDirectory } ?: dir
        pendingTarget = null
        openFolder(target)
        return true
    }

    /** Called when the app returns from the permission screen. */
    fun retryPendingVolume(): Boolean {
        val dir = pendingVolume ?: return false
        return hasAccess(dir) && openVolume(dir)
    }

    fun openFolder(dir: File) {
        val base = root ?: return
        job?.cancel()
        query = ""
        folder = dir
        title = if (dir == base) entries.firstOrNull { it.key == base.absolutePath }?.name ?: "Папки на диске" else dir.name
        playback.lastFolder = dir.absolutePath
        loading = true
        message = null
        entries = emptyList()
        job = viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) {
                runCatching { dir.listFiles() }.getOrNull()?.map { file ->
                    val directory = file.isDirectory
                    val kind = when {
                        directory -> EntryKind.FOLDER
                        Media.isVideo(file.name) -> EntryKind.VIDEO
                        else -> EntryKind.OTHER
                    }
                    BrowserEntry(file.absolutePath, file.name, kind, if (directory) 0 else file.length(), if (kind == EntryKind.VIDEO) Uri.fromFile(file).toString() else null)
                }?.sortedWith(browserOrder)
            }
            loading = false
            if (listed == null) message = "Нет доступа к папке. Разрешите доступ к файлам или проверьте формат диска."
            else entries = listed
        }
    }

    /** Goes one level up; false when already at the list of disks (the screen should close). */
    fun up(): Boolean {
        val current = folder ?: return false
        if (current == root) {
            focus.prefer(root?.absolutePath)
            showVolumes()
            return true
        }
        val parent = current.parentFile ?: run { showVolumes(); return true }
        focus.prefer(current.absolutePath)
        openFolder(parent)
        return true
    }

    fun toggleShowAll() { showAll = !showAll }
    fun search(text: String) { query = text.trim() }
    fun refresh() { folder?.let(::openFolder) ?: showVolumes() }

    private fun playlistFrom(selected: BrowserEntry): PlayRequest {
        val videos = entries.filter { it.kind == EntryKind.VIDEO }.take(500)
        val index = videos.indexOf(selected).coerceAtLeast(0)
        return PlayRequest(videos.map { PlayItem(it.playUri!!, Media.title(it.name)) }, index, folder?.absolutePath)
    }
}

/** SMB share browser; the connection lives for the process, the password is never stored. */
class NetworkBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("network", 0)

    var host by mutableStateOf(prefs.getString("host", "").orEmpty())
    var share by mutableStateOf(prefs.getString("share", "").orEmpty())
    var user by mutableStateOf(prefs.getString("user", "").orEmpty())
    var password by mutableStateOf("")
    var connecting by mutableStateOf(false)
        private set
    var showForm by mutableStateOf(SmbConnection.active == null)
    var folderUrl by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("Домашняя сеть")
        private set
    var entries by mutableStateOf<List<BrowserEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(if (SmbConnection.active == null) "Укажите адрес ПК или NAS и имя общей папки. ТВ и компьютер должны быть в одной сети." else null)
        private set
    var query by mutableStateOf("")
        private set
    private var job: Job? = null
    val focus = FocusMemory()

    val visible: List<BrowserEntry> by derivedStateOf {
        val needle = query.lowercase(Locale.ROOT)
        entries.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
    }

    init {
        SmbConnection.active?.let { openFolder(it.rootUrl) }
    }

    fun connect() {
        if (connecting) return
        val login = user.trim()
        val domain = login.substringBefore('\\', "")
        val name = login.substringAfter('\\')
        connecting = true
        message = "Подключаюсь к общей папке…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SmbConnection.connect(host.trim(), share.trim(), domain, name, password) }
            }
            connecting = false
            result.onSuccess { session ->
                prefs.edit { putString("host", host.trim()).putString("share", share.trim()).putString("user", login) }
                password = ""
                showForm = false
                openFolder(session.rootUrl)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = (error as? IllegalArgumentException)?.message
                    ?: "Нет соединения или доступа. Проверьте адрес, имя папки, логин и что на сервере включён SMB 2."
            }
        }
    }

    fun openFolder(url: String) {
        val session = SmbConnection.active ?: run { showForm = true; return }
        job?.cancel()
        folderUrl = url
        query = ""
        title = if (url == session.rootUrl) session.share else Uri.decode(url.trimEnd('/').substringAfterLast('/'))
        loading = true
        message = null
        entries = emptyList()
        job = viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) { runCatching { session.list(url) } }
            loading = false
            listed.onSuccess { found ->
                entries = found.filter { it.directory || it.video }.map {
                    BrowserEntry(it.url, it.name, if (it.directory) EntryKind.FOLDER else EntryKind.VIDEO, it.size, if (it.directory) null else it.url)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = "Не удалось открыть папку. Проверьте сеть и доступ, затем нажмите «Обновить»."
            }
        }
    }

    fun open(entry: BrowserEntry): PlayRequest? {
        if (entry.kind == EntryKind.FOLDER) {
            openFolder(entry.key)
            return null
        }
        val videos = entries.filter { it.kind == EntryKind.VIDEO }.take(500)
        return PlayRequest(videos.map { PlayItem(it.playUri!!, Media.title(it.name)) }, videos.indexOf(entry).coerceAtLeast(0), folderUrl)
    }

    fun up(): Boolean {
        val session = SmbConnection.active ?: return false
        val current = folderUrl ?: return false
        val parent = session.parentOf(current) ?: return false
        focus.prefer(current)
        openFolder(parent)
        return true
    }

    fun search(text: String) { query = text.trim() }
    fun refresh() { folderUrl?.let(::openFolder) }
}
