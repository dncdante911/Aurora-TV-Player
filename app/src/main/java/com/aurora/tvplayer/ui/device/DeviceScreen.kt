package com.aurora.tvplayer.ui.device

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurora.tvplayer.BuildConfig
import com.aurora.tvplayer.data.AmbientLighting
import com.aurora.tvplayer.data.DecoderPreference
import com.aurora.tvplayer.data.FrameRateMode
import com.aurora.tvplayer.data.DeviceReport
import com.aurora.tvplayer.data.ReportSection
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.components.AuroraRow
import com.aurora.tvplayer.ui.components.FocusMemory
import com.aurora.tvplayer.ui.components.InitialFocus
import com.aurora.tvplayer.ui.components.ScreenHeader
import com.aurora.tvplayer.ui.components.Spinner
import com.aurora.tvplayer.ui.components.remembered
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DeviceScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf<List<ReportSection>?>(null) }
    var ambient by remember { mutableStateOf(AmbientLighting.isEnabled(context)) }
    val memory = remember { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first)
    LaunchedEffect(Unit) { report = withContext(Dispatchers.Default) { DeviceReport.build(context) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("Мой телевизор", "Настройки и возможности", subtitle = "Aurora TV ${BuildConfig.VERSION_NAME}") }
        item { Label("Просмотр") }
        item {
            Setting(Icons.Play, "Следующая серия автоматически", "После конца файла сразу запускается следующий из папки.",
                toggle = settings.autoplayNext, modifier = Modifier.focusRequester(first).remembered(memory, "autoplay")) {
                vm.settings.setAutoplayNext(!settings.autoplayNext)
            }
        }
        item {
            Setting(Icons.Tv, "Частота кадров экрана", settings.frameRate.detail, value = settings.frameRate.label,
                modifier = Modifier.remembered(memory, "fps")) {
                val all = FrameRateMode.entries
                vm.settings.setFrameRate(all[(all.indexOf(settings.frameRate) + 1) % all.size])
            }
        }
        item {
            Setting(Icons.Hdr, "Декодеры", settings.decoder.detail, value = settings.decoder.label,
                modifier = Modifier.remembered(memory, "decoder")) {
                val all = DecoderPreference.entries
                vm.settings.setDecoder(all[(all.indexOf(settings.decoder) + 1) % all.size])
            }
        }
        item {
            Setting(Icons.Forward, "Перемотка вперёд", "Кнопка и стрелка вправо в плеере. Назад всегда 10 секунд.",
                value = "${settings.forwardStepSec} сек", modifier = Modifier.remembered(memory, "step")) {
                val steps = listOf(10, 30, 60)
                vm.settings.setForwardStep(steps[(steps.indexOf(settings.forwardStepSec) + 1) % steps.size])
            }
        }
        item {
            Setting(Icons.Lightbulb, "Подсветка Ambilight", when (ambient) {
                null -> "Системный переключатель недоступен: подсветкой управляет сам ТВ (нужен Android TV 16 с поддержкой производителя)."
                else -> "Системная подсветка Android TV 16. Цвета рассчитывает прошивка телевизора."
            }, toggle = ambient, modifier = Modifier.remembered(memory, "ambient")) {
                val current = ambient ?: return@Setting
                if (AmbientLighting.setEnabled(context, !current)) ambient = !current
                else vm.toast("Телевизор не подтвердил изменение подсветки")
            }
        }
        item { Label("Оформление") }
        item {
            Setting(Icons.Tv, "Живой фон «Северное сияние»", "Плавные переливы на фоне меню. Выключите на очень слабых приставках.",
                toggle = settings.animatedBackground, modifier = Modifier.remembered(memory, "background")) {
                vm.settings.setAnimatedBackground(!settings.animatedBackground)
            }
        }
        item {
            Setting(Icons.History, "Очистить историю просмотра", "Позиции в файлах сохранятся, исчезнет только ряд «Продолжить».",
                modifier = Modifier.remembered(memory, "history")) {
                vm.playback.clearHistory()
                vm.toast("История очищена")
            }
        }
        item {
            Setting(Icons.Volume, "Звук, HDR и Dolby Vision", "Как настроить оптику, Pass-through и HDR.", modifier = Modifier.remembered(memory, "sound")) {
                vm.showSoundHelp()
            }
        }
        item { Label("Возможности телевизора") }
        val sections = report
        if (sections == null) item {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 30.dp)
                Spacer(Modifier.width(14.dp))
                Text("Проверяю декодеры и экран…", style = MaterialTheme.typography.bodyLarge, color = Aurora.muted)
            }
        } else sections.forEach { section ->
            item(key = section.title) {
                Column(Modifier.fillMaxWidth().background(Aurora.panel.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(18.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleSmall, color = Aurora.cyan)
                    Spacer(Modifier.height(6.dp))
                    section.rows.forEach { row ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, modifier = Modifier.weight(1f))
                            row.ok?.let { Icon(if (it) Icons.Check else Icons.Close, null, tint = if (it) Aurora.good else Aurora.faint, modifier = Modifier.padding(end = 6.dp).size(16.dp)) }
                            Text(row.value, style = MaterialTheme.typography.bodyMedium, color = if (row.ok == false) Aurora.faint else Aurora.white)
                        }
                    }
                }
            }
        }
        item {
            Text("Это данные прошивки, а не тест конкретного файла. Для Dolby Vision нужны подходящий файл, системный декодер и поддержка экрана; " +
                "форматы звука зависят от всей цепочки до ресивера.", style = MaterialTheme.typography.bodySmall, color = Aurora.faint, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Aurora.cyan, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Setting(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    toggle: Boolean? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    AuroraRow(onClick = onClick, modifier = modifier) {
        Icon(icon, null, Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        when {
            toggle != null -> Text(if (toggle) "Вкл" else "Выкл", style = MaterialTheme.typography.labelLarge,
                color = if (toggle) Aurora.navy else Aurora.muted,
                modifier = Modifier.background(if (toggle) Aurora.mint else Aurora.panelHigh, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 5.dp))
            value != null -> Text(value, style = MaterialTheme.typography.labelLarge)
        }
    }
}
