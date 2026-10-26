package com.brenyd.vmusic.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Background worker that gives every song a cover and complete info, like vivo Music's "song matched":
 *  1. extracts covers embedded in the files
 *  2. for songs still missing a cover, artist or album, looks them up online and applies confident matches
 *  3. fetches artist photos
 * Matches are stored by the app; the music files are never modified.
 */
object Matcher {
    private const val TAG = "Matcher"
    private const val RETRY_MS = 14L * 24 * 3600 * 1000

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")

    private var job: Job? = null
    private val stateFile get() = File(Library.dataDir, "matcher.json")
    private val embeddedChecked = HashSet<String>()
    private val onlineTried = HashMap<String, Long>()
    private val artistTried = HashMap<String, Long>()
    private var loaded = false

    fun kick(force: Boolean = false) {
        if (job?.isActive == true && !force) return
        job?.cancel()
        job = Library.scope.launch(Dispatchers.IO) {
            running.value = true
            try { run(force) } catch (e: Exception) { Log.e(TAG, "matcher failed", e) } finally {
                running.value = false
                status.value = ""
                save()
            }
        }
    }

    fun forget(path: String) {
        synchronized(this) { onlineTried.remove(path) }
    }

    private suspend fun run(force: Boolean) {
        load()
        val raws = Library.raws()
        // 0) real audio quality (format, bit depth, sample rate, bitrate)
        val known = Library.quality.value
        val batch = HashMap<String, AudioQuality>()
        for ((i, r) in raws.withIndex()) {
            val k = Library.qualityKey(r)
            if (k in known) continue
            AudioQuality.probe(r)?.let { batch[k] = it }
            if (batch.size >= 25) { Library.setQuality(HashMap(batch)); batch.clear(); status.value = "Reading audio quality… ${i + 1}/${raws.size}" }
        }
        Library.setQuality(batch)
        // 1) embedded covers (fast, offline)
        var n = 0
        for (r in raws) {
            if (r.path in embeddedChecked) continue
            val cur = Library.override(r.path)
            if (cur?.art == null) {
                Art.extractEmbedded(r)?.let { f -> Library.updateOverride(r.path) { o -> if (o.art == null) o.copy(art = f.absolutePath) else o } }
            }
            synchronized(this) { embeddedChecked += r.path }
            if (++n % 25 == 0) { status.value = "Reading covers… $n/${raws.size}"; save() }
        }
        if (!Library.autoMatch) return

        // 2) online matching for incomplete songs
        val now = System.currentTimeMillis()
        val todo = Library.songs.value.filter { needsMatch(it) && (force || now - (onlineTried[it.path] ?: 0) > RETRY_MS) }
        for ((i, s) in todo.withIndex()) {
            if (!Online.networkOk()) break
            status.value = "Matching song info… ${i + 1}/${todo.size}"
            runCatching { matchOne(s) }.onFailure { Log.w(TAG, "match failed for ${s.path}", it) }
            synchronized(this) { onlineTried[s.path] = System.currentTimeMillis() }
            if (i % 10 == 9) save()
            delay(300)
        }

        // 3) artist photos
        val artists = Library.artists.value.filter { it.imagePath == null && it.name != UNKNOWN_ARTIST }
            .filter { force || now - (artistTried[it.name.lowercase()] ?: 0) > RETRY_MS }
        for ((i, a) in artists.withIndex()) {
            if (!Online.networkOk()) break
            status.value = "Getting artist photos… ${i + 1}/${artists.size}"
            runCatching { fetchArtistImage(a.name) }
            synchronized(this) { artistTried[a.name.lowercase()] = System.currentTimeMillis() }
            delay(250)
        }
    }

    fun needsMatch(s: Song) = !s.matched && (s.artPath == null || s.artist == UNKNOWN_ARTIST || s.album == UNKNOWN_ALBUM)

    /** Auto-match one song. Only fills in what is missing. */
    private fun matchOne(s: Song): Boolean {
        val cands = Online.searchTrack(s.title, s.artist, s.durationMs)
        val best = cands.firstOrNull { Online.confident(it, s.title, s.artist, s.durationMs) } ?: return false
        apply(s, best, fillOnly = true)
        return true
    }

    /** Apply a candidate. [fillOnly] keeps existing good tags (automatic matching); otherwise everything is replaced. */
    fun apply(s: Song, cand: Online.Candidate, fillOnly: Boolean) {
        val c = runCatching { Online.enrich(cand) }.getOrDefault(cand)
        var artFile: String? = null
        if (!fillOnly || s.artPath == null) {
            c.coverUrl?.let { url ->
                Online.bytes(url)?.let { bytes ->
                    val f = Art.matchedFile(s.path)
                    if (Art.saveImage(bytes, f)) artFile = f.absolutePath
                }
            }
        }
        Library.updateOverride(s.path) { o ->
            if (fillOnly) o.copy(
                artist = if (s.artist == UNKNOWN_ARTIST) c.artist else o.artist,
                album = if (s.album == UNKNOWN_ALBUM) c.album.ifBlank { null } else o.album,
                track = if (s.track <= 0) c.track else o.track,
                year = if (s.year <= 0) c.year else o.year,
                art = artFile ?: o.art,
                matched = true,
            ) else o.copy(
                title = c.title, artist = c.artist, album = c.album.ifBlank { null },
                track = c.track ?: o.track, year = c.year ?: o.year,
                art = artFile ?: o.art, matched = true,
            )
        }
        Lyrics.forget(s.path)
    }

    suspend fun fetchArtistImage(name: String): Boolean = withContext(Dispatchers.IO) {
        val url = Online.artistImageUrl(name) ?: return@withContext false
        val bytes = Online.bytes(url) ?: return@withContext false
        val f = Art.artistFile(name)
        if (!Art.saveImage(bytes, f)) return@withContext false
        Library.setArtistImage(name, f.absolutePath)
        true
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val o = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return
        o.optJSONArray("embedded")?.let { a -> for (i in 0 until a.length()) embeddedChecked += a.getString(i) }
        o.optJSONObject("online")?.let { m -> for (k in m.keys()) onlineTried[k] = m.getLong(k) }
        o.optJSONObject("artists")?.let { m -> for (k in m.keys()) artistTried[k] = m.getLong(k) }
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
        o.put("embedded", org.json.JSONArray(embeddedChecked.toList()))
        o.put("online", JSONObject(onlineTried as Map<*, *>))
        o.put("artists", JSONObject(artistTried as Map<*, *>))
        runCatching { stateFile.writeText(o.toString()) }
    }
}
