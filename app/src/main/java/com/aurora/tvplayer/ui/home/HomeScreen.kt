package com.aurora.tvplayer.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurora.tvplayer.R
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.Support
import com.aurora.tvplayer.data.UpdateState
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.Screen
import com.aurora.tvplayer.ui.UiEvent
import com.aurora.tvplayer.ui.components.InitialFocus
import com.aurora.tvplayer.ui.components.SourceCard
import com.aurora.tvplayer.ui.components.VideoCard
import com.aurora.tvplayer.ui.components.remembered
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RowPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp)

@Composable
fun HomeScreen(vm: MainViewModel) {
    val history by vm.playback.history.collectAsStateWithLifecycle()
    val revision by vm.playback.revision.collectAsStateWithLifecycle()
    val update by vm.updater.state.collectAsStateWithLifecycle()
    val memory = vm.homeFocus
    val firstSource = remember { FocusRequester() }
    InitialFocus(memory, firstSource)
    LaunchedEffect(Unit) {
        vm.refreshLastFolder()
        vm.maybeRemindSupport()
        vm.updater.checkIfDue()
    }

    Column(Modifier.fillMaxSize()) {
        // The banner is the fixed header, as in 2.x: outside the scrolling list, so focusing a card
        // can never slide it under anything or off the screen.
        Header()
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Ready || update is UpdateState.Installing) item(key = "update") {
                UpdateBanner(update, onDownload = vm.updater::downloadNow, onInstall = vm::installUpdate,
                    modifier = Modifier.padding(horizontal = 48.dp).remembered(memory, "update"))
            }
            item(key = "sources") {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    Text("Выберите источник", style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
                    Text("USB, папки компьютера и сетевые ссылки", style = MaterialTheme.typography.bodyMedium, color = Aurora.muted)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SourceCard(Icons.Usb, "USB / папки", "Встроенный проводник", { vm.open(Screen.Files()) },
                            Modifier.weight(1f).focusRequester(firstSource).remembered(memory, "src:usb"))
                        SourceCard(Icons.Network, "Домашняя сеть", "Папки ПК и NAS · SMB", { vm.open(Screen.Network) },
                            Modifier.weight(1f).remembered(memory, "src:smb"))
                        SourceCard(Icons.Link, "Ссылка", "HTTP · HLS · DASH", vm::askLink, Modifier.weight(1f).remembered(memory, "src:link"))
                        SourceCard(Icons.Mic, "Голос", "RU · УКР · EN", vm::chooseVoiceLanguage, Modifier.weight(1f).remembered(memory, "src:voice"))
                    }
                }
            }

            if (history.isNotEmpty()) item(key = "continue") {
                Section("Продолжить просмотр", Media.plural(history.size, "видео", "видео", "видео")) {
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(history.take(15), key = { it.uri }) { entry ->
                            val progress = remember(entry.uri, revision) { vm.playback.progress(entry.uri) }
                            VideoCard(
                                title = entry.title,
                                subtitle = when {
                                    progress.watched -> "Просмотрено"
                                    progress.started && progress.durationMs > 0 -> Media.remaining(progress.durationMs - progress.positionMs)
                                    else -> sourceLabel(entry.uri)
                                },
                                progress = progress.takeIf { it.started && it.durationMs > 0 }?.fraction,
                                watched = progress.watched,
                                onClick = { vm.resume(entry) },
                                onLongClick = { vm.historyActions(entry) },
                                modifier = Modifier.remembered(memory, "history:${entry.uri}"),
                            )
                        }
                    }
                }
            }

            if (vm.folderVideos.isNotEmpty()) item(key = "folder") {
                Section("Последняя папка · ${vm.folderName.orEmpty()}", Media.plural(vm.folderVideos.size, "видео", "видео", "видео")) {
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(vm.folderVideos, key = { _, video -> video.uri }) { index, video ->
                            val progress = remember(video.uri, revision) { vm.playback.progress(video.uri) }
                            VideoCard(
                                title = video.title,
                                subtitle = when {
                                    progress.watched -> "Просмотрено"
                                    progress.started && progress.durationMs > 0 -> Media.remaining(progress.durationMs - progress.positionMs)
                                    else -> "${index + 1} из ${vm.folderVideos.size}"
                                },
                                progress = progress.takeIf { it.started && it.durationMs > 0 }?.fraction,
                                watched = progress.watched,
                                width = 210.dp,
                                onClick = { vm.playFolderVideo(index) },
                                modifier = Modifier.remembered(memory, "folder:${video.uri}"),
                            )
                        }
                    }
                }
            }

            item(key = "more") {
                Section("Ещё", null) {
                    Row(Modifier.padding(RowPadding), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        vm.folderName?.let { name ->
                            SourceCard(Icons.Folder, "Последняя папка", name, { vm.open(Screen.Files(vm.playback.lastFolder)) },
                                Modifier.weight(1f).remembered(memory, "more:folder"), compact = true)
                        }
                        SourceCard(Icons.FileOpen, "Открыть файл", "Системный выбор файла", { vm.send(UiEvent.PickFile) },
                            Modifier.weight(1f).remembered(memory, "more:file"), compact = true)
                        SourceCard(Icons.Volume, "Звук и HDR", "Dolby Vision, DTS, оптика", vm::showSoundHelp,
                            Modifier.weight(1f).remembered(memory, "more:sound"), compact = true)
                        SourceCard(Icons.Tv, "Мой телевизор", "Настройки и возможности", { vm.open(Screen.Device) },
                            Modifier.weight(1f).remembered(memory, "more:device"), compact = true)
                        if (Support.available) {
                            SourceCard(Icons.Favorite, "Поддержать", "Спасибо автору", { vm.open(Screen.Support) },
                                Modifier.weight(1f).remembered(memory, "more:support"), compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Box(
        Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 6.dp)
            .fillMaxWidth().height(112.dp).clip(RoundedCornerShape(18.dp)),
    ) {
        Image(painterResource(R.drawable.aurora_tv_banner), "Aurora TV", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopEnd).padding(12.dp)
                .background(Aurora.navyDeep.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) { Clock() }
    }
}

@Composable
private fun Section(title: String, detail: String?, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleMedium, color = Aurora.white)
            detail?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Aurora.muted)
            }
        }
        content()
    }
}

private fun sourceLabel(uri: String) = when {
    uri.startsWith("smb://") -> "Домашняя сеть"
    uri.startsWith("http") -> "Сетевая ссылка"
    uri.startsWith("file:") -> "USB / память ТВ"
    else -> "Файл"
}

@Composable
private fun Clock() {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = format.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(now, style = MaterialTheme.typography.titleMedium, color = Aurora.white)
}
