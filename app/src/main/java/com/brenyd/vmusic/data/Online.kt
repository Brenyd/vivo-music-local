package com.brenyd.vmusic.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Free, key-less online sources:
 *  - Deezer API: track info, album covers (1000px), artist photos
 *  - iTunes Search API: track info + covers (fallback, and better for some regions)
 *  - LRCLIB: synced lyrics
 */
object Online {
    private const val TAG = "Online"
    private const val UA = "vivo-music-local/1.0 (https://github.com/Brenyd/vivo-music-local)"

    data class Candidate(
        val title: String,
        val artist: String,
        val album: String,
        val track: Int?,
        val year: Int?,
        val coverUrl: String?,
        val durationMs: Long?,
        val source: String,
        val deezerId: Long? = null,
        val score: Double = 0.0,
    )

    // ------------------------------------------------------------------ network
    fun networkOk(): Boolean {
        val cm = Library.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (Library.wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun get(url: String): String? = bytes(url)?.toString(Charsets.UTF_8)

    fun bytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.d(TAG, "GET failed $url: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------ string matching
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKC)
        t = t.replace(Regex("[(\\[（【].*?[)\\]）】]"), " ")
        t = t.replace(Regex("\\b(feat|ft|featuring)\\.?\\s.*$"), " ")
        t = t.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return t
    }

    fun similarity(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85 * min(x.length, y.length) / max(x.length, y.length) + 0.15
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / max(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun artistSim(a: String, b: String): Double {
        if (a.isBlank() || a == UNKNOWN_ARTIST) return 0.5
        val parts = Library.splitArtists(a)
        return maxOf(similarity(a, b), parts.maxOf { similarity(it, b) })
    }

    private fun score(c: Candidate, title: String, artist: String, durationMs: Long): Candidate {
        val ts = similarity(title, c.title)
        val ars = artistSim(artist, c.artist)
        val ds = when {
            c.durationMs == null || durationMs <= 0 -> 0.5
            abs(c.durationMs - durationMs) < 3000 -> 1.0
            abs(c.durationMs - durationMs) < 10000 -> 0.6
            else -> 0.0
        }
        return c.copy(score = 0.55 * ts + 0.35 * ars + 0.10 * ds)
    }

    /** Good enough to apply automatically, without asking. */
    fun confident(c: Candidate, title: String, artist: String, durationMs: Long): Boolean {
        val ts = similarity(title, c.title)
        val ars = artistSim(artist, c.artist)
        val durOk = c.durationMs == null || durationMs <= 0 || abs(c.durationMs - durationMs) < 15000
        val artistKnown = artist.isNotBlank() && artist != UNKNOWN_ARTIST
        return durOk && ts >= 0.85 && (if (artistKnown) ars >= 0.7 else (c.durationMs != null && abs(c.durationMs - durationMs) < 4000))
    }

    private fun hasCjk(s: String) = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    // ------------------------------------------------------------------ track search
    fun searchTrack(title: String, artist: String, durationMs: Long): List<Candidate> {
        val out = ArrayList<Candidate>()
        runCatching { out += deezer(title, artist) }
        runCatching { out += itunes(title, artist) }
        return out.map { score(it, title, artist, durationMs) }
            .distinctBy { norm(it.title) + "|" + norm(it.artist) + "|" + norm(it.album) + "|" + it.source }
            .sortedByDescending { it.score }
    }

    private fun deezer(title: String, artist: String): List<Candidate> {
        val known = artist.isNotBlank() && artist != UNKNOWN_ARTIST
        val q = if (known) "artist:\"${Library.splitArtists(artist).first()}\" track:\"$title\"" else title
        var arr = get("https://api.deezer.com/search?limit=10&q=${enc(q)}")?.let { JSONObject(it).optJSONArray("data") }
        if ((arr == null || arr.length() == 0) && known) {
            arr = get("https://api.deezer.com/search?limit=10&q=${enc("$artist $title")}")?.let { JSONObject(it).optJSONArray("data") }
        }
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val al = t.optJSONObject("album")
            Candidate(
                title = t.optString("title"),
                artist = t.optJSONObject("artist")?.optString("name").orEmpty(),
                album = al?.optString("title").orEmpty(),
                track = null, year = null,
                coverUrl = al?.optString("cover_xl")?.takeIf { it.isNotBlank() && it != "null" },
                durationMs = t.optLong("duration", 0).takeIf { it > 0 }?.times(1000),
                source = "Deezer",
                deezerId = t.optLong("id").takeIf { it > 0 },
            )
        }
    }

    private fun itunes(title: String, artist: String): List<Candidate> {
        val known = artist.isNotBlank() && artist != UNKNOWN_ARTIST
        val term = if (known) "${Library.splitArtists(artist).first()} $title" else title
        val country = if (hasCjk(title) || hasCjk(artist)) "&country=CN" else ""
        val arr = get("https://itunes.apple.com/search?entity=song&limit=10$country&term=${enc(term)}")
            ?.let { JSONObject(it).optJSONArray("results") } ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Candidate(
                title = t.optString("trackName"),
                artist = t.optString("artistName"),
                album = t.optString("collectionName"),
                track = t.optInt("trackNumber", 0).takeIf { it > 0 },
                year = t.optString("releaseDate").take(4).toIntOrNull(),
                coverUrl = t.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100bb", "1000x1000bb"),
                durationMs = t.optLong("trackTimeMillis", 0).takeIf { it > 0 },
                source = "iTunes",
            )
        }
    }

    /** Fill in track number / year for a Deezer result. */
    fun enrich(c: Candidate): Candidate {
        if (c.deezerId == null || (c.track != null && c.year != null)) return c
        val t = get("https://api.deezer.com/track/${c.deezerId}")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return c
        return c.copy(
            track = c.track ?: t.optInt("track_position", 0).takeIf { it > 0 },
            year = c.year ?: t.optString("release_date").take(4).toIntOrNull(),
        )
    }

    // ------------------------------------------------------------------ artist photos
    fun artistImageUrl(name: String): String? {
        val arr = get("https://api.deezer.com/search/artist?limit=5&q=${enc(name)}")
            ?.let { JSONObject(it).optJSONArray("data") } ?: return null
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (similarity(name, a.optString("name")) < 0.9) continue
            val pic = a.optString("picture_xl")
            // Deezer's placeholder for artists without a photo has an empty hash: ".../artist//1000x1000-..."
            if (pic.isBlank() || pic.contains("/artist//")) continue
            return pic
        }
        return null
    }

