package com.aurora.tvplayer.ui.device

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurora.tvplayer.BuildConfig
import com.aurora.tvplayer.data.AmbientLighting
import com.aurora.tvplayer.data.CrashLog
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.LiveLook
import com.aurora.tvplayer.data.DecoderPreference
import com.aurora.tvplayer.data.DecorMode
import com.aurora.tvplayer.data.DevicePower
import com.aurora.tvplayer.data.DeviceReport
import com.aurora.tvplayer.data.FrameRateMode
import com.aurora.tvplayer.data.Occasion
import com.aurora.tvplayer.data.ReportSection
import com.aurora.tvplayer.data.ReportTable
import com.aurora.tvplayer.data.Support
import com.aurora.tvplayer.data.UpdateState
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.Screen
import com.aurora.tvplayer.ui.components.AuroraRow
import com.aurora.tvplayer.ui.components.CardShape
import com.aurora.tvplayer.ui.components.DialogAction
import com.aurora.tvplayer.ui.components.DialogSpec
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
    var ambient by remember { mutableStateOf(AmbientLighting.isEnabled(context)) }
    val allowedDecor = DevicePower.liveLookOn(context, settings.liveLook)
    val memory = remember { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("Мой телевизор", "Настройки", subtitle = "Aurora TV ${BuildConfig.VERSION_NAME}") }
        item {
            Spacer(Modifier.height(8.dp))
            Setting(Icons.Info, "Возможности телевизора", "Экран и HDR, видео- и аудиодекодеры, FFmpeg, цифровой звук",
                value = "Открыть", modifier = Modifier.focusRequester(first).remembered(memory, "caps")) {
                vm.open(Screen.Capabilities)
            }
        }
        item { Label("Просмотр") }
        item {
            Setting(Icons.Play, "Следующая серия автоматически", "После конца файла сразу запускается следующий из папки.",
                toggle = settings.autoplayNext, modifier = Modifier.remembered(memory, "autoplay")) {
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
            Setting(Icons.Forward, "Перемотка вперёд", "Кнопка и стрелка вправо в плеере. Удержание ускоряет, плеер переходит, когда кнопку отпустили.",
                value = "${settings.forwardStepSec} сек", modifier = Modifier.remembered(memory, "step")) {
                val steps = listOf(10, 30, 60)
                vm.settings.setForwardStep(steps[(steps.indexOf(settings.forwardStepSec) + 1) % steps.size])
            }
        }
        item {
            Setting(Icons.Rewind, "Перемотка назад", "Кнопка и стрелка влево в плеере.",
                value = "${settings.backStepSec} сек", modifier = Modifier.remembered(memory, "back-step")) {
                val steps = listOf(5, 10, 15, 30)
                vm.settings.setBackStep(steps[(steps.indexOf(settings.backStepSec) + 1) % steps.size])
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
        item { Label("Обновления") }
        item {
            val update by vm.updater.state.collectAsStateWithLifecycle()
            Setting(Icons.Up, "Проверить обновления", when (val u = update) {
                UpdateState.Checking -> "Проверяю GitHub…"
                UpdateState.UpToDate -> "У вас последняя версия ${BuildConfig.VERSION_NAME}"
                is UpdateState.Available -> "Доступна ${u.release.version}"
                is UpdateState.Downloading -> "Скачиваю ${u.release.version}: ${(u.progress * 100).toInt()}%"
                is UpdateState.Ready -> "${u.release.version} скачана — нажмите, чтобы установить"
                is UpdateState.Installing -> "Устанавливаю ${u.release.version}…"
                is UpdateState.Failed -> u.message
                UpdateState.Idle -> "Сейчас: ${BuildConfig.VERSION_NAME}. Новые версии берутся из GitHub Releases."
            }, value = if (update is UpdateState.Ready) "Обновить" else "Проверить", modifier = Modifier.remembered(memory, "update")) {
                when (update) {
                    is UpdateState.Ready -> vm.installUpdate()
                    is UpdateState.Available -> vm.updater.downloadNow()
                    else -> vm.updater.check(userAsked = true)
                }
            }
        }
        item {
            Setting(Icons.Refresh, "Обновлять автоматически", "Раз в 12 часов проверять и скачивать новую версию. Установка — только по кнопке, не во время просмотра.",
                toggle = settings.autoUpdate, modifier = Modifier.remembered(memory, "autoupdate")) {
                vm.settings.setAutoUpdate(!settings.autoUpdate)
            }
        }
        item { Label("Оформление и данные") }
        item {
            Setting(Icons.Tv, "Живое оформление",
                when (settings.liveLook) {
                    LiveLook.AUTO -> if (DevicePower.isWeak(context)) "Авто: приставка слабая, оставлен простой вид — меню не тормозит"
                        else "Авто: приставка справляется — живой фон «Северное сияние» и сезонное оформление"
                    LiveLook.ON -> "Живой фон «Северное сияние» и сезонное оформление всегда"
                    LiveLook.OFF -> "Простой спокойный вид без анимации"
                },
                value = settings.liveLook.label, modifier = Modifier.remembered(memory, "background")) {
                vm.settings.setLiveLook(LiveLook.entries[(settings.liveLook.ordinal + 1) % LiveLook.entries.size])
            }
        }
        item {
            Setting(Icons.Tv, "Сезонное оформление",
                if (allowedDecor) "Снег, лепестки, светлячки и листопад по сезону, украшения и салют к праздникам за неделю до и после."
                else "Работает, когда включено «Живое оформление».",
                value = settings.decor.label, modifier = Modifier.remembered(memory, "decor")) {
                vm.settings.setDecor(DecorMode.entries[(settings.decor.ordinal + 1) % DecorMode.entries.size])
            }
        }
        if (allowedDecor) item {
            val preview = vm.decorPreview
            Setting(Icons.Tv, "Посмотреть оформление", "Нажимайте, чтобы примерить все сезоны и праздники. После перезапуска — снова по календарю.",
                value = preview?.label ?: "По календарю", modifier = Modifier.remembered(memory, "decor-preview")) {
                val all = Occasion.entries
                vm.decorPreview = if (preview == null) all.first() else all.getOrNull(preview.ordinal + 1)
            }
        }
        item {
            val servers = remember(vm.dialog) { vm.servers.all() }
            Setting(Icons.Network, "Сетевые папки", if (servers.isEmpty()) "Сохранённых подключений нет."
                else Media.plural(servers.size, "подключение", "подключения", "подключений") + " · паролей сохранено: ${servers.count { it.hasPassword }}. Нажмите, чтобы забыть все.",
                modifier = Modifier.remembered(memory, "servers")) {
                if (servers.isEmpty()) return@Setting
                vm.show(DialogSpec.Message("Забыть сетевые папки?", "Адреса и сохранённые пароли будут удалены с этого ТВ. Текущее подключение работает до закрытия приложения.",
                    listOf(DialogAction("Забыть все", Icons.Delete) {
                        servers.forEach(vm.servers::remove)
                        vm.toast("Сетевые папки забыты")
                    })))
            }
        }
        item {
            Setting(Icons.History, "Очистить историю просмотра", "Позиции в файлах сохранятся, исчезнет только ряд «Продолжить».",
                modifier = Modifier.remembered(memory, "history")) {
                vm.playback.clearHistory()
                vm.toast("История очищена")
            }
        }
        if (Support.available) item {
            Setting(Icons.Favorite, "Поддержать автора", "QR-код для благодарности: плеер бесплатный и без рекламы.",
                value = "Открыть", modifier = Modifier.remembered(memory, "support")) { vm.open(Screen.Support) }
        }
        item {
            val crash = remember(vm.dialog) { CrashLog.read(context) }
            Setting(Icons.Bug, "Отчёт о сбое", if (crash == null) "Сбоев не было. Если плеер закроется сам, здесь появится текст для автора."
                else "Плеер закрылся с ошибкой ${crash.lineSequence().firstOrNull().orEmpty()}. Нажмите, чтобы посмотреть.",
                value = if (crash == null) null else "Открыть", modifier = Modifier.remembered(memory, "crash")) {
                if (crash == null) return@Setting
                vm.show(DialogSpec.Message("Отчёт о сбое", crash.take(3_000) + "\n\nСфотографируйте экран и пришлите автору вместе с описанием, что делали.",
                    listOf(DialogAction("Удалить отчёт", Icons.Delete) {
                        CrashLog.clear(context)
                        vm.toast("Отчёт удалён")
                    })))
            }
        }
        item {
            Setting(Icons.Volume, "Звук, HDR и Dolby Vision", "Как настроить оптику, Pass-through и HDR.", modifier = Modifier.remembered(memory, "sound")) {
                vm.showSoundHelp()
            }
        }
    }
}

/**
 * The capability report on its own screen. Every section is a focusable card, so the remote
 * scrolls through the whole report instead of stopping at the last button.
 */
@Composable
fun CapabilitiesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<List<ReportSection>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val memory = remember { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first, trigger = report != null)
    LaunchedEffect(refresh) {
        report = null
        report = withContext(Dispatchers.Default) { DeviceReport.build(context) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.Bottom) {
                ScreenHeader("Мой телевизор", "Возможности телевизора", Modifier.weight(1f), subtitle = "Что заявляет прошивка: экран, декодеры и звук")
                AuroraRowButton("Проверить снова", Icons.Refresh, Modifier.remembered(memory, "refresh")) { refresh++ }
            }
        }
        val sections = report
        if (sections == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Проверяю декодеры и экран…", style = MaterialTheme.typography.bodyLarge, color = Aurora.muted)
                }
            }
        } else {
            items(sections, key = { it.title }, span = { if (it.table != null) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { section ->
                SectionCard(section, Modifier.remembered(memory, section.title).then(if (section == sections.first()) Modifier.focusRequester(first) else Modifier))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Это данные прошивки, а не тест конкретного файла. Для Dolby Vision нужны подходящий файл, системный декодер и поддержка экрана; " +
                        "форматы звука зависят от всей цепочки до ресивера. Что не умеет декодер ТВ, плеер в режиме «Автоматически» отдаёт программному FFmpeg.",
                    style = MaterialTheme.typography.bodySmall, color = Aurora.faint,
                )
            }
        }
    }
}

