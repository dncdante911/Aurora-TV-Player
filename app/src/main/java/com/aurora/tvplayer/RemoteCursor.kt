package com.aurora.tvplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.graphics.withTranslation
import com.aurora.tvplayer.ui.components.PointerMotion

/**
 * A mouse pointer driven by the remote ("Курсор пультом"): the arrows move it smoothly and
 * faster the longer they are held, OK clicks, a long OK is the right button (the row menu).
 * At an edge of the screen the page scrolls like with a mouse wheel. Events reach the app as
 * real mouse events, so everything that works with a mouse works with it. Back, media and
 * other keys keep their usual meaning. Dialogs are separate windows: there the remote works as
 * usual and the pointer waits underneath.
 */
class RemoteCursor(private val activity: ComponentActivity, private val onMove: () -> Unit = {}) {
    private val density = activity.resources.displayMetrics.density
    private val view = CursorView(activity)
    private var x = 0f
    private var y = 0f
    private var horizontal = 0
    private var vertical = 0
    private var heldSince = 0L
    private var lastFrame = 0L
    private var lastScroll = 0L
    private var okDownAt = 0L
    private var running = false

    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            view.visibility = if (value) View.VISIBLE else View.GONE
            if (value) {
                x = root.width / 2f
                y = root.height / 2f
                show()
            } else stop()
        }

    private val root: ViewGroup get() = activity.window.decorView as ViewGroup

    /** Call after setContent: the pointer is drawn above the screen, below dialogs. */
    fun attach() {
        if (view.parent == null) root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** First look at every key; true when the pointer used it. */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> steer(down, dx = -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> steer(down, dx = 1)
            KeyEvent.KEYCODE_DPAD_UP -> steer(down, dy = -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> steer(down, dy = 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) okDownAt = event.eventTime
                if (!down) {
                    // A hidden pointer first shows where it is; the next OK clicks.
                    if (view.alpha < 0.5f) show() else click(secondary = event.eventTime - okDownAt >= LONG_PRESS_MS)
                }
            }
            else -> return false
        }
        return true
    }

    private fun steer(down: Boolean, dx: Int = 0, dy: Int = 0) {
        if (down) {
            if (dx != 0) horizontal = dx
            if (dy != 0) vertical = dy
            if (!running) {
                running = true
                heldSince = SystemClock.uptimeMillis()
                lastFrame = 0L
                Choreographer.getInstance().postFrameCallback(frame)
            }
        } else {
            if (dx != 0 && horizontal == dx) horizontal = 0
            if (dy != 0 && vertical == dy) vertical = 0
        }
        show()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!enabled || (horizontal == 0 && vertical == 0)) {
                running = false
                return
            }
            val now = frameTimeNanos / 1_000_000
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
            lastFrame = now
            // 320 dp/s at first, up to 1500 dp/s after a second of holding: precise and quick.
            val held = ((SystemClock.uptimeMillis() - heldSince) / 1000f).coerceIn(0f, 1f)
            val speed = (320f + 1180f * held * held) * density
            val width = root.width.toFloat()
            val height = root.height.toFloat()
            x = (x + horizontal * speed * dt).coerceIn(0f, width - 1)
            y = (y + vertical * speed * dt).coerceIn(0f, height - 1)
            hover()
            edgeScroll(width, height, now)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun edgeScroll(width: Float, height: Float, now: Long) {
        if (now - lastScroll < 90) return
        val edge = 2f
        val vScroll = when {
            vertical > 0 && y >= height - edge -> -1f
            vertical < 0 && y <= edge -> 1f
            else -> 0f
        }
        val hScroll = when {
            horizontal > 0 && x >= width - edge -> 1f
            horizontal < 0 && x <= edge -> -1f
            else -> 0f
        }
        if (vScroll == 0f && hScroll == 0f) return
        lastScroll = now
        dispatch(MotionEvent.ACTION_SCROLL, 0) { coords ->
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        }
    }

    private fun hover() {
        PointerMotion.moved()
        onMove()
        dispatch(MotionEvent.ACTION_HOVER_MOVE, 0)
        show()
    }

    private fun click(secondary: Boolean) {
        PointerMotion.moved()
        val button = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, button, downTime)
        dispatch(MotionEvent.ACTION_UP, 0, downTime)
        show()
    }

    /** The pointer fades after a few idle seconds, so it never sits on top of a film. */
    private val fade = Runnable { view.animate().alpha(0f).setDuration(400).start() }

    private fun show() {
        view.moveTo(x, y)
        view.removeCallbacks(fade)
        view.animate().cancel()
        view.alpha = 1f
        view.postDelayed(fade, IDLE_MS)
    }

    private fun stop() {
        horizontal = 0
        vertical = 0
    }

    private fun dispatch(action: Int, buttons: Int, downTime: Long = SystemClock.uptimeMillis(), axes: (MotionEvent.PointerCoords) -> Unit = {}) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = this@RemoteCursor.x; this.y = this@RemoteCursor.y; pressure = 1f; size = 1f }
        axes(coords)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) root.dispatchTouchEvent(event)
            else root.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val LONG_PRESS_MS = 550L
        const val IDLE_MS = 5_000L
    }
}

/** The arrow itself: white with a dark outline, readable over any film or menu. */
@SuppressLint("ViewConstructor")
private class CursorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var px = 0f
    private var py = 0f
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 22f)
        lineTo(6f, 17f)
        lineTo(10f, 26f)
        lineTo(14f, 24f)
        lineTo(10f, 15.5f)
        lineTo(17f, 15.5f)
        close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 5, 14, 24)
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0); style = Paint.Style.FILL }

    init {
        visibility = GONE
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun moveTo(x: Float, y: Float) {
        px = x
        py = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = density * 1.15f
        canvas.withTranslation(px + 2 * density, py + 3 * density) {
            scale(scale, scale)
            drawPath(arrow, shadow)
        }
        canvas.withTranslation(px, py) {
            scale(scale, scale)
            drawPath(arrow, fill)
            drawPath(arrow, outline)
        }
    }

    // Never takes the events it shows the way for.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}