    // ------------------------------------------------------------------ lyrics (LRCLIB)
    fun lyrics(title: String, artist: String, album: String, durationMs: Long): String? {
        val known = artist.isNotBlank() && artist != UNKNOWN_ARTIST
        val dur = durationMs / 1000
        if (known) {
            val q = "track_name=${enc(title)}&artist_name=${enc(artist)}" +
                (if (album.isNotBlank() && album != UNKNOWN_ALBUM) "&album_name=${enc(album)}" else "") + "&duration=$dur"
            get("https://lrclib.net/api/get?$q")?.let { body ->
                val o = runCatching { JSONObject(body) }.getOrNull()
                if (o != null) pick(o)?.let { return it }
            }
        }
        val q2 = "track_name=${enc(title)}" + if (known) "&artist_name=${enc(Library.splitArtists(artist).first())}" else ""
        val arr = get("https://lrclib.net/api/search?$q2")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { similarity(title, it.optString("trackName")) >= 0.8 }
            .filter { !known || artistSim(artist, it.optString("artistName")) >= 0.6 }
            .sortedWith(compareBy<JSONObject>({ if (it.optString("syncedLyrics").isNullOrBlank() || it.isNull("syncedLyrics")) 1 else 0 },
                { abs(it.optDouble("duration", 0.0) - dur) }))
        val best = items.firstOrNull() ?: return null
        if (abs(best.optDouble("duration", dur.toDouble()) - dur) > 20) return null
        return pick(best)
    }

    private fun pick(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return "[00:00.00]♪ Instrumental ♪"
        val synced = if (o.isNull("syncedLyrics")) null else o.optString("syncedLyrics")
        if (!synced.isNullOrBlank()) return synced
        val plain = if (o.isNull("plainLyrics")) null else o.optString("plainLyrics")
        return plain?.takeIf { it.isNotBlank() }
    }
}
