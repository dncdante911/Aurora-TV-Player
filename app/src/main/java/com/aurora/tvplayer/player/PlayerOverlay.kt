package com.aurora.tvplayer.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.ui.components.AuroraButton
import com.aurora.tvplayer.ui.components.AuroraRow
import com.aurora.tvplayer.ui.components.ProgressBar
import com.aurora.tvplayer.ui.components.RoundButton
import com.aurora.tvplayer.ui.components.Spinner
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PanelOption(val label: String, val detail: String? = null, val selected: Boolean = false, val icon: ImageVector? = null, val onSelect: () -> Unit)

/** Side panel of the player: a list of choices or a block of text. */
sealed interface Panel {
    val title: String
    data class Options(override val title: String, val options: List<PanelOption>) : Panel
    data class Info(override val title: String, val text: String) : Panel
}

/** Everything the overlay shows; written by the activity, read by Compose. */
class PlayerUiState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var live by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var panel by mutableStateOf<Panel?>(null)
    var hint by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false)
    var hasPrevious by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var nextTitle by mutableStateOf<String?>(null)
    /** ←/→ are moving a seek target that the player has not jumped to yet. */
    var seeking by mutableStateOf(false)
    /** Shown instead of the error card while a dropped connection is retried. */
    var reconnecting by mutableStateOf<String?>(null)
    var sleepLabel by mutableStateOf<String?>(null)
    var speed by mutableFloatStateOf(1f)
    /** The error is a decoder failure that FFmpeg may handle. */
    var canSoftware by mutableStateOf(false)
    var forwardStepMs by mutableLongStateOf(30_000L)
    var backStepMs by mutableLongStateOf(10_000L)
    /** Bumped when the controls should take focus again (e.g. after OK on a hidden overlay). */
    var focusTick by mutableFloatStateOf(0f)
}

interface PlayerActions {
    fun togglePlay()
    fun seekBy(deltaMs: Long)
    fun previous()
    fun next()
    fun exit()
    fun audio()
    fun subtitles()
    fun settings()
    fun retry()
    fun softwareDecoder()
    fun errorDetails()
    fun restart()
    fun closePanel()
    fun userActivity()
    /** Mouse: a click on the timeline jumps there. */
    fun seekToFraction(fraction: Float) {}
    /** Mouse: a click on the picture shows or hides the controls. */
    fun tapScreen() {}
}

