package com.brenyd.vmusic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brenyd.vmusic.data.Song

/** Screens pushed on top of the bottom-tab pages. */
sealed interface Dest {
    data object Search : Dest
    data class AlbumPage(val key: String) : Dest
    data class ArtistPage(val name: String) : Dest
    data class FolderPage(val path: String) : Dest
    /** Playlist id, or one of Ids.FAVORITES / RECENT / MOST. */
    data class PlaylistPage(val id: String) : Dest
}

/** Simple app-wide UI state (one activity, one window). */
object Ui {
    var tab by mutableIntStateOf(1)          // 0 Home, 1 Local, 2 Me
    var localTab by mutableIntStateOf(0)     // Songs, Artists, Albums, Folders
    val stack = mutableStateListOf<Dest>()
    var playerOpen by mutableStateOf(false)

    // sheets
    var moreFor by mutableStateOf<Song?>(null)
    var moreContextPlaylist by mutableStateOf<String?>(null)
    var infoFor by mutableStateOf<Song?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var queueOpen by mutableStateOf(false)
    var sleepOpen by mutableStateOf(false)
    var deleteFor by mutableStateOf<Song?>(null)
    var hiddenOpen by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    fun push(d: Dest) {
        playerOpen = false
        stack.add(d)
    }

    fun back(): Boolean {
        when {
            infoFor != null -> infoFor = null
            moreFor != null -> moreFor = null
            queueOpen -> queueOpen = false
            sleepOpen -> sleepOpen = false
            deleteFor != null -> deleteFor = null
            hiddenOpen -> hiddenOpen = false
            addToPlaylist != null -> addToPlaylist = null
            playerOpen -> playerOpen = false
            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
            else -> return false
        }
        return true
    }

    fun more(song: Song, playlistId: String? = null) {
        moreContextPlaylist = playlistId
        moreFor = song
    }
}
