package com.brenyd.vmusic.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Raw row from MediaStore (the file's own tags). */
data class RawSong(
    val id: Long, val uri: Uri, val path: String, val title: String, val artist: String, val album: String,
    val albumArtist: String, val durationMs: Long, val track: Int, val year: Int, val size: Long,
    val dateAdded: Long, val mime: String, val bitrate: Int,
)

object Library {
    private const val TAG = "Library"
    lateinit var app: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: SharedPreferences
        private set

    private val raw = MutableStateFlow<List<RawSong>>(emptyList())
    private val overrides = MutableStateFlow<Map<String, Override>>(emptyMap())
    val artistImages = MutableStateFlow<Map<String, String>>(emptyMap())
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val recents = MutableStateFlow<List<String>>(emptyList())
    val playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    /** Songs the user hid from the library (files stay on the phone). */
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    val scanning = MutableStateFlow(false)
    val scannedOnce = MutableStateFlow(false)

    /** All songs with matched/edited info applied. */
    lateinit var songs: StateFlow<List<Song>>
        private set
    /** Hidden songs, for the "Hidden songs" list. */
    lateinit var hiddenSongs: StateFlow<List<Song>>
        private set
    lateinit var albums: StateFlow<List<Album>>
        private set
    lateinit var artists: StateFlow<List<Artist>>
        private set
    lateinit var folders: StateFlow<List<Folder>>
        private set

    val dataDir: File get() = app.filesDir
    val artDir: File get() = File(app.filesDir, "art").apply { mkdirs() }
    val lyricsDir: File get() = File(app.filesDir, "lyrics").apply { mkdirs() }

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        loadState()
        val all = combine(raw, overrides) { r, o -> r.map { merge(it, o[it.path]) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        songs = combine(all, hidden) { list, h -> if (h.isEmpty()) list else list.filter { it.path !in h } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        hiddenSongs = combine(all, hidden) { list, h -> list.filter { it.path in h }.sortedBy { it.title.lowercase() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        albums = songs.map { list ->
            list.groupBy { (it.album.lowercase() + "\u0000" + albumArtistOf(it).lowercase()) }
                .map { (k, s) -> Album(k, s.first().album, albumArtistOf(s.first()), s.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))) }
                .sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
        artists = combine(songs, artistImages) { list, imgs ->
            val map = LinkedHashMap<String, MutableList<Song>>()
            for (s in list) for (a in splitArtists(s.artist)) map.getOrPut(a) { mutableListOf() }.add(s)
            map.map { (name, s) -> Artist(name, s.sortedBy { it.title.lowercase() }, imgs[name.lowercase()]) }
                .sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
        folders = songs.map { list ->
            list.groupBy { it.folder }.map { (p, s) -> Folder(p, s.sortedBy { it.title.lowercase() }) }
                .sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        app.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { requestRescan() }
            })
    }

    // ------------------------------------------------------------------ settings
    var autoMatch: Boolean
        get() = prefs.getBoolean("auto_match", true)
        set(v) = prefs.edit().putBoolean("auto_match", v).apply()
    var autoLyrics: Boolean
        get() = prefs.getBoolean("auto_lyrics", true)
        set(v) = prefs.edit().putBoolean("auto_lyrics", v).apply()
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()
    var minDurationSec: Int
        get() = prefs.getInt("min_duration", 30)
        set(v) = prefs.edit().putInt("min_duration", v).apply()
    var songSort: SortOrder
        get() = runCatching { SortOrder.valueOf(prefs.getString("song_sort", SortOrder.ADDED.name)!!) }.getOrDefault(SortOrder.ADDED)
        set(v) = prefs.edit().putString("song_sort", v.name).apply()

    // ------------------------------------------------------------------ scanning
    private var rescanJob: Job? = null
    fun requestRescan() {
        rescanJob?.cancel()
        rescanJob = scope.launch { delay(1500); scan() }
    }

    suspend fun scan() = withContext(Dispatchers.IO) {
        scanning.value = true
        try {
            val list = queryMediaStore()
            raw.value = list
            scannedOnce.value = true
            Log.i(TAG, "scanned ${list.size} songs")
            Matcher.kick()
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to read music yet")
        } catch (e: Exception) {
            Log.e(TAG, "scan failed", e)
        } finally {
            scanning.value = false
        }
    }

    private fun queryMediaStore(): List<RawSong> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val modern = Build.VERSION.SDK_INT >= 30
        if (modern) { cols += "album_artist"; cols += "bitrate" }
        val minMs = minDurationSec * 1000L
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val out = ArrayList<RawSong>()
        app.contentResolver.query(uri, cols.toTypedArray(), sel, arrayOf(minMs.toString()), null)?.use { c ->
            fun str(name: String) = c.getColumnIndex(name).let { if (it >= 0) c.getString(it) else null }
            fun lng(name: String) = c.getColumnIndex(name).let { if (it >= 0) c.getLong(it) else 0L }
            while (c.moveToNext()) {
                val id = lng(MediaStore.Audio.Media._ID)
                val path = str(MediaStore.Audio.Media.DATA) ?: continue
                val display = str(MediaStore.Audio.Media.DISPLAY_NAME) ?: path.substringAfterLast('/')
                var title = str(MediaStore.Audio.Media.TITLE).orEmpty().trim()
                var artist = cleanTag(str(MediaStore.Audio.Media.ARTIST))
                val album = cleanTag(str(MediaStore.Audio.Media.ALBUM))
                // untagged files: MediaStore uses the file name as title. Try "Artist - Title".
                val base = display.substringBeforeLast('.')
                if (title.isEmpty() || title == base) {
                    val parsed = parseFileName(base)
                    title = parsed.second
                    if (artist.isEmpty() && parsed.first != null) artist = parsed.first!!
                }
                out += RawSong(
                    id = id,
                    uri = ContentUris.withAppendedId(uri, id),
                    path = path,
                    title = title.ifEmpty { base },
                    artist = artist,
                    album = album,
                    albumArtist = if (modern) cleanTag(str("album_artist")) else "",
                    durationMs = lng(MediaStore.Audio.Media.DURATION),
                    track = (lng(MediaStore.Audio.Media.TRACK) % 1000).toInt(),
                    year = lng(MediaStore.Audio.Media.YEAR).toInt(),
                    size = lng(MediaStore.Audio.Media.SIZE),
                    dateAdded = lng(MediaStore.Audio.Media.DATE_ADDED),
                    mime = str(MediaStore.Audio.Media.MIME_TYPE).orEmpty(),
                    bitrate = if (modern) lng("bitrate").toInt() else 0,
                )
            }
        }
        return out
    }

    private fun cleanTag(v: String?): String {
        val s = v?.trim().orEmpty()
        return if (s.isEmpty() || s == "<unknown>" || s.equals("unknown", true)) "" else s
    }

    /** "Artist - Title" (also "01 - Artist - Title", "01. Title"). */
    fun parseFileName(base: String): Pair<String?, String> {
        var s = base.replace('_', ' ').trim()
        s = s.replace(Regex("^\\d{1,3}\\s*[-.)]\\s*"), "")
        val parts = s.split(" - ", " – ", " — ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[0] to parts.drop(1).joinToString(" - ") else null to s
    }

    fun splitArtists(a: String): List<String> {
        if (a.isBlank()) return listOf(UNKNOWN_ARTIST)
        return a.split(Regex("\\s*(?:;|/|、|,| & | feat\\. | ft\\. )\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(a) }
    }

    private fun albumArtistOf(s: Song) = s.albumArtist.ifBlank { splitArtists(s.artist).first() }

    private fun merge(r: RawSong, o: Override?): Song {
        val art = o?.art?.takeIf { File(it).exists() }
        return Song(
            id = r.id, uri = r.uri, path = r.path,
            title = o?.title ?: r.title,
            artist = (o?.artist ?: r.artist).ifBlank { UNKNOWN_ARTIST },
            album = (o?.album ?: r.album).ifBlank { UNKNOWN_ALBUM },
            albumArtist = r.albumArtist,
            durationMs = r.durationMs, track = o?.track ?: r.track, year = o?.year ?: r.year,
            size = r.size, dateAdded = r.dateAdded, mime = r.mime, bitrate = r.bitrate,
            folder = r.path.substringBeforeLast('/', ""),
            artPath = art, matched = o?.matched == true,
            fileTitle = r.title, fileArtist = r.artist, fileAlbum = r.album,
        )
    }

    fun raws(): List<RawSong> = raw.value
    fun rawFor(path: String): RawSong? = raw.value.firstOrNull { it.path == path }
    fun song(path: String): Song? = songs.value.firstOrNull { it.path == path }
    fun songById(id: Long): Song? = songs.value.firstOrNull { it.id == id }
    fun override(path: String): Override? = overrides.value[path]

    // ------------------------------------------------------------------ edits
    fun setOverride(path: String, o: Override?) {
        overrides.value = overrides.value.toMutableMap().apply { if (o == null) remove(path) else put(path, o) }
        saveSoon()
    }

    fun updateOverride(path: String, f: (Override) -> Override) {
        val cur = overrides.value[path] ?: Override()
        setOverride(path, f(cur))
    }

    /** Back to the file's own tags (keeps the embedded artwork). */
    fun restore(path: String) {
        val cur = overrides.value[path] ?: return
        val emb = Art.embeddedFile(path)
        setOverride(path, if (emb.exists()) Override(art = emb.absolutePath) else null)
        Matcher.forget(path)
        Lyrics.forget(path)
        if (cur.art != null && cur.art != emb.absolutePath) runCatching { File(cur.art).delete() }
    }

    fun setArtistImage(name: String, path: String?) {
        artistImages.value = artistImages.value.toMutableMap().apply { if (path == null) remove(name.lowercase()) else put(name.lowercase(), path) }
        saveSoon()
    }

    fun toggleFavorite(path: String) {
        favorites.value = favorites.value.toMutableSet().apply { if (!add(path)) remove(path) }
        saveSoon()
    }

    fun hide(paths: List<String>) {
        hidden.value = hidden.value + paths
        saveSoon()
    }

    fun unhide(paths: List<String>) {
        hidden.value = hidden.value - paths.toSet()
        saveSoon()
    }

    /** Forget everything about a song whose file was deleted. */
    fun removeDeleted(path: String) {
        raw.value = raw.value.filter { it.path != path }
        overrides.value[path]?.art?.let { runCatching { File(it).delete() } }
        overrides.value = overrides.value - path
        favorites.value = favorites.value - path
        hidden.value = hidden.value - path
        recents.value = recents.value - path
        playCounts.value = playCounts.value - path
        playlists.value = playlists.value.map { it.copy(paths = it.paths - path) }
        Lyrics.forget(path)
        saveSoon()
    }

    fun addFavorites(paths: List<String>) {
        favorites.value = favorites.value + paths
        saveSoon()
    }

    fun recordPlay(path: String) {
        recents.value = (listOf(path) + recents.value.filter { it != path }).take(200)
        playCounts.value = playCounts.value.toMutableMap().apply { put(path, (get(path) ?: 0) + 1) }
        saveSoon()
    }

    fun createPlaylist(name: String, paths: List<String> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, paths)
        playlists.value = playlists.value + p
        saveSoon()
        return p
    }

    fun addToPlaylist(id: String, paths: List<String>) {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(paths = (it.paths + paths).distinct()) else it }
        saveSoon()
    }

    fun removeFromPlaylist(id: String, path: String) {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(paths = it.paths - path) else it }
        saveSoon()
    }

    fun renamePlaylist(id: String, name: String) {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(name = name) else it }
        saveSoon()
    }

    fun deletePlaylist(id: String) {
        playlists.value = playlists.value.filter { it.id != id }
        saveSoon()
    }

    // ------------------------------------------------------------------ persistence
    private val stateFile get() = File(app.filesDir, "library.json")
    private var saveJob: Job? = null

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) { delay(400); save() }
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
        o.put("overrides", JSONObject().apply {
            for ((k, v) in overrides.value) put(k, JSONObject().apply {
                v.title?.let { put("title", it) }; v.artist?.let { put("artist", it) }
                v.album?.let { put("album", it) }; v.track?.let { put("track", it) }
                v.year?.let { put("year", it) }; v.art?.let { put("art", it) }
                put("matched", v.matched)
            })
        })
        o.put("artistImages", JSONObject(artistImages.value as Map<*, *>))
        o.put("favorites", JSONArray(favorites.value.toList()))
        o.put("recents", JSONArray(recents.value))
        o.put("playCounts", JSONObject(playCounts.value as Map<*, *>))
        o.put("hidden", JSONArray(hidden.value.toList()))
        o.put("playlists", JSONArray().apply {
            for (p in playlists.value) put(JSONObject().put("id", p.id).put("name", p.name).put("paths", JSONArray(p.paths)))
        })
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile)
    }

    private fun loadState() {
        val o = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return
        fun JSONObject.optStr(k: String) = if (has(k) && !isNull(k)) getString(k) else null
        fun JSONObject.optIntOrNull(k: String) = if (has(k) && !isNull(k)) getInt(k) else null
        o.optJSONObject("overrides")?.let { ov ->
            val m = HashMap<String, Override>()
            for (k in ov.keys()) {
                val v = ov.getJSONObject(k)
                m[k] = Override(v.optStr("title"), v.optStr("artist"), v.optStr("album"), v.optIntOrNull("track"),
                    v.optIntOrNull("year"), v.optStr("art"), v.optBoolean("matched"))
            }
            overrides.value = m
        }
        o.optJSONObject("artistImages")?.let { a -> artistImages.value = a.keys().asSequence().associateWith { a.getString(it) } }
        o.optJSONArray("hidden")?.let { a -> hidden.value = (0 until a.length()).map { a.getString(it) }.toSet() }
        o.optJSONArray("favorites")?.let { a -> favorites.value = (0 until a.length()).map { a.getString(it) }.toSet() }
        o.optJSONArray("recents")?.let { a -> recents.value = (0 until a.length()).map { a.getString(it) } }
        o.optJSONObject("playCounts")?.let { a -> playCounts.value = a.keys().asSequence().associateWith { a.getInt(it) } }
        o.optJSONArray("playlists")?.let { a ->
            playlists.value = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val ps = p.getJSONArray("paths")
                Playlist(p.getString("id"), p.getString("name"), (0 until ps.length()).map { ps.getString(it) })
            }
        }
    }

    // ------------------------------------------------------------------ helpers for lists
    fun sorted(list: List<Song>, order: SortOrder): List<Song> = when (order) {
        SortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
        SortOrder.ARTIST -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        SortOrder.ALBUM -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.track }))
        SortOrder.ADDED -> list.sortedByDescending { it.dateAdded }
        SortOrder.DURATION -> list.sortedByDescending { it.durationMs }
    }

    fun songsFor(paths: List<String>): List<Song> {
        val byPath = songs.value.associateBy { it.path }
        return paths.mapNotNull { byPath[it] }
    }
}
