package com.aurora.tvplayer

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

internal val uiNavy = Color(0xFF081727)
internal val uiBlue = Color(0xFF1B3857)
internal val uiCyan = Color(0xFF2BE6EF)
internal val uiWhite = Color(0xFFF1F7FC)
internal val uiMuted = Color(0xFFB5CAD8)
internal val uiBackdrop = Brush.verticalGradient(listOf(Color(0xFF071522), Color(0xFF11243B), Color(0xFF1A2E48)))

class HomeUi(activity: Activity, private val actions: Actions) {
    interface Actions {
        fun openUsb()
        fun openNetwork()
        fun openLink()
        fun openVoice()
        fun openFile()
        fun openSoundHelp()
        fun openDevice()
        fun resume()
        fun lastFolder()
        fun playVideo(index: Int)
    }

    val view = ComposeView(activity)
    val dialogs = AuroraDialogs()
    var recentTitle by mutableStateOf<String?>(null)
    var folderTitle by mutableStateOf<String?>(null)
    var deviceTitle by mutableStateOf("Мой телевизор")
    var sectionTitle by mutableStateOf("ВИДЕО В ПОСЛЕДНЕЙ ПАПКЕ")
    var emptyMessage by mutableStateOf("Откройте USB или сетевую папку с видео")
    val videos = mutableStateListOf<String>()

    init { view.setContent { AuroraTheme { Screen() } } }

    @Composable private fun Screen() {
        val firstAction = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstAction.requestFocus() }
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(uiBackdrop),
            contentPadding = PaddingValues(start = 38.dp, end = 38.dp, top = 14.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("A U R O R A   T V", color = uiCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(90.dp))
                    Text("ВАШ КИНОЗАЛ", color = uiCyan, fontSize = 14.sp)
                }
            }
            item {
                Image(
                    painter = painterResource(R.drawable.aurora_tv_banner),
                    contentDescription = "Aurora TV",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(16.dp))
                )
            }
            item {
                Column {
                    Text("Выберите источник", color = uiWhite, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("USB, файлы и сетевые ссылки", color = uiMuted, fontSize = 16.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceCard("▣", "USB / папки", "Встроенный проводник", Modifier.weight(1f).focusRequester(firstAction), actions::openUsb)
                    SourceCard("⌂", "Домашняя сеть", "Папки ПК и NAS · SMB", Modifier.weight(1f), actions::openNetwork)
                    SourceCard("↗", "Ссылка", "HTTP · HLS · DASH", Modifier.weight(1f), actions::openLink)
                    SourceCard("●", "Голос", "RU · УКР · EN", Modifier.weight(1f), actions::openVoice)
                }
            }
            if (recentTitle != null || folderTitle != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    recentTitle?.let { ActionButton("▶   Продолжить: $it", Modifier.weight(1f), action = actions::resume) }
                    folderTitle?.let { ActionButton("▣   Последняя папка: $it", Modifier.weight(1f), action = actions::lastFolder) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("▶   Открыть файл", Modifier.weight(1f), action = actions::openFile)
                    ActionButton("ⓘ   Звук, Dolby Vision и форматы", Modifier.weight(1f), action = actions::openSoundHelp)
                    ActionButton("◉   $deviceTitle", Modifier.weight(1f), action = actions::openDevice)
                }
            }
            item { Text(sectionTitle, color = uiWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            if (videos.isEmpty()) item {
                Text(emptyMessage, color = uiMuted, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(uiBlue).padding(horizontal = 20.dp, vertical = 14.dp))
            }
            itemsIndexed(videos) { index, name ->
                ActionButton("▶   $name", Modifier.fillMaxWidth(), alignStart = true) { actions.playVideo(index) }
            }
        }
        dialogs.Render()
    }
}

@Composable internal fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = uiCyan, onPrimary = uiNavy,
        surface = uiBlue, onSurface = uiWhite)) { content() }
}

@Composable private fun SourceCard(icon: String, title: String, subtitle: String, modifier: Modifier, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.height(94.dp).onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Brush.linearGradient(listOf(uiCyan, uiCyan))
                else Brush.horizontalGradient(listOf(Color(0xFF0E3B57), Color(0xFF12304A))))
            .border(1.dp, if (focused) uiWhite else Color(0xFF2094B5), shape)
            .clickable(onClick = action)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(icon, color = if (focused) uiNavy else uiCyan, fontSize = 19.sp)
        Text(title, color = if (focused) uiNavy else uiWhite, fontSize = 17.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = if (focused) uiNavy else uiMuted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    alignStart: Boolean = false,
    action: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier.height(height).onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (focused) uiCyan else uiBlue)
            .border(1.dp, if (focused) uiWhite else Color(0xFF284966), shape)
            .clickable(onClick = action)
            .padding(horizontal = 14.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    ) {
        Text(label, color = if (focused) uiNavy else uiWhite, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