@Composable
fun PlayerOverlay(state: PlayerUiState, actions: PlayerActions) {
    // Buttons consume their clicks; only clicks on the picture itself reach this.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { actions.tapScreen() }) }) {
        val reconnecting = state.reconnecting
        if (reconnecting != null && state.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 64.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    reconnecting, style = MaterialTheme.typography.titleMedium, color = Aurora.white,
                    modifier = Modifier.background(Aurora.navyDeep.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else if (state.buffering && state.error == null) Spinner(Modifier.align(Alignment.Center), size = 64.dp)

        // The side panel takes the remote alone; the bottom controls come back when it closes.
        AnimatedVisibility(state.controls && state.panel == null && state.error == null && !state.ended, enter = fadeIn(), exit = fadeOut()) {
            Controls(state, actions)
        }

        // Quick seek with the controls hidden: a slim timeline and the jump size.
        if (!state.controls && state.hint != null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Aurora.scrimBottom).padding(horizontal = 48.dp, vertical = 28.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(state.hint.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Aurora.cyan)
                    Spacer(Modifier.weight(1f))
                    Text(Media.formatTime(state.position), style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
                    Text(" / ${Media.formatTime(state.duration)}", style = MaterialTheme.typography.titleMedium, color = Aurora.muted)
                }
                Spacer(Modifier.height(10.dp))
                Timeline(state, focused = false)
            }
        } else if (state.hint != null) {
            Text(
                state.hint.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Aurora.navy,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Aurora.cyan, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        val next = state.nextTitle
        if (next != null && state.error == null && state.panel == null) {
            Column(
                // Below the clock, end time and sleep timer while the top bar is shown.
                Modifier.align(Alignment.TopEnd).padding(top = if (state.controls) 112.dp else 32.dp, end = 48.dp).widthIn(max = 380.dp)
                    .background(Aurora.navyDeep.copy(alpha = 0.88f), RoundedCornerShape(16.dp))
                    .border(1.dp, Aurora.cyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text("ДАЛЕЕ", style = MaterialTheme.typography.labelSmall, color = Aurora.cyan)
                Text(next, style = MaterialTheme.typography.titleSmall, color = Aurora.white, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val left = (state.duration - state.position).coerceAtLeast(0)
                Text("Через ${(left + 999) / 1000} с · «Серия ▶▶» — сразу", style = MaterialTheme.typography.bodySmall, color = Aurora.muted)
                Spacer(Modifier.height(8.dp))
                ProgressBar(1f - (left / 20_000f).coerceIn(0f, 1f), Modifier.fillMaxWidth())
            }
        }

        state.error?.let { message -> ErrorCard(message, state, actions, Modifier.align(Alignment.Center)) }
        if (state.ended && state.error == null) EndCard(state, actions, Modifier.align(Alignment.Center))

        AnimatedVisibility(
            state.panel != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            state.panel?.let { SidePanel(it, actions) }
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, actions: PlayerActions) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(state.focusTick) {
        withFrameNanos { }
        if (state.panel == null) runCatching { playFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Aurora.scrimTop).padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, color = Aurora.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, maxLines = 1) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Clock()
                endsAt(state)?.let { Text("закончится в $it", style = MaterialTheme.typography.bodySmall, color = Aurora.muted) }
                state.sleepLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.background(Aurora.violet.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Bedtime, null, Modifier.size(16.dp), tint = Aurora.white)
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = Aurora.white)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Aurora.scrimBottom)
                .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Media.formatTime(state.position), style = MaterialTheme.typography.titleSmall, color = if (state.seeking) Aurora.cyan else Aurora.white)
                Spacer(Modifier.width(16.dp))
                SeekBar(state, actions, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Text(
                    when {
                        state.live -> "LIVE"
                        state.duration > 0 -> "−" + Media.formatTime(state.duration - state.position)
                        else -> "--:--"
                    },
                    style = MaterialTheme.typography.titleSmall, color = Aurora.muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Labeled("Серия") { RoundButton(Icons.SkipPrevious, "Предыдущая серия", actions::previous, enabled = state.hasPrevious) }
                Labeled("−${state.backStepMs / 1000} с") { RoundButton(Icons.Rewind, "Назад", { actions.seekBy(-state.backStepMs) }) }
                Labeled(if (state.playing) "Пауза" else "Играть") {
                    RoundButton(if (state.playing) Icons.Pause else Icons.Play, "Пауза", actions::togglePlay, Modifier.focusRequester(playFocus), size = 68.dp, emphasized = true)
                }
                Labeled("+${state.forwardStepMs / 1000} с") { RoundButton(Icons.Forward, "Вперёд", { actions.seekBy(state.forwardStepMs) }) }
                Labeled("Серия") { RoundButton(Icons.SkipNext, "Следующая серия", actions::next, enabled = state.hasNext) }
                Spacer(Modifier.weight(1f))
                Labeled("Аудио") { RoundButton(Icons.Audio, "Аудио", actions::audio, size = 50.dp) }
                Labeled("Субтитры") { RoundButton(Icons.Subtitles, "Субтитры", actions::subtitles, size = 50.dp) }
                Labeled("Настройки") { RoundButton(Icons.Settings, "Настройки", actions::settings, size = 50.dp) }
                Labeled("К файлам") { RoundButton(Icons.List, "К файлам", actions::exit, size = 50.dp) }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Aurora.muted, maxLines = 1)
    }
}

/** Focusable timeline: ←/→ jump, holding the key speeds the jumps up. */
@Composable
private fun SeekBar(state: PlayerUiState, actions: PlayerActions, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier.height(28.dp)
            .pointerInput(Unit) {
                // Click or drag along the bar: the target moves with the pointer, the player follows when released.
                detectTapGestures(onTap = { actions.seekToFraction(it.x / size.width) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = {}) { change, _ -> actions.seekToFraction(change.position.x / size.width) }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                val boost = 1 + repeat / 4
                when (event.key) {
                    Key.DirectionLeft -> { actions.seekBy(-state.backStepMs * boost); true }
                    Key.DirectionRight -> { actions.seekBy(state.forwardStepMs * boost); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Timeline(state, focused)
    }
}

@Composable
private fun Timeline(state: PlayerUiState, focused: Boolean) {
    val fraction = if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (state.duration > 0) (state.buffered.toFloat() / state.duration).coerceIn(0f, 1f) else 0f
    val height = if (focused) 8.dp else 5.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(bufferedFraction).height(height).background(Color.White.copy(alpha = 0.32f), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(fraction).height(height).background(Aurora.cyan, RoundedCornerShape(4.dp)))
        val thumb = if (focused) 22.dp else 12.dp
        Box(
            Modifier.offset(x = (maxWidth - thumb) * fraction).size(thumb)
                .background(if (focused) Aurora.white else Aurora.cyan, CircleShape)
                .border(if (focused) 3.dp else 0.dp, Aurora.cyan, CircleShape),
        )
    }
}

@Composable
private fun ErrorCard(message: String, state: PlayerUiState, actions: PlayerActions, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(message) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(
        modifier.width(640.dp).background(Aurora.navyDeep.copy(alpha = 0.95f), RoundedCornerShape(20.dp))
            .border(1.dp, Aurora.bad.copy(alpha = 0.6f), RoundedCornerShape(20.dp)).padding(28.dp),
    ) {
        Text("Видео не воспроизводится", style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Aurora.muted)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.canSoftware) {
                AuroraButton("Программный декодер", onClick = actions::softwareDecoder, icon = Icons.Hdr, accent = true, modifier = Modifier.focusRequester(first))
                AuroraButton("Повторить", onClick = actions::retry, icon = Icons.Refresh)
            } else {
                AuroraButton("Повторить", onClick = actions::retry, icon = Icons.Refresh, accent = true, modifier = Modifier.focusRequester(first))
            }
            if (state.hasNext) AuroraButton("Следующий файл", onClick = actions::next, icon = Icons.SkipNext)
            AuroraButton("Подробнее", onClick = actions::errorDetails, icon = Icons.Info)
            AuroraButton("К файлам", onClick = actions::exit, icon = Icons.List)
        }
    }
}

@Composable
private fun EndCard(state: PlayerUiState, actions: PlayerActions, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(modifier.background(Aurora.navyDeep.copy(alpha = 0.92f), RoundedCornerShape(20.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Check, null, tint = Aurora.good, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text("Просмотр завершён", style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
        Text(state.title, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuroraButton("К файлам", onClick = actions::exit, icon = Icons.List, accent = true, modifier = Modifier.focusRequester(first))
            AuroraButton("Смотреть снова", onClick = actions::restart, icon = Icons.Refresh)
        }
    }
}

@Composable
private fun SidePanel(panel: Panel, actions: PlayerActions) {
    val first = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxHeight().width(440.dp)
            .background(Aurora.navyDeep.copy(alpha = 0.95f))
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(panel.title, style = MaterialTheme.typography.headlineSmall, color = Aurora.white)
        Spacer(Modifier.height(16.dp))
        when (panel) {
            is Panel.Options -> {
                val start = panel.options.indexOfFirst { it.selected }.coerceAtLeast(0)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(panel.options) { index, option ->
                        AuroraRow(onClick = option.onSelect, modifier = if (index == start) Modifier.focusRequester(first) else Modifier) {
                            option.icon?.let { Icon(it, null, Modifier.size(22.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                option.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                            if (option.selected) Icon(Icons.Check, "Выбрано", Modifier.size(22.dp))
                        }
                    }
                }
            }
            is Panel.Info -> {
                Text(panel.text, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                Spacer(Modifier.height(16.dp))
                AuroraButton("Закрыть", onClick = actions::closePanel, modifier = Modifier.focusRequester(first))
            }
        }
    }
}

/** "23:41" when a film of known length plays; accounts for the playback speed. */
private fun endsAt(state: PlayerUiState): String? {
    if (state.live || state.duration <= 0 || state.speed <= 0f) return null
    val left = ((state.duration - state.position).coerceAtLeast(0) / state.speed).toLong()
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + left))
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
