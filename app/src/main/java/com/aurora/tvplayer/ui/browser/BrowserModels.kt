package com.aurora.tvplayer.ui.browser

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.tvplayer.aurora
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.Subtitles
import com.aurora.tvplayer.net.SmbConnection
import com.aurora.tvplayer.net.SmbServer
import com.aurora.tvplayer.player.PlayItem
import com.aurora.tvplayer.player.PlayRequest
import com.aurora.tvplayer.ui.components.FocusMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

enum class EntryKind { VOLUME, FOLDER, VIDEO, OTHER }

/** One row of a file browser; [key] is the path or URL and identifies it for focus memory. */
data class BrowserEntry(val key: String, val name: String, val kind: EntryKind, val size: Long, val playUri: String?, val modified: Long = 0)

private fun isSystemFolder(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.startsWith(".") || lower.startsWith("$") || lower.startsWith("found.") ||
        lower == "android" || lower == "lost.dir" || lower == "system volume information"
}

/** A folder read earlier; reused while its modification time has not changed. */
private class CachedFolder(val modified: Long, val entries: List<BrowserEntry>)

/** USB and internal storage, read folder by folder (never the whole disk). */
class LocalBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val playback = application.aurora.playback

    var title by mutableStateOf("Накопители")
        private set
    var root by mutableStateOf<File?>(null)
        private set
    /** Human name of the open disk ("USB-накопитель", "Память ТВ" or the volume label). */
    var rootName by mutableStateOf("")
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
    val sort = SortPreference(application.getSharedPreferences("library", 0))
    val files = FileActions(
        LocalFiles, playback, viewModelScope,
        folderOf = { uri -> uri.toUri().path?.let { File(it).parent } },
        changed = { cache.clear(); refresh() },
    )
    /** Going back up a level shows the folder at once instead of reading the USB again. */
    private val cache = object : LinkedHashMap<String, CachedFolder>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFolder>?) = size > 24
    }

    val visible: List<BrowserEntry> by derivedStateOf {
        val needle = query.lowercase(Locale.ROOT)
        entries.filter { entry ->
            val shown = when (entry.kind) {
                EntryKind.VOLUME, EntryKind.VIDEO -> true
                EntryKind.FOLDER -> showAll || !isSystemFolder(entry.name)
                EntryKind.OTHER -> showAll
            }
            shown && entry.name.lowercase(Locale.ROOT).contains(needle)
        }.sortedFor(sort.mode)
    }

    /** Subtitle files that belong to [entry] and travel with it. */
    fun companionsOf(entry: BrowserEntry): List<String> =
        if (entry.kind == EntryKind.VIDEO) Subtitles.matching(entry.name, entries.map { it.name }) else emptyList()

    /** Android 11+: «Доступ ко всем файлам» covers writing too; 9–10 need the write permission. */
    fun canWrite(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> folder?.let(::hasAccess) == true
        else -> app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
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
        rootName = entries.firstOrNull { it.key == dir.absolutePath }?.name ?: "Диск"
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
        title = if (dir == base) rootName.ifEmpty { "Папки на диске" } else dir.name
        playback.lastFolder = dir.absolutePath
        message = null
        // A cached folder shows at once; the check below replaces it if the disk changed.
        val cached = cache[dir.absolutePath]
        entries = cached?.entries.orEmpty()
        loading = cached == null
        job = viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) {
                val modified = dir.lastModified()
                if (cached != null && cached.modified == modified && modified != 0L) cached.entries
                else read(dir)?.also { cache[dir.absolutePath] = CachedFolder(modified, it) }
            }
            loading = false
            if (listed == null) message = "Нет доступа к папке. Разрешите доступ к файлам или проверьте формат диска."
            else if (listed !== entries) entries = listed
        }
    }

    /**
     * One attribute read per file. On Android 11+ USB disks sit behind FUSE, where each stat is a
     * round trip; File.isDirectory() plus File.length() cost two and made big folders slow.
     */
    private fun read(dir: File): List<BrowserEntry>? = runCatching {
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            stream.mapNotNull { path ->
                val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull() ?: return@mapNotNull null
                val name = path.fileName.toString()
                val kind = when {
                    attributes.isDirectory -> EntryKind.FOLDER
                    Media.isVideo(name) -> EntryKind.VIDEO
                    else -> EntryKind.OTHER
                }
                val file = path.toFile()
                BrowserEntry(file.absolutePath, name, kind, if (attributes.isDirectory) 0 else attributes.size(),
                    if (kind == EntryKind.VIDEO) Uri.fromFile(file).toString() else null, attributes.lastModifiedTime().toMillis())
            }
        }
    }.getOrNull()

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
    fun refresh() {
        folder?.let { cache.remove(it.absolutePath); openFolder(it) } ?: showVolumes()
    }

    private fun playlistFrom(selected: BrowserEntry): PlayRequest {
        val videos = entries.filter { it.kind == EntryKind.VIDEO }
        val subtitles = entries.filter { Media.isSubtitle(it.name) }.associate { it.name to Uri.fromFile(File(it.key)).toString() }
        return PlayRequest.around(
            videos.map { video -> PlayItem(video.playUri!!, Media.title(video.name), Subtitles.matching(video.name, subtitles.keys).mapNotNull(subtitles::get)) },
            videos.indexOf(selected).coerceAtLeast(0),
            folder?.absolutePath,
        )
    }
}

