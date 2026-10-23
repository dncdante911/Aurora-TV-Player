package com.aurora.tvplayer

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class FolderUi(activity: Activity, private val network: Boolean, private val actions: Actions) {
    interface Actions {
        fun openDisks()
        fun goUp()
        fun filter()
        fun search()
        fun refresh()
        fun connect()
        fun openConnection()
        fun openItem(index: Int)
    }
    data class Item(val name: String, val kind: String)

    val view = ComposeView(activity)
    val dialogs = AuroraDialogs()
    var title by mutableStateOf(if (network) "Домашняя сеть" else "Накопители")
    var status by mutableStateOf("")
    var filterTitle by mutableStateOf("Все файлы")
    var connectionVisible by mutableStateOf(network)
    var busy by mutableStateOf(false)
    var host by mutableStateOf("")
    var share by mutableStateOf("")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    val items = mutableStateListOf<Item>()

    init { view.setContent { AuroraTheme { Screen() } } }

    @Composable private fun Screen() {
        Column(Modifier.fillMaxSize().background(uiBackdrop).padding(horizontal = 38.dp, vertical = 20.dp)) {
            Text(if (network) "AURORA TV  /  ДОМАШНЯЯ СЕТЬ" else "AURORA TV  /  ФАЙЛЫ", color = uiCyan, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, color = uiWhite, fontSize = 27.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!network) ActionButton("Диски", Modifier.weight(1f), action = actions::openDisks)
                ActionButton("← Назад", Modifier.weight(1f), action = actions::goUp)
                if (!network) ActionButton(filterTitle, Modifier.weight(1f), action = actions::filter)
                if (network) ActionButton("Подключение", Modifier.weight(1f), action = actions::openConnection)
                ActionButton("Поиск", Modifier.weight(1f), action = actions::search)
                if (network) ActionButton("Обновить", Modifier.weight(1f), action = actions::refresh)
            }
            if (network && connectionVisible) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Input("Адрес ПК / NAS", host, { host = it }, Modifier.weight(1.5f))
                    Input("Общая папка", share, { share = it }, Modifier.weight(1f))
                    Input("Пользователь", user, { user = it }, Modifier.weight(1f))
                    Input("Пароль", password, { password = it }, Modifier.weight(1f), true)
                }
                Spacer(Modifier.height(10.dp))
                ActionButton(if (busy) "Подключаюсь…" else "Подключить", Modifier.fillMaxWidth(), action = actions::connect)
            }
            Spacer(Modifier.height(16.dp))
            Text(status, color = uiMuted, fontSize = 15.sp)
            Spacer(Modifier.height(9.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(items) { index, item ->
                    ActionButton("${when (item.kind) { "volume" -> "▣"; "folder" -> "▸"; "video" -> "▶"; else -> "·" }}   ${item.name}", Modifier.fillMaxWidth(), alignStart = true) {
                        actions.openItem(index)
                    }
                }
            }
        }
        dialogs.Render()
    }
}

@Composable private fun Input(hint: String, value: String, changed: (String) -> Unit, modifier: Modifier, secret: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = changed, singleLine = true,
        textStyle = TextStyle(color = uiWhite, fontSize = 16.sp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.height(56.dp).onFocusChanged { focused = it.isFocused }
            .background(uiBlue, RoundedCornerShape(6.dp))
            .border(2.dp, if (focused) uiCyan else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        decorationBox = { inner -> Box {
            if (value.isEmpty()) Text(hint, color = uiMuted, fontSize = 15.sp)
            inner()
        } }
    )
}
