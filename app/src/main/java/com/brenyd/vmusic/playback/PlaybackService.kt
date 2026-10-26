package com.brenyd.vmusic.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.brenyd.vmusic.data.Art
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Song
import com.brenyd.vmusic.ui.MainActivity
import com.brenyd.vmusic.ui.MusicWidget
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(AudioFx.hiResOutput) // keep 24-bit audio at full resolution
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val sessionId = am.generateAudioSessionId()
        player.audioSessionId = sessionId
        AudioFx.attach(sessionId)
        AudioFx.initDevices(this)
        // let system equalizer apps (e.g. Wavelet) attach to our audio too
        sendBroadcast(Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC))

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { Ids.pathOf(it.mediaId) }?.let { Library.recordPlay(it) }
                QueueStore.save(player)
                fadeInFrom = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && AudioFx.fadeSeconds > 0) System.currentTimeMillis() else 0L
                MusicWidget.update(this@PlaybackService, player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) QueueStore.save(player)
                MusicWidget.update(this@PlaybackService, player)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                gain = AudioFx.gainFactor(AudioFx.readGain(tracks))
                for (g in tracks.groups) if (g.type == C.TRACK_TYPE_AUDIO && g.length > 0) {
                    AudioFx.onTrackSampleRate(g.getTrackFormat(0).sampleRate)
                }
                applyVolume(player)
            }
        })
        AudioFx.onGainSettingsChanged = { gain = AudioFx.gainFactor(AudioFx.readGain(player.currentTracks)); applyVolume(player) }
        startFadeTicker(player)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .build()
        if (!Library.scannedOnce.value) Library.requestRescan()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // ------------------------------------------------------------------ volume: ReplayGain x fade
    private var gain = 1f
    private var fade = 1f
    private var fadeInFrom = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun applyVolume(player: Player) {
        player.volume = (gain * fade).coerceIn(0f, 1f)
    }

    /** "Fade between songs": fade out at the end of a song and in at the start of the next. */
    private fun startFadeTicker(player: Player) {
        val tick = object : Runnable {
            override fun run() {
                val len = AudioFx.fadeSeconds * 1000L
                var f = 1f
                if (len > 0 && player.isPlaying && player.repeatMode != Player.REPEAT_MODE_ONE) {
                    val dur = player.duration
                    val pos = player.currentPosition
                    if (dur > len * 2 && player.hasNextMediaItem() && dur - pos < len) f = ((dur - pos).toFloat() / len).coerceIn(0f, 1f)
                    if (fadeInFrom > 0) {
                        val since = System.currentTimeMillis() - fadeInFrom
                        if (since < len) f = minOf(f, (since.toFloat() / len).coerceIn(0.05f, 1f)) else fadeInFrom = 0
                    }
                }
                if (f != fade) { fade = f; applyVolume(player) }
                handler.postDelayed(this, 100)
            }
        }
        handler.post(tick)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AudioFx.release()
        session?.let {
            QueueStore.save(it.player)
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {
        private val searchResults = HashMap<String, List<MediaItem>>()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean(Ids.SEARCH_SUPPORTED, true)
                putInt(Ids.STYLE_BROWSABLE, Ids.STYLE_LIST)
                putInt(Ids.STYLE_PLAYABLE, Ids.STYLE_LIST)
            }
            return Futures.immediateFuture(
                LibraryResult.ofItem(Tree.root(), LibraryParams.Builder().setExtras(extras).build())
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Library.scope.future {
            awaitLibrary()
            val item = Tree.item(mediaId)
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Library.scope.future {
            awaitLibrary()
            val all = Tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = Library.scope.future {
            awaitLibrary()
            val res = Tree.search(query)
            searchResults[query] = res
            session.notifySearchResultChanged(browser, query, res.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Library.scope.future {
            awaitLibrary()
            val all = searchResults[query] ?: Tree.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Library.scope.future {
            awaitLibrary()
            mediaItems.flatMap { resolve(it) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Library.scope.future {
            awaitLibrary()
            // A single song chosen in Android Auto: play the list it came from, starting at that song.
            if (mediaItems.size == 1 && mediaItems[0].localConfiguration == null) {
                val id = mediaItems[0].mediaId
                val q = mediaItems[0].requestMetadata.searchQuery
                if (!q.isNullOrBlank() && id.isEmpty()) {
                    val res = Tree.searchSongs(q).map { Tree.playable(it, "search") }
                    return@future MediaSession.MediaItemsWithStartPosition(res, 0, C.TIME_UNSET)
                }
                if (id == Ids.SHUFFLE_ALL) {
                    val all = Library.songs.value.shuffled().map { Tree.playable(it, Ids.SONGS) }
                    return@future MediaSession.MediaItemsWithStartPosition(all, 0, C.TIME_UNSET)
                }
                val parent = Ids.parentOf(id)
                val path = Ids.pathOf(id)
                if (parent != null && path != null) {
                    val list = Tree.songsOf(parent)
                    val idx = list.indexOfFirst { it.path == path }
                    if (idx >= 0) {
                        return@future MediaSession.MediaItemsWithStartPosition(
                            list.map { Tree.playable(it, parent) }, idx, startPositionMs,
                        )
                    }
                }
            }
            val resolved = mediaItems.flatMap { resolve(it) }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Library.scope.future {
            awaitLibrary()
            QueueStore.restore() ?: MediaSession.MediaItemsWithStartPosition(
                Library.songs.value.shuffled().take(200).map { Tree.playable(it, Ids.SONGS) }, 0, 0,
            )
        }

        private fun resolve(item: MediaItem): List<MediaItem> {
            if (item.localConfiguration != null) return listOf(item)
            val q = item.requestMetadata.searchQuery
            if (!q.isNullOrBlank()) return Tree.searchSongs(q).map { Tree.playable(it, "search") }
            if (item.mediaId == Ids.SHUFFLE_ALL) return Library.songs.value.shuffled().map { Tree.playable(it, Ids.SONGS) }
            val path = Ids.pathOf(item.mediaId)
            if (path != null) Library.song(path)?.let { return listOf(Tree.playable(it, Ids.parentOf(item.mediaId) ?: Ids.SONGS)) }
            // a browsable node (album, playlist...) was sent to be played: play its songs
            return Tree.songsOf(item.mediaId).map { Tree.playable(it, item.mediaId) }
        }
    }

    private suspend fun awaitLibrary() {
        if (Library.scannedOnce.value) return
        Library.requestRescan()
        withTimeoutOrNull(15_000) { Library.scannedOnce.first { it } }
    }
}

/** Media ids used for the browse tree (Android Auto) and the play queue. */
object Ids {
    const val ROOT = "root"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "pl:fav"
    const val RECENT = "pl:recent"
    const val MOST = "pl:most"
    const val SHUFFLE_ALL = "shuffle:all"

    const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val STYLE_LIST = 1
    const val STYLE_GRID = 2

    fun song(parent: String, path: String) = "song:$parent|$path"
    fun album(key: String) = "album:$key"
    fun artist(name: String) = "artist:$name"
    fun playlist(id: String) = "pl:$id"
    fun folder(path: String) = "folder:$path"

    fun pathOf(mediaId: String): String? =
        if (mediaId.startsWith("song:") && mediaId.contains('|')) mediaId.substringAfter('|') else null

    fun parentOf(mediaId: String): String? =
        if (mediaId.startsWith("song:") && mediaId.contains('|')) mediaId.removePrefix("song:").substringBefore('|') else null
}

/** Builds the browse tree from the library. */
object Tree {
    private fun node(id: String, title: String, subtitle: String? = null, art: String? = null, style: Int? = null, playable: Boolean = false): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(playable)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setArtworkUri(Art.contentUri(art))
        if (style != null) md.setExtras(Bundle().apply { putInt(Ids.STYLE_BROWSABLE, style); putInt(Ids.STYLE_PLAYABLE, Ids.STYLE_LIST) })
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(md.build()).build()
    }

    fun playable(s: Song, parent: String): MediaItem = MediaItem.Builder()
        .setMediaId(Ids.song(parent, s.path))
        .setUri(s.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setAlbumArtist(s.albumArtist.ifBlank { null })
                .setTrackNumber(s.track.takeIf { it > 0 })
                .setReleaseYear(s.year.takeIf { it > 0 })
                .setDurationMs(s.durationMs)
                .setArtworkUri(Art.contentUri(s.artPath))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    fun root(): MediaItem = node(Ids.ROOT, "Music")

    fun children(parentId: String): List<MediaItem> {
        val lib = Library
        return when {
            parentId == Ids.ROOT -> listOf(
                node(Ids.SONGS, "Songs", style = Ids.STYLE_LIST),
                node(Ids.ALBUMS, "Albums", style = Ids.STYLE_GRID),
                node(Ids.ARTISTS, "Artists", style = Ids.STYLE_LIST),
                node(Ids.PLAYLISTS, "Playlists", style = Ids.STYLE_LIST),
            )
            parentId == Ids.SONGS -> listOf(
                MediaItem.Builder().setMediaId(Ids.SHUFFLE_ALL).setMediaMetadata(
                    MediaMetadata.Builder().setTitle("Shuffle all").setIsBrowsable(false).setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()
                ).build()
            ) + songsOf(Ids.SONGS).map { playable(it, Ids.SONGS) }
            parentId == Ids.ALBUMS -> lib.albums.value.map { node(Ids.album(it.key), it.name, it.artist, it.artPath, playable = true) }
            parentId == Ids.ARTISTS -> lib.artists.value.map { node(Ids.artist(it.name), it.name, "${it.songs.size} songs", it.artPath, playable = true) }
            parentId == Ids.PLAYLISTS -> buildList {
                add(node(Ids.FAVORITES, "Favorites", "${lib.favorites.value.size} songs", playable = true))
                add(node(Ids.RECENT, "Recently played", playable = true))
                add(node(Ids.MOST, "Most played", playable = true))
                lib.playlists.value.forEach { add(node(Ids.playlist(it.id), it.name, "${it.paths.size} songs", playable = true)) }
            }
            else -> songsOf(parentId).map { playable(it, parentId) }
        }
    }

    fun songsOf(parentId: String): List<Song> {
        val lib = Library
        return when {
            parentId == Ids.SONGS -> lib.sorted(lib.songs.value, lib.songSort)
            parentId == Ids.FAVORITES -> lib.songsFor(lib.favorites.value.toList()).sortedBy { it.title.lowercase() }
            parentId == Ids.RECENT -> lib.songsFor(lib.recents.value.take(100))
            parentId == Ids.MOST -> lib.songsFor(lib.playCounts.value.entries.sortedByDescending { it.value }.take(100).map { it.key })
            parentId.startsWith("album:") -> lib.albums.value.firstOrNull { it.key == parentId.removePrefix("album:") }?.songs.orEmpty()
            parentId.startsWith("artist:") -> lib.artists.value.firstOrNull { it.name == parentId.removePrefix("artist:") }?.songs.orEmpty()
            parentId.startsWith("folder:") -> lib.folders.value.firstOrNull { it.path == parentId.removePrefix("folder:") }?.songs.orEmpty()
            parentId.startsWith("pl:") -> lib.playlists.value.firstOrNull { it.id == parentId.removePrefix("pl:") }?.let { lib.songsFor(it.paths) }.orEmpty()
            else -> emptyList()
        }
    }

    fun item(mediaId: String): MediaItem? {
        if (mediaId == Ids.ROOT) return root()
        Ids.pathOf(mediaId)?.let { p -> return Library.song(p)?.let { playable(it, Ids.parentOf(mediaId) ?: Ids.SONGS) } }
        return children(Ids.ROOT).firstOrNull { it.mediaId == mediaId }
            ?: listOf(Ids.ALBUMS, Ids.ARTISTS, Ids.PLAYLISTS).asSequence().flatMap { children(it) }.firstOrNull { it.mediaId == mediaId }
    }

    fun searchSongs(query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = Library.songs.value
        val words = q.split(Regex("\\s+"))
        return all.filter { s ->
            val hay = "${s.title} ${s.artist} ${s.album}".lowercase()
            words.all { hay.contains(it) }
        }.sortedByDescending { s ->
            when {
                s.title.lowercase() == q -> 3
                s.title.lowercase().startsWith(q) -> 2
                s.artist.lowercase().contains(q) -> 1
                else -> 0
            }
        }
    }

    fun search(query: String): List<MediaItem> = searchSongs(query).take(100).map { playable(it, "search") }
}