/**
 * SMB share browser. The connection lives for the process; with «Запомнить пароль» the share
 * also comes back by itself after Android stopped the app (see [SavedServers]).
 */
class NetworkBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("network", 0)
    private val servers = application.aurora.servers
    val sort = SortPreference(application.getSharedPreferences("library", 0))
    val files = FileActions(
        SmbFiles, application.aurora.playback, viewModelScope,
        folderOf = { url -> url.substringBeforeLast('/') + "/" },
        changed = { cache.clear(); refresh() },
    )
    /** Listings of the last folders for going back; a server gives no cheap "changed?" check, so they expire. */
    private val cache = HashMap<String, Pair<Long, List<SmbConnection.Entry>>>()

    var host by mutableStateOf(prefs.getString("host", "").orEmpty())
    var share by mutableStateOf(prefs.getString("share", "").orEmpty())
    var user by mutableStateOf(prefs.getString("user", "").orEmpty())
    var password by mutableStateOf("")
    var remember by mutableStateOf(prefs.getBoolean("remember_password", true))
        private set
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
    /** Recently used shares, newest first. */
    var saved by mutableStateOf(servers.all())
        private set
    /** Shares of [host], listed when «Подключить» is pressed with an empty folder name. */
    var shares by mutableStateOf<List<String>>(emptyList())
        private set
    /** Computers and NAS that announce SMB on the network (Bonjour/mDNS): name to address. */
    var discovered by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    /** Subtitle files of the open folder, by name. */
    private var subtitleUrls: Map<String, String> = emptyMap()
    private var job: Job? = null
    val focus = FocusMemory()

    // Declared before init, which starts the discovery.
    private val nsd = application.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val visible: List<BrowserEntry> by derivedStateOf {
        val needle = query.lowercase(Locale.ROOT)
        entries.filter { it.name.lowercase(Locale.ROOT).contains(needle) }.sortedFor(sort.mode)
    }

    fun companionsOf(entry: BrowserEntry): List<String> =
        if (entry.kind == EntryKind.VIDEO) Subtitles.matching(entry.name, subtitleUrls.keys) else emptyList()

    init {
        val active = SmbConnection.active
        val last = servers.last()
        when {
            active != null -> openFolder(active.rootUrl)
            last != null && last.canReconnect -> useServer(last)
        }
        startDiscovery()
    }

    fun toggleRemember() {
        remember = !remember
        prefs.edit { putBoolean("remember_password", remember) }
    }

    /** A saved share: reconnects silently when it can, otherwise fills the form for the password. */
    fun useServer(server: SmbServer) {
        if (connecting) return
        host = server.host
        share = server.share
        user = server.login
        password = ""
        shares = emptyList()
        if (!server.canReconnect) {
            showForm = true
            message = "Введите пароль для ${server.label} и нажмите «Подключить»."
            return
        }
        connecting = true
        message = "Подключаюсь к ${server.label}…"
        viewModelScope.launch {
            val connected = withContext(Dispatchers.IO) { SmbConnection.reconnect(server) }
            connecting = false
            saved = servers.all()
            if (connected) {
                showForm = false
                SmbConnection.active?.let { openFolder(it.rootUrl) }
            } else {
                showForm = true
                message = "Не удалось подключиться к ${server.label}. Проверьте, что сервер включён, или введите пароль заново."
            }
        }
    }

    fun forgetSaved(server: SmbServer) {
        servers.remove(server)
        saved = servers.all()
    }

    /** A computer found on the network: its address goes into the form, the folders come next. */
    fun useDiscovered(address: String) {
        host = address
        share = ""
        shares = emptyList()
        message = "Нажмите «Подключить» — плеер покажет общие папки. Если сервер просит вход, сначала укажите пользователя и пароль."
    }

    fun chooseShare(name: String) {
        share = name
        connect()
    }

    fun connect() {
        if (connecting) return
        val login = user.trim()
        val domain = login.substringBefore('\\', "")
        val name = login.substringAfter('\\')
        val server = host.trim()
        val folder = share.trim()
        val secret = password
        connecting = true
        if (folder.isEmpty()) {
            message = "Ищу общие папки на $server…"
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { SmbConnection.listShares(server, domain, name, secret) } }
                connecting = false
                result.onSuccess { found ->
                    shares = found
                    message = if (found.isEmpty()) "Сервер не показал общих папок. Введите имя папки вручную." else "Выберите общую папку:"
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    message = SmbConnection.describe(error)
                }
            }
            return
        }
        message = "Подключаюсь к общей папке…"
        val rememberPassword = remember
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val previous = servers.find(server, folder)
                    // An empty password field with a saved one: use the saved password.
                    val pass = if (secret.isEmpty() && previous?.login == login) servers.password(previous) ?: "" else secret
                    SmbConnection.connect(server, folder, domain, name, pass, preferSmb3 = previous?.smb3 == true).also { session ->
                        servers.save(server, folder, login, secret, rememberPassword, session.smb3)
                    }
                }
            }
            connecting = false
            result.onSuccess { session ->
                password = ""
                shares = emptyList()
                showForm = false
                saved = servers.all()
                openFolder(session.rootUrl)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                message = SmbConnection.describe(error)
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
        val cached = cache[url]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second
        job = viewModelScope.launch {
            val listed = if (cached != null) Result.success(cached)
            else withContext(Dispatchers.IO) { runCatching { session.list(url) } }.onSuccess { cache[url] = System.currentTimeMillis() to it }
            loading = false
            listed.onSuccess { found ->
                subtitleUrls = found.filter { it.subtitle }.associate { it.name to it.url }
                entries = found.filter { it.directory || it.video }.map {
                    BrowserEntry(it.url, it.name, if (it.directory) EntryKind.FOLDER else EntryKind.VIDEO, it.size, if (it.directory) null else it.url, it.modified)
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
        val videos = entries.filter { it.kind == EntryKind.VIDEO }
        return PlayRequest.around(
            videos.map { video -> PlayItem(video.playUri!!, Media.title(video.name), Subtitles.matching(video.name, subtitleUrls.keys).mapNotNull(subtitleUrls::get)) },
            videos.indexOf(entry).coerceAtLeast(0),
            folderUrl,
        )
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
    fun refresh() {
        folderUrl?.let { cache.remove(it); openFolder(it) }
    }

    // Discovery: NAS, Samba and macOS announce "_smb._tcp"; Windows usually does not.

    private fun startDiscovery() {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                viewModelScope.launch { resolveQueue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovery = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        if (runCatching { manager.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) discovery = listener
    }

    /** Android before 14 resolves one service at a time. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val manager = nsd ?: return
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                viewModelScope.launch {
                    resolving = false
                    val address = resolved.host?.hostAddress
                    if (address != null && ':' !in address && discovered.none { it.second == address }) {
                        discovered = discovered + (resolved.serviceName to address)
                    }
                    resolveNext()
                }
            }
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                viewModelScope.launch { resolving = false; resolveNext() }
            }
        }
        if (runCatching { manager.resolveService(info, listener) }.isFailure) {
            resolving = false
            resolveNext()
        }
    }

    private companion object {
        const val CACHE_MS = 60_000L
    }

    override fun onCleared() {
        discovery?.let { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        discovery = null
    }
}
