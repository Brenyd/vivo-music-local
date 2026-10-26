package com.brenyd.vmusic.data

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Xml
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** A music server or network share the user added. */
data class Server(
    val id: String,
    val type: String,      // "subsonic" | "webdav" | "smb"
    val name: String,
    val url: String,       // https://music.example.com  ·  https://nas/dav/Music  ·  smb://nas/Music
    val user: String,
    val pass: String,
) {
    val typeLabel get() = when (type) { "subsonic" -> "Subsonic / Navidrome"; "webdav" -> "WebDAV"; else -> "SMB share" }
}

/** One entry when browsing a server: a folder/album/artist, or a playable song. */
data class RemoteEntry(
    val id: String,          // browse id (for folders) or song path
    val title: String,
    val subtitle: String,
    val artUrl: String?,
    val song: Song?,         // non-null for playable entries
    val kind: String = if (song != null) "song" else "folder",
)

object Servers {
    val list = MutableStateFlow<List<Server>>(emptyList())

    fun load() {
        val a = runCatching { JSONArray(Library.prefs.getString("servers", "[]")) }.getOrNull() ?: return
        list.value = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Server(o.getString("id"), o.getString("type"), o.getString("name"), o.getString("url"), o.optString("user"), o.optString("pass"))
        }
    }

    private fun save() {
        val a = JSONArray()
        list.value.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("url", it.url).put("user", it.user).put("pass", it.pass)) }
        Library.prefs.edit().putString("servers", a.toString()).apply()
    }

    fun add(type: String, name: String, url: String, user: String, pass: String): Server {
        val s = Server(UUID.randomUUID().toString().take(8), type, name.ifBlank { URI(url).host ?: url }, url.trimEnd('/'), user, pass)
        list.value = list.value + s
        save()
        return s
    }

    fun remove(id: String) { list.value = list.value.filter { it.id != id }; save() }
    fun get(id: String) = list.value.firstOrNull { it.id == id }

    /** HTTP Basic auth for WebDAV URLs (Subsonic carries its auth in the URL). */
    fun authHeaderFor(uri: Uri): String? {
        val u = uri.toString()
        val s = list.value.firstOrNull { it.type == "webdav" && u.startsWith(it.url) && it.user.isNotEmpty() } ?: return null
        return "Basic " + Base64.encodeToString("${s.user}:${s.pass}".toByteArray(), Base64.NO_WRAP)
    }

    fun smbContext(host: String): CIFSContext {
        val s = list.value.firstOrNull { it.type == "smb" && (runCatching { URI(it.url).host }.getOrNull() ?: "").equals(host, true) }
        val base = SingletonContext.getInstance()
        if (s == null || s.user.isEmpty()) return base.withAnonymousCredentials()
        val (domain, user) = if (s.user.contains('\\')) s.user.substringBefore('\\') to s.user.substringAfter('\\') else "" to s.user
        return base.withCredentials(NtlmPasswordAuthenticator(domain, user, s.pass))
    }
}

/** Songs from servers that were browsed or played, so the player can look them up by path. */
object Remote {
    val songs = MutableStateFlow<Map<String, Song>>(emptyMap())

    fun isRemote(path: String) = path.startsWith("subsonic://") || path.startsWith("webdav://") || path.startsWith("smb://")
    fun get(path: String): Song? = songs.value[path]
    fun register(list: List<Song>) { if (list.isNotEmpty()) songs.value = songs.value + list.associateBy { it.path } }
    fun update(path: String, f: (Song) -> Song) { songs.value[path]?.let { songs.value = songs.value + (path to f(it)) } }

