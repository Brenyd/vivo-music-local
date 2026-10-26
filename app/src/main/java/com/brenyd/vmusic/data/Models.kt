package com.brenyd.vmusic.data

import android.net.Uri

/** A song as the app shows it: file tags from MediaStore, with any matched/edited info applied on top. */
data class Song(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val track: Int,
    val year: Int,
    val size: Long,
    val dateAdded: Long,
    val mime: String,
    val bitrate: Int,
    val folder: String,
    /** Cached artwork file (embedded art, downloaded art or a cover the user picked), or null. */
    val artPath: String?,
    /** Info was matched online (shows the blue check like vivo Music). */
    val matched: Boolean,
    /** Original tags from the file, used by "Restore". */
    val fileTitle: String,
    val fileArtist: String,
    val fileAlbum: String,
    /** Real audio quality read from the file (null until probed). */
    val quality: AudioQuality? = null,
    /** Cover URL for songs on a server. */
    val artUrl: String? = null,
) {
    val remote: Boolean get() = Remote.isRemote(path)
    /** Something [ArtImage] can show: a cached file or a URL. */
    val art: String? get() = artPath ?: artUrl
    val key: String get() = path
    val folderName: String get() = folder.substringAfterLast('/')
}

data class Album(val key: String, val name: String, val artist: String, val songs: List<Song>) {
    val artPath: String? get() = songs.firstNotNullOfOrNull { it.artPath }
}

data class Artist(val name: String, val songs: List<Song>, val imagePath: String?) {
    val artPath: String? get() = imagePath ?: songs.firstNotNullOfOrNull { it.artPath }
}

data class Folder(val path: String, val songs: List<Song>) {
    val name: String get() = path.substringAfterLast('/').ifEmpty { path }
}

data class Playlist(val id: String, val name: String, val paths: List<String>)

/** Matched / edited info stored by the app (the music files themselves are not modified). */
data class Override(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val art: String? = null,
    val matched: Boolean = false,
)

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

enum class SortOrder(val label: String) {
    TITLE("Song name"),
    ARTIST("Artist"),
    ALBUM("Album"),
    ADDED("Date added"),
    DURATION("Duration"),
}
