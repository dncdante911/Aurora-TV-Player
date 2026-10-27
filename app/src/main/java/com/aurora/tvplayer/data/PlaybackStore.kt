package com.aurora.tvplayer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class WatchProgress(val positionMs: Long, val durationMs: Long, val watched: Boolean) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val started: Boolean get() = positionMs > 0 && !watched
}

/** One recently played video; [folder] lets "continue" rebuild the episode list around it. */
data class HistoryEntry(val uri: String, val title: String, val folder: String?, val updatedAt: Long)

/**
 * Positions and history in the "library" preferences. The position keys ("position_" + hash)
 * are the ones versions 1.x–2.x wrote, so an update keeps every saved position.
 */
class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val mutableHistory = MutableStateFlow(readHistory())
    val history: StateFlow<List<HistoryEntry>> = mutableHistory.asStateFlow()

    /** Bumped on every change so lists showing progress can refresh. */
    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    var lastFolder: String?
        get() = prefs.getString("last_folder_path", prefs.getString("folder_path", null))
        set(value) = prefs.edit { putString("last_folder_path", value) }

    fun progress(uri: String): WatchProgress = WatchProgress(
        positionMs = prefs.getLong(positionKey(uri), 0),
        durationMs = prefs.getLong(durationKey(uri), 0),
        watched = prefs.getBoolean(watchedKey(uri), false),
    )

    /** Called every few seconds while playing: position and history go to disk in one write. */
    fun save(uri: String, title: String, folder: String?, positionMs: Long, durationMs: Long) {
        val nearEnd = durationMs > 0 && positionMs >= durationMs * 0.95
        val entry = HistoryEntry(uri, title, folder, System.currentTimeMillis())
        val history = (listOf(entry) + mutableHistory.value.filterNot { it.uri == uri }).take(MAX_HISTORY)
        prefs.edit {
            if (nearEnd) {
                remove(positionKey(uri))
                putBoolean(watchedKey(uri), true)
            } else if (positionMs > 5_000) {
                putLong(positionKey(uri), positionMs)
                remove(watchedKey(uri))
            }
            if (durationMs > 0) putLong(durationKey(uri), durationMs)
            putInt(seenKey(uri), today())
            putString(KEY_HISTORY, encode(history))
        }
        publish(history)
    }

    fun markWatched(uri: String) {
        prefs.edit {
            remove(positionKey(uri))
            putBoolean(watchedKey(uri), true)
            putInt(seenKey(uri), today())
        }
        mutableRevision.value++
    }

    fun forget(uri: String) {
        val history = mutableHistory.value.filterNot { it.uri == uri }
        prefs.edit {
            remove(positionKey(uri))
            remove(watchedKey(uri))
            remove(durationKey(uri))
            remove(seenKey(uri))
            putString(KEY_HISTORY, encode(history))
        }
        publish(history)
    }

    /** A file was renamed or moved: its position, "watched" mark and history entry follow it. */
    fun move(from: String, to: String, title: String, folder: String?) {
        if (from == to) return
        val position = prefs.getLong(positionKey(from), 0)
        val duration = prefs.getLong(durationKey(from), 0)
        val watched = prefs.getBoolean(watchedKey(from), false)
        val history = mutableHistory.value.map { if (it.uri == from) it.copy(uri = to, title = title, folder = folder) else it }
        prefs.edit {
            (TRACKED + "seen_").forEach { remove(it + from.hashCode()) }
            if (position > 0) putLong(positionKey(to), position)
            if (duration > 0) putLong(durationKey(to), duration)
            if (watched) putBoolean(watchedKey(to), true)
            putInt(seenKey(to), today())
            putString(KEY_HISTORY, encode(history))
        }
        publish(history)
    }

    fun clearHistory() = writeHistory(emptyList())

    /**
     * Drops positions of files not played for over a year; without this every file ever opened
     * stays in the preferences, which Android keeps in memory as a whole. Positions written
     * before 3.4 carry no date: they get today's and a year of grace. Runs at most once a day.
     */
    fun prune() {
        val day = today()
        if (prefs.getInt(KEY_PRUNED, 0) == day) return
        val all = prefs.all
        val hashes = all.keys.mapNotNullTo(HashSet()) { key ->
            TRACKED.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) }
        }
        prefs.edit {
            hashes.forEach { hash ->
                val seen = all["seen_$hash"] as? Int
                when {
                    seen == null -> putInt("seen_$hash", day)
                    day - seen > KEEP_DAYS -> (TRACKED + "seen_").forEach { remove(it + hash) }
                }
            }
            putInt(KEY_PRUNED, day)
        }
    }

    private fun writeHistory(entries: List<HistoryEntry>) {
        prefs.edit { putString(KEY_HISTORY, encode(entries)) }
        publish(entries)
    }

    private fun publish(entries: List<HistoryEntry>) {
        mutableHistory.value = entries
        mutableRevision.value++
    }

    private fun encode(entries: List<HistoryEntry>): String = JSONArray().apply {
        entries.forEach { e -> put(JSONObject().put("uri", e.uri).put("title", e.title).put("folder", e.folder).put("at", e.updatedAt)) }
    }.toString()

    private fun readHistory(): List<HistoryEntry> {
        prefs.getString(KEY_HISTORY, null)?.let { raw ->
            return runCatching {
                val array = JSONArray(raw)
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    HistoryEntry(o.getString("uri"), o.optString("title"), o.optString("folder").ifEmpty { null }, o.optLong("at"))
                }
            }.getOrDefault(emptyList())
        }
        // Versions before 3.0 kept only the last video.
        val recent = prefs.getString("recent_uri", null) ?: return emptyList()
        return listOf(HistoryEntry(recent, prefs.getString("recent_title", null) ?: "Видео", null, System.currentTimeMillis()))
    }

    private companion object {
        const val KEY_HISTORY = "history_v3"
        const val MAX_HISTORY = 30
        const val KEY_PRUNED = "pruned_day"
        const val KEEP_DAYS = 400
        val TRACKED = listOf("position_", "duration_", "watched_")
        fun today() = (System.currentTimeMillis() / 86_400_000L).toInt()
        fun seenKey(uri: String) = "seen_" + uri.hashCode()
        fun positionKey(uri: String) = "position_" + uri.hashCode()
        fun durationKey(uri: String) = "duration_" + uri.hashCode()
        fun watchedKey(uri: String) = "watched_" + uri.hashCode()
    }
}
