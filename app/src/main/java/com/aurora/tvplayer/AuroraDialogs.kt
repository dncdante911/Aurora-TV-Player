package com.aurora.tvplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import java.util.function.Consumer
import java.util.function.IntConsumer

class AuroraDialogs {
    private var title by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)
    private var choices by mutableStateOf<List<String>?>(null)
    private var inputHint by mutableStateOf<String?>(null)
    private var input by mutableStateOf("")
    private var positiveLabel by mutableStateOf("Готово")
    private var negativeLabel by mutableStateOf("Закрыть")
    private var onPositive: Runnable? = null
    private var onNegative: Runnable? = null
    private var onInput: Consumer<String>? = null
    private var onChoice: IntConsumer? = null

    fun showInfo(heading: String, body: String) {
        reset()
        title = heading
        message = body
    }

    fun showInfoAction(heading: String, body: String, actionLabel: String, action: Runnable) {
        showInfo(heading, body)
        positiveLabel = actionLabel
        onPositive = action
    }

    fun showChoice(heading: String, values: List<String>, handler: IntConsumer) {
        reset()
        title = heading
        choices = values.toList()
        onChoice = handler
    }

    fun showInput(heading: String, hint: String, initial: String, acceptLabel: String,
                  cancelLabel: String, accept: Consumer<String>, cancel: Runnable?) {
        reset()
        title = heading
        inputHint = hint
        input = initial
        positiveLabel = acceptLabel
        negativeLabel = cancelLabel
        onInput = accept
        onNegative = cancel
    }

    private fun reset() {
        title = null
        message = null
        choices = null
        inputHint = null
        input = ""
        positiveLabel = "Готово"
        negativeLabel = "Закрыть"
        onPositive = null
        onNegative = null
        onInput = null
        onChoice = null
    }

    @Composable fun Render() {
        val heading = title ?: return
        Dialog(onDismissRequest = ::reset) {
            Column(Modifier.width(600.dp).background(Color(0xFF142E45), RoundedCornerShape(12.dp))
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(heading, color = uiWhite, fontSize = 22.sp)
                message?.let {
                    Text(it, color = uiWhite, fontSize = 16.sp,
                        modifier = Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()))
                }
                choices?.let { values ->
                    LazyColumn(Modifier.heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        itemsIndexed(values) { index, value ->
                            ActionButton(value, Modifier.fillMaxWidth(), alignStart = true) {
                                val callback = onChoice
                                reset()
                                callback?.accept(index)
                            }
                        }
                    }
                }
                inputHint?.let { hint ->
                    var focused by remember { mutableStateOf(false) }
                    BasicTextField(value = input, onValueChange = { input = it }, singleLine = true,
                        textStyle = TextStyle(color = uiWhite, fontSize = 17.sp),
                        modifier = Modifier.fillMaxWidth().height(54.dp).onFocusChanged { focused = it.isFocused }
                            .background(uiBlue, RoundedCornerShape(6.dp))
                            .border(2.dp, if (focused) uiCyan else Color.Transparent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        decorationBox = { inner -> Box {
                            if (input.isEmpty()) Text(hint, color = uiMuted, fontSize = 16.sp)
                            inner()
                        } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(negativeLabel, Modifier.weight(1f)) {
                        val callback = onNegative
                        reset()
                        callback?.run()
                    }
                    if (onInput != null || onPositive != null) ActionButton(positiveLabel, Modifier.weight(1f)) {
                        val text = input
                        val inputCallback = onInput
                        val action = onPositive
                        reset()
                        inputCallback?.accept(text)
                        action?.run()
                    }
                }
            }
        }
    }
}
