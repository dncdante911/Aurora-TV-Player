package com.aurora.tvplayer

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.function.IntConsumer

class PlayerUi(activity: Activity, private val actions: Actions) {
    interface Actions {
        fun previous()
        fun rewind()
        fun toggle()
        fun forward()
        fun next()
        fun exit()
        fun audio()
        fun subtitles()
        fun settings()
        fun retry()
        fun showError()
        fun menuClosed()
    }
    val view = ComposeView(activity)
    var title by mutableStateOf("Видео")
    var time by mutableStateOf("00:00 / --:--")
    var status by mutableStateOf("")
    var playing by mutableStateOf(false)
    var error by mutableStateOf(false)
    var previousEnabled by mutableStateOf(false)
    var nextEnabled by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var visible by mutableStateOf(true)
    private var dialogTitle by mutableStateOf<String?>(null)
    private var dialogChoices by mutableStateOf<List<String>?>(null)
    private var dialogMessage by mutableStateOf<String?>(null)
    private var choiceHandler: IntConsumer? = null

    fun showChoices(title: String, choices: List<String>, handler: IntConsumer) {
        choiceHandler = handler
        dialogTitle = title
        dialogChoices = choices.toList()
        dialogMessage = null
    }

    fun showInfo(title: String, message: String) {
        dialogTitle = title
        dialogChoices = null
        dialogMessage = message
    }

    private fun closeDialog() {
        dialogTitle = null
        dialogChoices = null
        dialogMessage = null
        choiceHandler = null
        actions.menuClosed()
    }

    init { view.setContent { AuroraTheme { Screen() } } }

    @Composable private fun Screen() {
        if (!visible) return
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xD9091B2D))
                    .padding(start = 34.dp, end = 34.dp, top = 16.dp, bottom = 17.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, color = uiWhite, fontSize = 19.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(time, color = uiMuted, fontSize = 15.sp)
                }
                if (status.isNotEmpty()) ActionButton(status, Modifier.fillMaxWidth(), action = actions::showError)
                Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFF708597), RoundedCornerShape(4.dp))
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) { actions.rewind(); true }
                        else if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) { actions.forward(); true }
                        else false
                    }.focusable()) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(7.dp)
                        .background(uiCyan, RoundedCornerShape(4.dp)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (previousEnabled) ActionButton("◀◀ Серия", Modifier.weight(1f), action = actions::previous)
                    ActionButton("−10 с", Modifier.weight(1f), action = actions::rewind)
                    ActionButton(if (playing) "❚❚ Пауза" else "▶ Играть", Modifier.weight(1.2f), action = actions::toggle)
                    ActionButton("+30 с", Modifier.weight(1f), action = actions::forward)
                    if (nextEnabled) ActionButton("Серия ▶▶", Modifier.weight(1f), action = actions::next)
                    ActionButton("К файлам", Modifier.weight(1f), action = actions::exit)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Аудио", Modifier.weight(1f), action = actions::audio)
                    ActionButton("Субтитры", Modifier.weight(1f), action = actions::subtitles)
                    ActionButton("Настройки", Modifier.weight(1f), action = actions::settings)
                    if (error) ActionButton("Повторить", Modifier.weight(1f), action = actions::retry)
                }
            }
        }
        dialogTitle?.let { heading ->
            Dialog(onDismissRequest = ::closeDialog) {
                Column(Modifier.width(570.dp).background(Color(0xFF142E45), RoundedCornerShape(12.dp))
                    .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(heading, color = uiWhite, fontSize = 22.sp)
                    dialogChoices?.let { choices ->
                        LazyColumn(modifier = Modifier.heightIn(max = 370.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            itemsIndexed(choices) { index, choice ->
                                ActionButton(choice, Modifier.fillMaxWidth(), alignStart = true) {
                                    val selected = choiceHandler
                                    closeDialog()
                                    selected?.accept(index)
                                }
                            }
                        }
                    }
                    dialogMessage?.let { message ->
                        Text(message, color = uiWhite, fontSize = 16.sp,
                            modifier = Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()))
                        ActionButton("Закрыть", Modifier.fillMaxWidth(), action = ::closeDialog)
                    }
                }
            }
        }
    }
}
