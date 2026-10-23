package com.aurora.tvplayer.ui.browser

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.UiEvent
import com.aurora.tvplayer.ui.components.AuroraButton
import com.aurora.tvplayer.ui.components.AuroraChip
import com.aurora.tvplayer.ui.components.AuroraRow
import com.aurora.tvplayer.ui.components.AuroraTextField
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
        },
        loading = browser.loading,
        message = browser.message ?: if (!browser.loading && browser.visible.isEmpty() && !browser.atVolumes) "В этой папке нет видео или подпапок" else null,
        entries = browser.visible,
        playback = vm.playback,
        onOpen = ::openEntry,
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
        },
        form = if (browser.showForm) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuroraTextField(browser.host, { browser.host = it }, "Адрес ПК / NAS (192.168.1.20)", Modifier.weight(1.4f).remembered(browser.focus, "form:host"),
                            icon = Icons.Network, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                        AuroraTextField(browser.share, { browser.share = it }, "Общая папка", Modifier.weight(1f).remembered(browser.focus, "form:share"), icon = Icons.Folder, imeAction = ImeAction.Next)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AuroraTextField(browser.user, { browser.user = it }, "Пользователь (пусто — гость)", Modifier.weight(1f).remembered(browser.focus, "form:user"), imeAction = ImeAction.Next)
                        AuroraTextField(browser.password, { browser.password = it }, "Пароль", Modifier.weight(1f).remembered(browser.focus, "form:password"), secret = true, onSubmit = browser::connect)
                        AuroraButton(if (browser.connecting) "Подключаюсь…" else "Подключить", onClick = browser::connect, icon = Icons.Check, accent = true,
                            modifier = Modifier.remembered(browser.focus, "form:connect"))
                    }
                    Text("Пароль хранится только до закрытия приложения. Нужен SMB 2.x; для домена укажите DOMAIN\\user.",
                        style = MaterialTheme.typography.bodySmall, color = Aurora.faint)
                }
            }
        } else null,
        loading = browser.loading || browser.connecting,
        message = browser.message ?: if (!browser.loading && browser.folderUrl != null && browser.visible.isEmpty()) "В этой папке нет видео или подпапок" else null,
        entries = browser.visible,
        playback = vm.playback,
        onOpen = { entry -> browser.open(entry)?.let { vm.send(UiEvent.Play(it)) } },
    )
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { tools() }
            Spacer(Modifier.height(12.dp))
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
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Aurora.faint, modifier = Modifier.padding(bottom = 4.dp))
        }
        items(entries, key = { it.key }) { entry ->
            val first = entry == entries.first()
            val progress = remember(entry.playUri, revision) { entry.playUri?.let(playback::progress) }
            AuroraRow(
                onClick = { onOpen(entry) },
                dimmed = entry.kind == EntryKind.OTHER,
                modifier = Modifier.remembered(memory, entry.key).then(if (first) Modifier.focusRequester(firstEntry) else Modifier),
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
