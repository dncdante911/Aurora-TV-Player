package com.aurora.tvplayer.ui.browser

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.PlaybackStore
import com.aurora.tvplayer.net.SmbConnection
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.UiEvent
import com.aurora.tvplayer.ui.components.AuroraButton
import com.aurora.tvplayer.ui.components.AuroraChip
import com.aurora.tvplayer.ui.components.AuroraRow
import com.aurora.tvplayer.ui.components.AuroraTextField
import com.aurora.tvplayer.ui.components.DialogAction
import com.aurora.tvplayer.ui.components.DialogSpec
import com.aurora.tvplayer.ui.components.FocusMemory
import com.aurora.tvplayer.ui.components.InitialFocus
import com.aurora.tvplayer.ui.components.ProgressBar
import com.aurora.tvplayer.ui.components.ScreenHeader
import com.aurora.tvplayer.ui.components.Spinner
import com.aurora.tvplayer.ui.components.remembered
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons
import java.io.File

@Composable
fun FilesScreen(vm: MainViewModel, initialFolder: String?) {
    val browser: LocalBrowserViewModel = viewModel()
    val firstTool = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialFolder?.let(browser::restore)
        browser.pendingVolume?.let { vm.send(UiEvent.RequestStorageAccess(it)) }
    }

    fun openEntry(entry: BrowserEntry) {
        browser.open(entry)?.let { vm.send(UiEvent.Play(it)) }
        if (entry.kind == EntryKind.VOLUME) browser.pendingVolume?.let { vm.send(UiEvent.RequestStorageAccess(it)) }
    }

    BackHandler { if (!browser.up()) vm.back() }

    fun guarded(action: () -> Unit): () -> Unit = {
        when {
            browser.canWrite() -> action()
            Build.VERSION.SDK_INT >= 30 -> browser.folder?.let { vm.send(UiEvent.RequestStorageAccess(it)) }
            else -> vm.send(UiEvent.RequestWriteAccess)
        }
    }

    BrowserLayout(
        section = "Файлы",
        title = browser.title,
        subtitle = browser.folder?.absolutePath?.let { path ->
            browser.root?.let { base -> (listOf(browser.rootName) + path.removePrefix(base.absolutePath).split(File.separatorChar).filter { it.isNotEmpty() }).joinToString("  /  ") }
        },
        memory = browser.focus,
        firstTool = firstTool,
        reloadKey = browser.folder?.absolutePath ?: "volumes",
        tools = {
            AuroraChip("Диски", onClick = browser::showVolumes, icon = Icons.Storage, modifier = Modifier.focusRequester(firstTool).remembered(browser.focus, "tool:disks"))
            AuroraChip("Назад", onClick = { if (!browser.up()) vm.back() }, icon = Icons.Up, modifier = Modifier.remembered(browser.focus, "tool:up"))
            AuroraChip(if (browser.showAll) "Только видео" else "Все файлы", onClick = browser::toggleShowAll, icon = Icons.Eye, selected = browser.showAll, modifier = Modifier.remembered(browser.focus, "tool:all"))
            AuroraChip(if (browser.query.isEmpty()) "Поиск" else "«${browser.query}»", selected = browser.query.isNotEmpty(), icon = Icons.Search,
                modifier = Modifier.remembered(browser.focus, "tool:search"),
                onClick = { vm.show(DialogSpec.Input("Поиск в текущей папке", "Название файла или папки", browser.query, "Найти", browser::search)) })
            AuroraChip(browser.sort.mode.label, onClick = browser.sort::next, icon = Icons.Sort, modifier = Modifier.remembered(browser.focus, "tool:sort"))
            browser.folder?.let { dir ->
                AuroraChip("Новая папка", icon = Icons.CreateFolder, modifier = Modifier.remembered(browser.focus, "tool:mkdir"),
                    onClick = guarded { askNewFolder(vm) { name -> browser.files.createFolder(dir.absolutePath, name, vm::toast) } })
            }
        },
        banner = { FileBanner(browser.files, browser.folder?.absolutePath, browser.focus, vm::toast) },
        loading = browser.loading,
        message = browser.message ?: if (!browser.loading && browser.visible.isEmpty() && !browser.atVolumes) "В этой папке нет видео или подпапок" else null,
        entries = browser.visible,
        playback = vm.playback,
        onOpen = ::openEntry,
        onMenu = { entry ->
            if (entry.kind != EntryKind.VOLUME) fileMenu(vm, entry, browser.files, browser.companionsOf(entry), { openEntry(entry) }, ::guarded)
        },
    )
}

