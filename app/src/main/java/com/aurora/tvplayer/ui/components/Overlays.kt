package com.aurora.tvplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurora.tvplayer.data.ReportSection
import com.aurora.tvplayer.ui.decor.LocalOccasion
import com.aurora.tvplayer.ui.decor.cardDecor
import com.aurora.tvplayer.ui.theme.Aurora
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.launch

/** 16:9 card for a video: placeholder art, progress, title. */
@Composable
fun VideoCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 236.dp,
    progress: Float? = null,
    watched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(width),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Aurora.panel, contentColor = Aurora.white,
            focusedContainerColor = Aurora.cyan, focusedContentColor = Aurora.navy,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Aurora.line), shape = CardShape),
            focusedBorder = Border(BorderStroke(2.dp, Aurora.white), shape = CardShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Aurora.cyan.copy(alpha = 0.55f), 16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Aurora.thumb(title))
                    .cardDecor(LocalOccasion.current, title, mirror = true),
            ) {
                Icon(Icons.Movie, null, tint = Aurora.white.copy(alpha = 0.22f), modifier = Modifier.align(Alignment.Center).size(48.dp))
                if (watched) Badge("Просмотрено", Modifier.align(Alignment.TopEnd).padding(8.dp), color = Aurora.navy.copy(alpha = 0.7f), content = Aurora.good)
                if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
                Text(subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** [accent] marks the main action: it comes first, highlighted, and takes the focus. */
data class DialogAction(val label: String, val icon: ImageVector? = null, val accent: Boolean = false, val onClick: () -> Unit)

/** One dialog at a time, rendered by the screen root. */
sealed interface DialogSpec {
    data class Message(val title: String, val text: String, val actions: List<DialogAction> = emptyList(), val dismissLabel: String = "Закрыть") : DialogSpec
    data class Choice(val title: String, val options: List<String>, val selected: Int = -1, val text: String? = null, val onChoose: (Int) -> Unit) : DialogSpec
    data class Input(val title: String, val hint: String, val initial: String, val confirm: String, val onConfirm: (String) -> Unit) : DialogSpec
    data class Report(val title: String, val sections: List<ReportSection>, val actions: List<DialogAction> = emptyList()) : DialogSpec
}

@Composable
fun DialogHost(spec: DialogSpec, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(620.dp)
                .background(Aurora.navyDeep.copy(alpha = 0.97f), RoundedCornerShape(20.dp))
                .padding(28.dp),
        ) {
            val first = remember { FocusRequester() }
            when (spec) {
                is DialogSpec.Message -> MessageBody(spec, onDismiss, first)
                is DialogSpec.Choice -> {
                    DialogTitle(spec.title)
                    spec.text?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = Aurora.muted)
                        Spacer(Modifier.height(16.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(spec.options) { index, option ->
                            AuroraRow(
                                onClick = { onDismiss(); spec.onChoose(index) },
                                modifier = if (index == spec.selected.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier,
                            ) {
                                Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (index == spec.selected) Icon(Icons.Check, null, Modifier.size(22.dp))
                            }
                        }
                    }
                }
                is DialogSpec.Input -> {
                    var value by remember { mutableStateOf(spec.initial) }
                    DialogTitle(spec.title)
                    AuroraTextField(value, { value = it }, spec.hint, Modifier.fillMaxWidth(), focusRequester = first, startEditing = true,
                        onSubmit = { onDismiss(); spec.onConfirm(value) })
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuroraButton(spec.confirm, accent = true, onClick = { onDismiss(); spec.onConfirm(value) })
                        AuroraButton("Отмена", onClick = onDismiss)
                    }
                }
                is DialogSpec.Report -> {
                    DialogTitle(spec.title)
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        spec.sections.forEach { section ->
                            Text(section.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Aurora.cyan, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                            section.rows.forEach { row ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.label, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, modifier = Modifier.weight(1f))
                                    row.ok?.let {
                                        Icon(if (it) Icons.Check else Icons.Close, null, tint = if (it) Aurora.good else Aurora.faint, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                                    }
                                    Text(row.value, style = MaterialTheme.typography.bodyMedium, color = if (row.ok == false) Aurora.faint else Aurora.white)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuroraButton("Готово", onClick = onDismiss, modifier = Modifier.focusRequester(first))
                        spec.actions.forEach { action -> AuroraButton(action.label, icon = action.icon, onClick = { onDismiss(); action.onClick() }) }
                    }
                }
            }
            LaunchedEffect(spec) {
                withFrameNanos { }
                runCatching { first.requestFocus() }
            }
        }
    }
}

/**
 * Title, text and buttons of a message. Focus stays on the buttons and ▲/▼ scroll the text, so
 * a long changelog reads on a remote without hunting for a focusable spot; the buttons stay
 * below the text and never scroll away.
 */
@Composable
internal fun MessageBody(spec: DialogSpec.Message, onDismiss: () -> Unit, first: FocusRequester) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 150.dp.toPx() }
    // Derived flags: the scroll position itself changes every frame and is read only while drawing.
    val scrollable by remember { derivedStateOf { scroll.maxValue > 0 } }
    val atEnd by remember { derivedStateOf { scroll.value >= scroll.maxValue } }
    Column(
        Modifier.onPreviewKeyEvent { event ->
            val down = event.key == Key.DirectionDown
            if (!scrollable || event.type != KeyEventType.KeyDown || (!down && event.key != Key.DirectionUp)) return@onPreviewKeyEvent false
            scope.launch { scroll.animateScrollBy(if (down) step else -step) }
            true
        },
    ) {
        DialogTitle(spec.title)
        Box(Modifier.heightIn(max = 320.dp)) {
            Text(spec.text, style = MaterialTheme.typography.bodyLarge, color = Aurora.muted,
                modifier = Modifier.verticalScroll(scroll).padding(end = if (scrollable) 18.dp else 0.dp))
            if (scrollable) ScrollIndicator(scroll, Modifier.matchParentSize())
        }
        Spacer(Modifier.height(22.dp))
        val main = spec.actions.filter { it.accent }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            main.forEachIndexed { index, action ->
                AuroraButton(action.label, icon = action.icon, accent = true, onClick = { onDismiss(); action.onClick() },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            AuroraButton(spec.dismissLabel, onClick = onDismiss, modifier = if (main.isEmpty()) Modifier.focusRequester(first) else Modifier)
            (spec.actions - main.toSet()).forEach { action -> AuroraButton(action.label, icon = action.icon, onClick = { onDismiss(); action.onClick() }) }
            if (scrollable) {
                Spacer(Modifier.weight(1f))
                Text(if (!atEnd) "▼ ещё текст · ▲▼ — листать" else "▲ — к началу", style = MaterialTheme.typography.bodySmall, color = Aurora.faint)
            }
        }
    }
}

/** Thin bar at the right edge: where the visible part of the text is. */
@Composable
private fun ScrollIndicator(scroll: ScrollState, modifier: Modifier) {
    Box(modifier.drawBehind {
        val max = scroll.maxValue.coerceAtLeast(1)
        val value = scroll.value
        val width = 4.dp.toPx()
        val x = size.width - width
        val track = size.height
        val thumb = (track * track / (track + max)).coerceAtLeast(24.dp.toPx())
        val y = (track - thumb) * value / max
        drawRoundRect(Aurora.white.copy(alpha = 0.12f), Offset(x, 0f), Size(width, track), CornerRadius(width / 2))
        drawRoundRect(Aurora.cyan, Offset(x, y), Size(width, thumb), CornerRadius(width / 2))
    })
}

@Composable
private fun DialogTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Aurora.white, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(16.dp))
}