    fun remoteSong(path: String, uri: Uri, title: String, artist: String, album: String, durationMs: Long, track: Int,
                   year: Int, size: Long, artUrl: String?, quality: AudioQuality?, folder: String): Song = Song(
        id = path.hashCode().toLong(), uri = uri, path = path, title = title,
        artist = artist.ifBlank { UNKNOWN_ARTIST }, album = album.ifBlank { UNKNOWN_ALBUM }, albumArtist = "",
        durationMs = durationMs, track = track, year = year, size = size, dateAdded = 0, mime = "",
        bitrate = (quality?.kbps ?: 0) * 1000, folder = folder, artPath = null, matched = false,
        fileTitle = title, fileArtist = artist, fileAlbum = album, quality = quality, artUrl = artUrl,
    )

    // ------------------------------------------------------------------ browsing
    suspend fun browse(server: Server, id: String?): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val r = when (server.type) {
            "subsonic" -> Subsonic(server).browse(id)
            "webdav" -> WebDav(server).list(id)
            else -> Smb(server).list(id)
        }
        register(r.mapNotNull { it.song })
        r
    }

    suspend fun search(server: Server, q: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val r = if (server.type == "subsonic") Subsonic(server).search(q) else emptyList()
        register(r.mapNotNull { it.song })
        r
    }

    suspend fun test(server: Server): String? = withContext(Dispatchers.IO) {
        try {
            when (server.type) {
                "subsonic" -> Subsonic(server).ping()
                "webdav" -> WebDav(server).list(null)
                else -> Smb(server).list(null)
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------ save to phone
    /** Copy a song from your server to Music/vMusic on the phone. */
    suspend fun saveToPhone(s: Song): Boolean = withContext(Dispatchers.IO) {
        val name = (s.uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
            ?: "${s.artist} - ${s.title}.${s.quality?.format?.lowercase(Locale.US) ?: "mp3"}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val input: InputStream = openStream(s) ?: return@withContext false
        input.use { src ->
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/vMusic")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val cr = Library.app.contentResolver
                val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: return@withContext false
                cr.openOutputStream(uri)?.use { src.copyTo(it, 256 * 1024) } ?: return@withContext false
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "vMusic").apply { mkdirs() }
                File(dir, name).outputStream().use { src.copyTo(it, 256 * 1024) }
                android.media.MediaScannerConnection.scanFile(Library.app, arrayOf(File(dir, name).path), null, null)
            }
        }
        Library.requestRescan()
        true
    }

    private fun openStream(s: Song): InputStream? = when {
        s.path.startsWith("smb://") -> SmbFile(s.uri.toString(), Servers.smbContext(s.uri.host ?: "")).openInputStream()
        s.path.startsWith("subsonic://") -> {
            val srv = Servers.get(s.path.removePrefix("subsonic://").substringBefore('/'))
            val id = s.path.substringAfterLast('/')
            srv?.let { URL(Subsonic(it).url("download", "id" to id)).openStream() }
        }
        else -> {
            val c = URL(s.uri.toString()).openConnection() as HttpURLConnection
            Servers.authHeaderFor(s.uri)?.let { c.setRequestProperty("Authorization", it) }
            c.inputStream
        }
    }

    private fun audioExt(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in
        setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "alac", "wma", "ape", "wv")

    private fun qualityFromExt(name: String, kbps: Int = 0): AudioQuality? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val lossless = ext in setOf("flac", "wav", "aiff", "aif", "alac", "ape", "wv")
        if (ext.isEmpty()) return null
        return AudioQuality(ext.uppercase(Locale.US), lossless, if (lossless) 16 else 0, 0, kbps)
    }

    private fun fromFileName(path: String, uri: Uri, name: String, size: Long, folder: String): Song {
        val (artist, title) = Library.parseFileName(name.substringBeforeLast('.'))
        return remoteSong(path, uri, title, artist ?: "", "", 0, 0, 0, size, null, qualityFromExt(name), folder)
    }

    // ------------------------------------------------------------------ Subsonic / Navidrome
    private class Subsonic(val s: Server) {
        fun url(method: String, vararg params: Pair<String, String>): String {
            val salt = UUID.randomUUID().toString().replace("-", "").take(12)
            val token = MessageDigest.getInstance("MD5").digest((s.pass + salt).toByteArray()).joinToString("") { "%02x".format(it) }
            val q = (listOf("u" to s.user, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "vmusic", "f" to "json") + params)
                .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
            return "${s.url}/rest/$method.view?$q"
        }

        private fun call(method: String, vararg params: Pair<String, String>): JSONObject {
            val body = Online.get(url(method, *params)) ?: throw IllegalStateException("Server didn't answer")
            val r = JSONObject(body).getJSONObject("subsonic-response")
            if (r.optString("status") != "ok") throw IllegalStateException(r.optJSONObject("error")?.optString("message") ?: "Login failed")
            return r
        }

        fun ping() { call("ping") }

        fun cover(id: String?) = id?.takeIf { it.isNotBlank() }?.let { url("getCoverArt", "id" to it, "size" to "600") }

        private fun song(o: JSONObject): Song {
            val id = o.getString("id")
            val bits = o.optInt("bitDepth", 0)
            val rate = o.optInt("samplingRate", 0)
            val suffix = o.optString("suffix").uppercase(Locale.US)
            val lossless = suffix in setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "WV")
            val q = if (suffix.isNotEmpty()) AudioQuality(suffix, lossless, if (lossless) (if (bits > 0) bits else 16) else 0, rate, o.optInt("bitRate", 0)) else null
            return remoteSong(
                path = "subsonic://${s.id}/$id",
                uri = Uri.parse(url("stream", "id" to id, "format" to "raw")),
                title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
                durationMs = o.optLong("duration", 0) * 1000, track = o.optInt("track", 0), year = o.optInt("year", 0),
                size = o.optLong("size", 0), artUrl = cover(o.optString("coverArt")), quality = q, folder = s.name,
            )
        }

        private fun album(o: JSONObject) = RemoteEntry("album:" + o.getString("id"), o.optString("name", o.optString("title")),
            listOf(o.optString("artist"), o.optInt("year", 0).takeIf { it > 0 }?.toString()).filter { !it.isNullOrBlank() }.joinToString(" · "),
            cover(o.optString("coverArt")), null, "album")

        private fun arr(o: JSONObject?, key: String): List<JSONObject> {
            val a = o?.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }
        }

        fun browse(id: String?): List<RemoteEntry> = when {
            id == null -> listOf(
                RemoteEntry("list:newest", "Recently added", "Albums", null, null, "folder"),
                RemoteEntry("list:random", "Random albums", "Albums", null, null, "folder"),
                RemoteEntry("list:frequent", "Most played", "Albums", null, null, "folder"),
                RemoteEntry("list:starred", "Starred", "Albums", null, null, "folder"),
                RemoteEntry("artists", "Artists", "All artists", null, null, "folder"),
                RemoteEntry("songs:random", "Shuffle 100 songs", "Random songs", null, null, "folder"),
            )
            id == "artists" -> arr(call("getArtists").optJSONObject("artists"), "index").flatMap { arr(it, "artist") }.map {
                RemoteEntry("artist:" + it.getString("id"), it.optString("name"), "${it.optInt("albumCount")} albums",
                    it.optString("artistImageUrl").takeIf { u -> u.startsWith("http") } ?: cover(it.optString("coverArt")), null, "artist")
            }
            id.startsWith("list:") -> arr(call("getAlbumList2", "type" to id.removePrefix("list:"), "size" to "100").optJSONObject("albumList2"), "album").map { album(it) }
            id.startsWith("artist:") -> arr(call("getArtist", "id" to id.removePrefix("artist:")).optJSONObject("artist"), "album").map { album(it) }
            id.startsWith("album:") -> arr(call("getAlbum", "id" to id.removePrefix("album:")).optJSONObject("album"), "song").map { songEntry(it) }
            id == "songs:random" -> arr(call("getRandomSongs", "size" to "100").optJSONObject("randomSongs"), "song").map { songEntry(it) }
            else -> emptyList()
        }

        private fun songEntry(o: JSONObject): RemoteEntry {
            val sg = song(o)
            return RemoteEntry(sg.path, sg.title, "${sg.artist} - ${sg.album}", sg.artUrl, sg)
        }

        fun search(q: String): List<RemoteEntry> {
            val r = call("search3", "query" to q, "songCount" to "50", "albumCount" to "20", "artistCount" to "10").optJSONObject("searchResult3")
            return arr(r, "artist").map { RemoteEntry("artist:" + it.getString("id"), it.optString("name"), "Artist", cover(it.optString("coverArt")), null, "artist") } +
                arr(r, "album").map { album(it) } + arr(r, "song").map { songEntry(it) }
        }
    }

    // ------------------------------------------------------------------ WebDAV
    private class WebDav(val s: Server) {
        fun list(dir: String?): List<RemoteEntry> {
            val target = dir ?: (s.url + "/")
            val c = URL(target).openConnection() as HttpURLConnection
            c.requestMethod = "PROPFIND"
            c.doOutput = true
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("Depth", "1")
            c.setRequestProperty("Content-Type", "application/xml")
            if (s.user.isNotEmpty()) c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${s.user}:${s.pass}".toByteArray(), Base64.NO_WRAP))
            c.outputStream.use { it.write("<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:displayname/></d:prop></d:propfind>".toByteArray()) }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val base = URI(target)
            val out = ArrayList<RemoteEntry>()
            c.inputStream.use { stream ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                p.setInput(stream, null)
                var href = ""; var isDir = false; var len = 0L; var tag = ""
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> {
                            tag = p.name
                            if (tag == "response") { href = ""; isDir = false; len = 0 }
                            if (tag == "collection") isDir = true
                        }
                        XmlPullParser.TEXT -> when (tag) {
                            "href" -> href += p.text.trim()
                            "getcontentlength" -> len = p.text.trim().toLongOrNull() ?: 0
                        }
                        XmlPullParser.END_TAG -> {
                            if (p.name == "response" && href.isNotEmpty()) {
                                val abs = base.resolve(href).toString()
                                if (abs.trimEnd('/') != target.trimEnd('/')) {
                                    val name = URLDecoder.decode(abs.trimEnd('/').substringAfterLast('/'), "UTF-8")
                                    if (isDir) out += RemoteEntry(abs, name, "Folder", null, null, "folder")
                                    else if (audioExt(name)) {
                                        val sg = fromFileName("webdav://${s.id}" + URI(abs).rawPath, Uri.parse(abs), name, len, URLDecoder.decode(base.rawPath, "UTF-8"))
                                        out += RemoteEntry(sg.path, sg.title, sg.artist, null, sg)
                                    }
                                }
                            }
                            tag = ""
                        }
                    }
                }
            }
            return out.sortedWith(compareBy({ it.song != null }, { it.title.lowercase() }))
        }
    }

    // ------------------------------------------------------------------ SMB
    private class Smb(val s: Server) {
        fun list(dir: String?): List<RemoteEntry> {
            val target = (dir ?: s.url).trimEnd('/') + "/"
            val host = URI(target).host ?: ""
            val f = SmbFile(target, Servers.smbContext(host))
            return f.listFiles().mapNotNull { e ->
                val name = e.name.trimEnd('/')
                if (name.startsWith(".")) null
                else if (e.isDirectory) RemoteEntry(e.url.toString(), name, "Folder", null, null, "folder")
                else if (audioExt(name)) {
                    val sg = fromFileName(e.url.toString(), Uri.parse(e.url.toString()), name, e.length(), target)
                    RemoteEntry(sg.path, sg.title, sg.artist, null, sg)
                } else null
            }.sortedWith(compareBy({ it.song != null }, { it.title.lowercase() }))
        }
    }
}
