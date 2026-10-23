package com.aurora.tvplayer.player

import android.content.Context
import android.content.Intent
import com.aurora.tvplayer.PlayerActivity

data class PlayItem(val uri: String, val title: String)

/** A playlist (usually the videos of one folder) and where to start in it. */
data class PlayRequest(val items: List<PlayItem>, val index: Int = 0, val folder: String? = null) {
    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri }))
        .putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.title }))
        .putExtra(EXTRA_INDEX, index)
        .putExtra(EXTRA_FOLDER, folder)

    companion object {
        // Extra names kept from 2.x.
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_FOLDER = "folder"

        fun from(intent: Intent): PlayRequest? {
            val uris = intent.getStringArrayListExtra(EXTRA_URIS)?.takeIf { it.isNotEmpty() } ?: return null
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES)?.takeIf { it.size == uris.size }
            return PlayRequest(
                items = uris.mapIndexed { i, uri -> PlayItem(uri, titles?.get(i) ?: uri.substringAfterLast('/')) },
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.lastIndex),
                folder = intent.getStringExtra(EXTRA_FOLDER),
            )
        }
    }
}