@Composable
fun NetworkScreen(vm: MainViewModel) {
    val browser: NetworkBrowserViewModel = viewModel()
    val firstTool = remember { FocusRequester() }
    BackHandler { if (!browser.up()) vm.back() }

    BrowserLayout(
        section = "Домашняя сеть",
        title = browser.title,
        subtitle = browser.folderUrl?.removePrefix("smb://")?.let(android.net.Uri::decode),
        memory = browser.focus,
        firstTool = firstTool,
        reloadKey = browser.folderUrl ?: "form",
        tools = {
            AuroraChip("Назад", onClick = { if (!browser.up()) vm.back() }, icon = Icons.Up, modifier = Modifier.focusRequester(firstTool).remembered(browser.focus, "tool:up"))
            AuroraChip("Подключение", onClick = { browser.showForm = !browser.showForm }, icon = Icons.Network, selected = browser.showForm, modifier = Modifier.remembered(browser.focus, "tool:form"))
            AuroraChip(if (browser.query.isEmpty()) "Поиск" else "«${browser.query}»", selected = browser.query.isNotEmpty(), icon = Icons.Search,
                modifier = Modifier.remembered(browser.focus, "tool:search"),
                onClick = { vm.show(DialogSpec.Input("Поиск в папке", "Название файла или папки", browser.query, "Найти", browser::search)) })
            AuroraChip("Обновить", onClick = browser::refresh, icon = Icons.Refresh, modifier = Modifier.remembered(browser.focus, "tool:refresh"))
            if (browser.folderUrl != null) {
                AuroraChip(browser.sort.mode.label, onClick = browser.sort::next, icon = Icons.Sort, modifier = Modifier.remembered(browser.focus, "tool:sort"))
                AuroraChip("Новая папка", icon = Icons.CreateFolder, modifier = Modifier.remembered(browser.focus, "tool:mkdir"), onClick = {
                    browser.folderUrl?.let { url -> askNewFolder(vm) { name -> browser.files.createFolder(url, name, vm::toast) } }
                })
            }
        },
        banner = { FileBanner(browser.files, browser.folderUrl, browser.focus, vm::toast) },
        form = if (browser.showForm) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (browser.saved.isNotEmpty()) ChipLine("Сохранённые") {
                        items(browser.saved, key = { "saved:" + it.key }) { server ->
                            AuroraChip(server.label, onClick = { browser.useServer(server) }, icon = if (server.hasPassword) Icons.Key else Icons.Network,
                                selected = SmbConnection.active?.let { it.host.equals(server.host, true) && it.share.equals(server.share, true) } == true,
                                modifier = Modifier.remembered(browser.focus, "saved:" + server.key))
                        }
                    }
                    if (browser.discovered.isNotEmpty()) ChipLine("Найдены в сети") {
                        items(browser.discovered, key = { "found:" + it.second }) { (name, address) ->
                            AuroraChip("$name · $address", onClick = { browser.useDiscovered(address) }, icon = Icons.Tv,
                                selected = browser.host == address, modifier = Modifier.remembered(browser.focus, "found:$address"))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuroraTextField(browser.host, { browser.host = it }, "Адрес ПК / NAS (192.168.1.20)", Modifier.weight(1.4f).remembered(browser.focus, "form:host"),
                            icon = Icons.Network, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                        AuroraTextField(browser.share, { browser.share = it }, "Общая папка (или пусто)", Modifier.weight(1f).remembered(browser.focus, "form:share"), icon = Icons.Folder, imeAction = ImeAction.Next)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AuroraTextField(browser.user, { browser.user = it }, "Пользователь (пусто — гость)", Modifier.weight(1f).remembered(browser.focus, "form:user"), imeAction = ImeAction.Next)
                        AuroraTextField(browser.password, { browser.password = it }, "Пароль", Modifier.weight(1f).remembered(browser.focus, "form:password"), secret = true, onSubmit = browser::connect)
                        AuroraChip("Запомнить", onClick = browser::toggleRemember, icon = Icons.Key, selected = browser.remember,
                            modifier = Modifier.remembered(browser.focus, "form:remember"))
                        AuroraButton(if (browser.connecting) "Подключаюсь…" else "Подключить", onClick = browser::connect, icon = Icons.Check, accent = true,
                            modifier = Modifier.remembered(browser.focus, "form:connect"))
                    }
                    if (browser.shares.isNotEmpty()) ChipLine("Папки на сервере") {
                        items(browser.shares, key = { "share:$it" }) { name ->
                            AuroraChip(name, onClick = { browser.chooseShare(name) }, icon = Icons.Folder, modifier = Modifier.remembered(browser.focus, "share:$name"))
                        }
                    }
                    Text(
                        "Не знаете имя папки — оставьте поле пустым, плеер покажет все папки сервера. " +
                            if (browser.remember) "Пароль хранится зашифрованным только на этом ТВ — после перезапуска папка откроется сама. SMB 2 или 3; для домена укажите DOMAIN\\user."
                        else "Пароль не сохраняется: после закрытия приложения его нужно ввести снова. SMB 2 или 3; для домена укажите DOMAIN\\user.",
                        style = MaterialTheme.typography.bodySmall, color = Aurora.faint,
                    )
                }
            }
        } else null,
        loading = browser.loading || browser.connecting,
        message = browser.message ?: if (!browser.loading && browser.folderUrl != null && browser.visible.isEmpty()) "В этой папке нет видео или подпапок" else null,
        entries = browser.visible,
        playback = vm.playback,
        onOpen = { entry -> browser.open(entry)?.let { vm.send(UiEvent.Play(it)) } },
        onMenu = { entry ->
            fileMenu(vm, entry, browser.files, browser.companionsOf(entry), { browser.open(entry)?.let { vm.send(UiEvent.Play(it)) } }, { action -> action })
        },
    )
}

