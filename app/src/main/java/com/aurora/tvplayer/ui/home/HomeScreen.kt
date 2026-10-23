package com.aurora.tvplayer.ui.home

import androidx.compose.foundation.Image
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
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.Screen
import com.aurora.tvplayer.ui.UiEvent
import com.aurora.tvplayer.ui.components.AuroraButton
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
    val memory = vm.homeFocus
    val firstSource = remember { FocusRequester() }
    InitialFocus(memory, firstSource)
    LaunchedEffect(Unit) { vm.refreshLastFolder() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "top") {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.aurora_tv_logo), null, Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text("AURORA TV", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2), color = Aurora.cyan)
                Spacer(Modifier.width(28.dp))
                Text("ВАШ КИНОЗАЛ", style = MaterialTheme.typography.labelSmall, color = Aurora.mint.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))
                Clock()
            }
        }
        item(key = "banner") {
            Image(
                painterResource(R.drawable.aurora_tv_banner), "Aurora TV",
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp)),
            )
        }
        item(key = "sources") {
            Column(Modifier.padding(horizontal = 48.dp)) {
                Text("Выберите источник", style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
                Text("USB, папки компьютера и сетевые ссылки", style = MaterialTheme.typography.bodyMedium, color = Aurora.muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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

        val folder = vm.folderName
        if (folder != null) item(key = "folder") {
            Section("Последняя папка · $folder", if (vm.folderVideos.isEmpty()) null else Media.plural(vm.folderVideos.size, "видео", "видео", "видео")) {
                LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    item(key = "open-folder") {
                        AuroraButton("Открыть папку", icon = Icons.Folder, accent = true,
                            onClick = { vm.open(Screen.Files(vm.playback.lastFolder)) },
                            modifier = Modifier.padding(top = 40.dp).remembered(memory, "folder:open"))
                    }
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

        item(key = "tools") {
            Row(Modifier.padding(horizontal = 48.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AuroraButton("Открыть файл", icon = Icons.FileOpen, onClick = { vm.send(UiEvent.PickFile) }, modifier = Modifier.weight(1f).remembered(memory, "tool:file"))
                AuroraButton("Звук, Dolby Vision и форматы", icon = Icons.Volume, onClick = vm::showSoundHelp, modifier = Modifier.weight(1.4f).remembered(memory, "tool:sound"))
                AuroraButton("Мой телевизор и настройки", icon = Icons.Tv, onClick = { vm.open(Screen.Device) }, modifier = Modifier.weight(1.4f).remembered(memory, "tool:device"))
            }
        }
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
    Text(now, style = MaterialTheme.typography.titleMedium, color = Aurora.muted)
}