/** Short confirmation at the bottom of the screen instead of a system toast. */
@Composable
fun MessageBar(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = Aurora.navy,
        modifier = modifier.background(Aurora.cyan, RoundedCornerShape(50)).padding(horizontal = 26.dp, vertical = 12.dp),
    )
}

/**
 * Remembers which element of a screen had focus, so coming back puts the user where they left.
 */
class FocusMemory {
    var key: String? = null
        private set
    private val requesters = HashMap<String, FocusRequester>()

    internal fun register(key: String, requester: FocusRequester) { requesters[key] = requester }
    internal fun unregister(key: String, requester: FocusRequester) { if (requesters[key] === requester) requesters.remove(key) }
    internal fun focused(key: String) { this.key = key }

    /** Point the next restore at [key], e.g. the folder the user just came out of. */
    fun prefer(key: String?) { this.key = key }

    fun restore(): Boolean {
        val requester = key?.let(requesters::get) ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }
}

@Composable
fun Modifier.remembered(memory: FocusMemory, key: String): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(memory, key) {
        memory.register(key, requester)
        onDispose { memory.unregister(key, requester) }
    }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) memory.focused(key) }
}

/** On entering a screen (or when [trigger] changes): restore the remembered element or focus [fallback]. */
@Composable
fun InitialFocus(memory: FocusMemory, fallback: FocusRequester, trigger: Any? = Unit) {
    LaunchedEffect(trigger) {
        withFrameNanos { }
        if (!memory.restore()) runCatching { fallback.requestFocus() }
    }
}
