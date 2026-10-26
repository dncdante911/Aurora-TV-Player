package com.aurora.tvplayer.player

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/** The audio and subtitle choice: what the user picked, matched again in the next file. */
data class TrackChoice(
    val audioCustom: Boolean = false,
    val audioLanguage: String? = null,
    val audioLabel: String? = null,
    val audioMime: String? = null,
    val audioChannels: Int = -1,
    val audioIndex: Int = -1,
    val subtitlesEnabled: Boolean = true,
    val subtitleLanguage: String? = null,
)

/**
 * Track choices per folder (one series = one folder), so the original track picked for one show
 * does not follow into another. A folder never chosen in starts from the latest choice anywhere,
 * which is how 2.x–3.3 behaved. Lives in the "library" preferences with the 2.x keys as fallback.
 */
class TrackMemory(private val prefs: SharedPreferences, folder: String?) {
    private val folderKey = folder?.let { "tracks_" + it.trimEnd('/').hashCode() }

    fun load(): TrackChoice = folderKey?.let(::read) ?: read(GLOBAL) ?: legacy()

    fun save(choice: TrackChoice) = prefs.edit {
        val json = write(choice)
        folderKey?.let { putString(it, json) }
        putString(GLOBAL, json)
    }

    fun update(transform: (TrackChoice) -> TrackChoice) = save(transform(load()))

    private fun read(key: String): TrackChoice? = prefs.getString(key, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            TrackChoice(
                audioCustom = o.optBoolean("audioCustom"),
                audioLanguage = o.optString("audioLanguage").ifEmpty { null },
                audioLabel = o.optString("audioLabel").ifEmpty { null },
                audioMime = o.optString("audioMime").ifEmpty { null },
                audioChannels = o.optInt("audioChannels", -1),
                audioIndex = o.optInt("audioIndex", -1),
                subtitlesEnabled = o.optBoolean("subtitlesEnabled", true),
                subtitleLanguage = o.optString("subtitleLanguage").ifEmpty { null },
            )
        }.getOrNull()
    }

    private fun write(c: TrackChoice): String = JSONObject()
        .put("audioCustom", c.audioCustom)
        .put("audioLanguage", c.audioLanguage)
        .put("audioLabel", c.audioLabel)
        .put("audioMime", c.audioMime)
        .put("audioChannels", c.audioChannels)
        .put("audioIndex", c.audioIndex)
        .put("subtitlesEnabled", c.subtitlesEnabled)
        .put("subtitleLanguage", c.subtitleLanguage)
        .toString()

    /** What versions before 3.4 stored, one global choice in separate keys. */
    private fun legacy() = TrackChoice(
        audioCustom = prefs.getBoolean("audio_custom", false),
        audioLanguage = prefs.getString("audio_language", null),
        audioLabel = prefs.getString("audio_label", null),
        audioMime = prefs.getString("audio_mime", null),
        audioChannels = prefs.getInt("audio_channels", -1),
        audioIndex = prefs.getInt("audio_index", -1),
        subtitlesEnabled = prefs.getBoolean("subtitles_enabled", true),
        subtitleLanguage = prefs.getString("subtitle_language", null),
    )

    private companion object {
        const val GLOBAL = "tracks_global"
    }
}
