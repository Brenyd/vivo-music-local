package com.brenyd.vmusic.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.brenyd.vmusic.data.Library

/** Remembers the play queue so playback can resume later (app restart, Bluetooth/car "play"). */
object QueueStore {
    private const val KEY_IDS = "queue_ids"
    private const val KEY_INDEX = "queue_index"
    private const val KEY_POS = "queue_pos"

    fun save(player: Player) {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        Library.prefs.edit()
            .putString(KEY_IDS, ids.joinToString("\n"))
            .putInt(KEY_INDEX, player.currentMediaItemIndex)
            .putLong(KEY_POS, player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun restore(): MediaSession.MediaItemsWithStartPosition? {
        val ids = Library.prefs.getString(KEY_IDS, null)?.split("\n")?.filter { it.isNotBlank() } ?: return null
        val items = ids.mapNotNull { id ->
            val path = Ids.pathOf(id) ?: return@mapNotNull null
            Library.song(path)?.let { Tree.playable(it, Ids.parentOf(id) ?: Ids.SONGS) }
        }
        if (items.isEmpty()) return null
        val idx = Library.prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.size - 1)
        return MediaSession.MediaItemsWithStartPosition(items, idx, Library.prefs.getLong(KEY_POS, 0))
    }
}
