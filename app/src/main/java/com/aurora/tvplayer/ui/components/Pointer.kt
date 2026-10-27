package com.aurora.tvplayer.ui.components

import android.os.SystemClock
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** When the mouse (or the remote's cursor) last really moved; hover moves focus only then. */
object PointerMotion {
    @Volatile private var movedAt = 0L

    fun moved() {
        movedAt = SystemClock.uptimeMillis()
    }

    /**
     * A list scrolling under a resting cursor also sends "enter" to the item that arrives under
     * it; following that would make the list chase the cursor.
     */
    val recent: Boolean get() = SystemClock.uptimeMillis() - movedAt < 250
}

/**
 * Mouse for TV components: tv-material's Surface reacts only to the remote. Left click runs
 * [onClick], right click [onSecondary] (the long-press menu) or, without one, goes back like
 * the mouse does elsewhere on Android; hovering moves the focus here, so the highlight follows
 * the pointer. Touch works the same way as a left click.
 */
fun Modifier.pointerClick(onClick: () -> Unit, onSecondary: (() -> Unit)? = null, enabled: Boolean = true): Modifier = composed {
    val requester = remember { FocusRequester() }
    val click by rememberUpdatedState(onClick)
    val secondary by rememberUpdatedState(onSecondary)
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    focusRequester(requester).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            var pressed = false
            var secondaryPress = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move ->
                        if (change.type == PointerType.Mouse && !change.pressed && PointerMotion.recent) runCatching { requester.requestFocus() }
                    PointerEventType.Press -> {
                        pressed = true
                        secondaryPress = event.buttons.isSecondaryPressed
                        change.consume()
                    }
                    PointerEventType.Release -> {
                        val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                        if (pressed && inside) {
                            change.consume()
                            runCatching { requester.requestFocus() }
                            when {
                                !secondaryPress -> click()
                                secondary != null -> secondary?.invoke()
                                else -> back?.onBackPressed()
                            }
                        }
                        pressed = false
                    }
                    PointerEventType.Exit -> pressed = false
                }
            }
        }
    }
}
