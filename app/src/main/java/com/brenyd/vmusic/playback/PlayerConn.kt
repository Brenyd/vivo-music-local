package com.brenyd.vmusic.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.brenyd.vmusic.data.Library
import com.brenyd.vmusic.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Play modes, in the order vivo Music cycles through them. */
enum class PlayMode { LOOP_ALL, LOOP_ONE, SHUFFLE }

/** UI-side connection to [PlaybackService]. */
object PlayerConn {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    val currentPath = MutableStateFlow<String?>(null)
    val isPlaying = MutableStateFlow(false)
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val mode = MutableStateFlow(PlayMode.LOOP_ALL)
    val queuePaths = MutableStateFlow<List<String>>(emptyList())
    val queueIndex = MutableStateFlow(0)
    val sleepEndsAt = MutableStateFlow<Long?>(null)
    private var sleepJob: Job? = null

    lateinit var current: StateFlow<Song?>
        private set
    lateinit var queue: StateFlow<List<Song>>
        private set

    fun connect(ctx: Context) {
        if (controller != null || pendingConnect) return
        pendingConnect = true
        if (!::current.isInitialized) {
            current = combine(currentPath, Library.songs) { p, list -> p?.let { path -> list.firstOrNull { it.path == path } } }
                .stateIn(scope, SharingStarted.Eagerly, null)
            queue = combine(queuePaths, Library.songs) { ps, list ->
                val m = list.associateBy { it.path }
                ps.mapNotNull { m[it] }
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())
        }
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: run { pendingConnect = false; return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
            pending.forEach { it(c) }
            pending.clear()
            startTicker()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private var pendingConnect = false

    private fun withController(f: (MediaController) -> Unit) {
        val c = controller
        if (c != null) f(c) else pending += f
    }

    private fun refresh() {
        val c = controller ?: return
        currentPath.value = c.currentMediaItem?.mediaId?.let { Ids.pathOf(it) }
        isPlaying.value = c.isPlaying
        position.value = c.currentPosition.coerceAtLeast(0)
        duration.value = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (current.value?.durationMs ?: 0)
        mode.value = when {
            c.shuffleModeEnabled -> PlayMode.SHUFFLE
            c.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.LOOP_ONE
            else -> PlayMode.LOOP_ALL
        }
        queuePaths.value = (0 until c.mediaItemCount).mapNotNull { Ids.pathOf(c.getMediaItemAt(it).mediaId) }
        queueIndex.value = c.currentMediaItemIndex
    }

    private var ticker: Job? = null
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying) position.value = c.currentPosition.coerceAtLeast(0)
                }
                delay(200)
            }
        }
    }

    // ------------------------------------------------------------------ actions
    fun play(songs: List<Song>, startIndex: Int = 0, parent: String = Ids.SONGS, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        withController { c ->
            val items = songs.map { Tree.playable(it, parent) }
            c.shuffleModeEnabled = shuffle
            if (c.repeatMode == Player.REPEAT_MODE_OFF) c.repeatMode = Player.REPEAT_MODE_ALL
            val start = if (shuffle && startIndex == 0) songs.indices.random() else startIndex
            c.setMediaItems(items, start.coerceIn(0, items.size - 1), 0)
            c.prepare()
            c.play()
        }
    }

    fun shuffleAll(songs: List<Song>) = play(songs, 0, Ids.SONGS, shuffle = true)

    fun toggle() = withController { c ->
        if (c.mediaItemCount == 0) {
            c.prepare() // triggers playback resumption in the service
            c.play()
        } else if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { c ->
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); position.value = ms }
    fun playAt(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }

    /** Drop every queue entry for a song (e.g. after its file was deleted). */
    fun removePath(path: String) = withController { c ->
        for (i in c.mediaItemCount - 1 downTo 0) if (Ids.pathOf(c.getMediaItemAt(i).mediaId) == path) c.removeMediaItem(i)
    }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun playNext(song: Song) = withController { c ->
        val item = Tree.playable(song, Ids.SONGS)
        if (c.mediaItemCount == 0) { c.setMediaItem(item); c.prepare(); c.play() }
        else c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), item)
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        val items = songs.map { Tree.playable(it, Ids.SONGS) }
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare() } else c.addMediaItems(items)
    }

    fun cycleMode() = withController { c ->
        when (mode.value) {
            PlayMode.LOOP_ALL -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ONE }
            PlayMode.LOOP_ONE -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = true }
            PlayMode.SHUFFLE -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ALL }
        }
    }

    fun clearQueue() = withController { it.stop(); it.clearMediaItems() }

    /** Sleep timer: pause after [minutes] (null cancels). */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { sleepEndsAt.value = null; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        sleepEndsAt.value = end
        sleepJob = scope.launch {
            while (System.currentTimeMillis() < end) delay(1000)
            controller?.pause()
            sleepEndsAt.value = null
        }
    }
}