/** "Перемещение: «Фильм»" with «Вставить сюда», or the progress of a running operation. */
@Composable
private fun FileBanner(files: FileActions, folder: String?, memory: FocusMemory, report: (String) -> Unit) {
    val busy = files.busy
    val moving = files.clipboard
    if (busy == null && moving == null) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(Aurora.violet.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .border(1.dp, Aurora.violet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy != null) {
            Spinner(size = 26.dp)
            Text(busy, style = MaterialTheme.typography.titleSmall, color = Aurora.white, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            AuroraChip("Остановить", onClick = files::cancel, icon = Icons.Close, modifier = Modifier.remembered(memory, "banner:stop"))
        } else if (moving != null) {
            Icon(Icons.Folder, null, Modifier.size(24.dp), tint = Aurora.white)
            Column(Modifier.weight(1f)) {
                Text("Перемещение: «${moving.name}»", style = MaterialTheme.typography.titleSmall, color = Aurora.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Откройте папку, куда перенести, и нажмите «Вставить сюда»", style = MaterialTheme.typography.bodySmall, color = Aurora.muted)
            }
            if (folder != null) AuroraChip("Вставить сюда", onClick = { files.paste(folder, report) }, icon = Icons.Check, selected = true,
                modifier = Modifier.remembered(memory, "banner:paste"))
            AuroraChip("Отменить", onClick = files::cancelMove, icon = Icons.Close, modifier = Modifier.remembered(memory, "banner:cancel"))
        }
    }
}

private fun askNewFolder(vm: MainViewModel, create: (String) -> Unit) =
    vm.show(DialogSpec.Input("Новая папка", "Название папки", "", "Создать") { raw ->
        val name = raw.trim()
        FileStore.problem(name)?.let(vm::toast) ?: create(name)
    })

/**
 * Actions for one row. [guard] wraps changing actions: on Android 9–10 it first asks for the
 * write permission. Delete asks again; rename keeps the extension unless the user types one.
 */
private fun fileMenu(
    vm: MainViewModel,
    entry: BrowserEntry,
    files: FileActions,
    companions: List<String>,
    open: () -> Unit,
    guard: (() -> Unit) -> () -> Unit,
) {
    val video = entry.kind == EntryKind.VIDEO
    val progress = entry.playUri?.let(vm.playback::progress)
    val actions = buildList<Pair<String, () -> Unit>> {
        if (entry.kind != EntryKind.OTHER) add((if (video) "Смотреть" else "Открыть") to open)
        add("Переименовать" to guard {
            val extension = if (entry.kind == EntryKind.FOLDER) "" else entry.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            vm.show(DialogSpec.Input("Переименовать", "Новое имя", entry.name.removeSuffix(extension), "Сохранить") { raw ->
                val typed = raw.trim()
                val name = if (extension.isNotEmpty() && !typed.endsWith(extension, ignoreCase = true)) typed + extension else typed
                when {
                    name == entry.name -> Unit
                    FileStore.problem(name) != null -> vm.toast(FileStore.problem(name)!!)
                    else -> files.rename(entry, name, companions, vm::toast)
                }
            })
        })
        add("Переместить в другую папку" to guard {
            files.cut(entry, companions)
            vm.toast("Откройте нужную папку и нажмите «Вставить сюда»")
        })
        add("Удалить" to guard {
            val what = when {
                entry.kind == EntryKind.FOLDER -> "Папка будет удалена со всем содержимым."
                companions.isNotEmpty() -> "Вместе с ним удалятся субтитры: ${companions.joinToString()}."
                else -> "Файл будет удалён."
            }
            vm.show(DialogSpec.Message("Удалить «${entry.name}»?", "$what Корзины нет — восстановить будет нельзя.",
                listOf(DialogAction("Удалить", Icons.Delete) { files.delete(entry, companions, vm::toast) }), dismissLabel = "Отмена"))
        })
        if (video && progress != null) {
            if (progress.watched || progress.started) add("Сбросить просмотр" to { vm.playback.forget(entry.playUri!!); vm.toast("Отметка снята") })
            if (!progress.watched) add("Отметить просмотренным" to { vm.playback.markWatched(entry.playUri!!); vm.toast("Отмечено как просмотренное") })
        }
    }
    vm.show(DialogSpec.Choice(entry.name, actions.map { it.first }, text = listOfNotNull(
        Media.sizeLabel(entry.size).ifEmpty { null },
        if (companions.isNotEmpty()) Media.plural(companions.size, "файл субтитров", "файла субтитров", "файлов субтитров") else null,
    ).joinToString(" · ").ifEmpty { null }) { index -> actions[index].second() })
}

/** A caption and a horizontally scrolling line of chips; the remote moves along it with ←/→. */
@Composable
private fun ChipLine(label: String, content: LazyListScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Aurora.muted, modifier = Modifier.width(150.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun BrowserLayout(
    section: String,
    title: String,
    subtitle: String?,
    memory: FocusMemory,
    firstTool: FocusRequester,
    reloadKey: String,
    tools: @Composable () -> Unit,
    loading: Boolean,
    message: String?,
    entries: List<BrowserEntry>,
    playback: PlaybackStore,
    onOpen: (BrowserEntry) -> Unit,
    form: (@Composable () -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
    /** Long OK or the remote's Menu key on a row: rename, move, delete. */
    onMenu: ((BrowserEntry) -> Unit)? = null,
) {
    val revision by playback.revision.collectAsStateWithLifecycle()
    // When a folder finishes loading, focus its first entry (or the one we came back to).
    val firstEntry = remember { FocusRequester() }
    InitialFocus(memory, if (entries.isNotEmpty()) firstEntry else firstTool, trigger = "$reloadKey:${entries.isNotEmpty()}")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            ScreenHeader(section, title, subtitle = subtitle)
            Spacer(Modifier.height(16.dp))
            // More chips than fit: the row scrolls as focus moves along it.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) { tools() }
            Spacer(Modifier.height(12.dp))
            banner?.invoke()
        }
        form?.let { item(key = "form") { it(); Spacer(Modifier.height(10.dp)) } }
        if (loading) item(key = "loading") {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 34.dp)
                Spacer(Modifier.width(14.dp))
                Text("Читаю папку…", style = MaterialTheme.typography.bodyLarge, color = Aurora.muted)
            }
        }
        message?.let { item(key = "message") { Text(it, style = MaterialTheme.typography.bodyLarge, color = Aurora.muted, modifier = Modifier.padding(vertical = 8.dp)) } }
        if (entries.isNotEmpty()) item(key = "count") {
            val videos = entries.count { it.kind == EntryKind.VIDEO }
            val folders = entries.count { it.kind == EntryKind.FOLDER || it.kind == EntryKind.VOLUME }
            Text(listOfNotNull(
                if (folders > 0) Media.plural(folders, "папка", "папки", "папок") else null,
                if (videos > 0) Media.plural(videos, "видео", "видео", "видео") else null,
            ).joinToString(" · ") + if (onMenu != null) "  ·  удерживайте OK — переименовать, переместить, удалить" else "",
                style = MaterialTheme.typography.bodySmall, color = Aurora.faint, modifier = Modifier.padding(bottom = 4.dp))
        }
        items(entries, key = { it.key }) { entry ->
            val first = entry == entries.first()
            val progress = remember(entry.playUri, revision) { entry.playUri?.let(playback::progress) }
            AuroraRow(
                onClick = { onOpen(entry) },
                onLongClick = onMenu?.let { menu -> { menu(entry) } },
                dimmed = entry.kind == EntryKind.OTHER,
                modifier = Modifier.remembered(memory, entry.key).then(if (first) Modifier.focusRequester(firstEntry) else Modifier)
                    .onPreviewKeyEvent { event ->
                        val menu = onMenu
                        if (menu != null && event.key == Key.Menu && event.type == KeyEventType.KeyUp) { menu(entry); true } else false
                    },
            ) {
                Icon(
                    when (entry.kind) {
                        EntryKind.VOLUME -> Icons.Usb
                        EntryKind.FOLDER -> Icons.Folder
                        EntryKind.VIDEO -> Icons.Movie
                        EntryKind.OTHER -> Icons.File
                    },
                    null, Modifier.size(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress != null && progress.started && progress.durationMs > 0) {
                        Spacer(Modifier.height(5.dp))
                        ProgressBar(progress.fraction, Modifier.fillMaxWidth(0.5f))
                    }
                }
                when {
                    progress?.watched == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Просмотрено", style = MaterialTheme.typography.bodySmall)
                    }
                    progress != null && progress.started && progress.durationMs > 0 ->
                        Text(Media.remaining(progress.durationMs - progress.positionMs), style = MaterialTheme.typography.bodySmall)
                }
                if (entry.size > 0) Text(Media.sizeLabel(entry.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
