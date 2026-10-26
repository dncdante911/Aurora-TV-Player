package com.aurora.tvplayer.player

import android.content.Context
import android.content.Intent
import com.aurora.tvplayer.PlayerActivity

/** One video; [subtitles] are URIs of subtitle files found next to it. */
data class PlayItem(val uri: String, val title: String, val subtitles: List<String> = emptyList())

/** A playlist (usually the videos of one folder) and where to start in it. */
data class PlayRequest(val items: List<PlayItem>, val index: Int = 0, val folder: String? = null) {
    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri }))
        .putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.title }))
        .putStringArrayListExtra(EXTRA_SUBTITLES, ArrayList(items.map { it.subtitles.joinToString("\n") }))
        .putExtra(EXTRA_INDEX, index)
        .putExtra(EXTRA_FOLDER, folder)

    companion object {
        // Extra names kept from 2.x.
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_SUBTITLES = "subtitles"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_FOLDER = "folder"
        /** The playlist travels in the intent; Android rejects intents near 1 MB, so huge folders are cut. */
        private const val MAX_ITEMS = 300

        /** Up to [MAX_ITEMS] videos of a folder around the chosen one. */
        fun around(items: List<PlayItem>, index: Int, folder: String?): PlayRequest {
            val safeIndex = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (items.size <= MAX_ITEMS) return PlayRequest(items, safeIndex, folder)
            val start = (safeIndex - MAX_ITEMS / 3).coerceIn(0, items.size - MAX_ITEMS)
            return PlayRequest(items.subList(start, start + MAX_ITEMS).toList(), safeIndex - start, folder)
        }

        fun from(intent: Intent): PlayRequest? {
            val uris = intent.getStringArrayListExtra(EXTRA_URIS)?.takeIf { it.isNotEmpty() } ?: return null
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES)?.takeIf { it.size == uris.size }
            val subtitles = intent.getStringArrayListExtra(EXTRA_SUBTITLES)?.takeIf { it.size == uris.size }
            return PlayRequest(
                items = uris.mapIndexed { i, uri ->
                    PlayItem(uri, titles?.get(i) ?: uri.substringAfterLast('/'), subtitles?.get(i)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty())
                },
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.lastIndex),
                folder = intent.getStringExtra(EXTRA_FOLDER),
            )
        }
    }
}
