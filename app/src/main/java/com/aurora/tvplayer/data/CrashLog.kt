package com.aurora.tvplayer.data

import android.content.Context
import android.os.Build
import com.aurora.tvplayer.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on the TV so a user can read it out in «Мой телевизор» and send it with a
 * report. Nothing leaves the device by itself. Android's own crash handling still runs after it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(format(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun format(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(Date())).append('\n')
            append("Aurora TV ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Поток: ").append(thread.name).append("\n\n")
            // The top of the trace is what matters; very long ones would not fit on the screen.
            append(trace.take(12_000))
        }
    }
}