/** A report section as a card the remote can land on; focus only lifts it with a cyan frame. */
@Composable
private fun SectionCard(section: ReportSection, modifier: Modifier) {
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Aurora.panel.copy(alpha = 0.85f), contentColor = Aurora.white,
            focusedContainerColor = Aurora.panelHigh, focusedContentColor = Aurora.white,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Aurora.line), shape = CardShape),
            focusedBorder = Border(BorderStroke(2.dp, Aurora.cyan), shape = CardShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Aurora.cyan.copy(alpha = 0.35f), 12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall, color = Aurora.cyan)
            Spacer(Modifier.height(8.dp))
            section.table?.let { ReportGrid(it) }
            section.rows.forEach { row ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Fixed shares: a long value can no longer squeeze the label into one letter per line.
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, modifier = Modifier.weight(0.42f), maxLines = 2)
                    Row(Modifier.weight(0.58f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        row.ok?.let { Icon(if (it) Icons.Check else Icons.Close, null, tint = if (it) Aurora.good else Aurora.faint, modifier = Modifier.padding(end = 6.dp).size(16.dp)) }
                        Text(row.value, style = MaterialTheme.typography.bodyMedium, color = if (row.ok == false) Aurora.faint else Aurora.white, textAlign = TextAlign.End, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Resolution × codec table: one column per codec, "60"/"30" in mint, "—" dimmed. */
@Composable
private fun ReportGrid(table: ReportTable) {
    Column {
        Row(Modifier.padding(bottom = 6.dp)) {
            Spacer(Modifier.weight(1.1f))
            table.columns.forEach { name ->
                Text(name, style = MaterialTheme.typography.labelMedium, color = Aurora.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        table.rows.forEachIndexed { index, (label, cells) ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (index % 2 == 0) Aurora.white.copy(alpha = 0.05f) else Aurora.white.copy(alpha = 0f), RoundedCornerShape(8.dp))
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = Aurora.white, modifier = Modifier.weight(1.1f).padding(start = 10.dp), maxLines = 1)
                cells.forEach { cell ->
                    Text(
                        if (cell == "—") cell else "$cell к/с",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (cell == "—") Aurora.faint else Aurora.mint,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                }
            }
        }
        Text("Аппаратный декодер, заявленный прошивкой. «—» — нет аппаратной поддержки (поможет программный FFmpeg, обычно до 1080p).",
            style = MaterialTheme.typography.bodySmall, color = Aurora.faint, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AuroraRowButton(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    com.aurora.tvplayer.ui.components.AuroraButton(text, onClick = onClick, icon = icon, modifier = modifier)
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
