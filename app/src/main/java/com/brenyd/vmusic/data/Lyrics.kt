package com.brenyd.vmusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Lyrics {
    data class Line(val timeMs: Long, val text: String)
    data class Doc(val lines: List<Line>, val synced: Boolean, val raw: String) {
        /** Index of the line being sung at [posMs] (-1 before the first line). */
        fun indexAt(posMs: Long): Int {
            if (!synced) return -1
            var lo = 0
            var hi = lines.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }
    }

    private fun file(path: String) = File(Library.lyricsDir, Art.key(path) + ".lrc")
    private fun noneMarker(path: String) = File(Library.lyricsDir, Art.key(path) + ".none")

    fun cached(path: String): Doc? = file(path).takeIf { it.exists() }?.readText()?.let { parse(it) }

    fun hasCached(path: String) = file(path).exists()

    /** Cached lyrics, or fetch them from LRCLIB when allowed. */
    suspend fun load(song: Song, fetch: Boolean = Library.autoLyrics): Doc? = withContext(Dispatchers.IO) {
        cached(song.path)?.let { return@withContext it }
        if (!fetch) return@withContext null
        val marker = noneMarker(song.path)
        if (marker.exists() && System.currentTimeMillis() - marker.lastModified() < 7L * 24 * 3600 * 1000) return@withContext null
        if (!Online.networkOk()) return@withContext null
        val text = Online.lyrics(song.title, song.artist, song.album, song.durationMs)
        if (text == null) {
            marker.writeText("")
            return@withContext null
        }
        file(song.path).writeText(text)
        parse(text)
    }

    /** Search again, ignoring the "not found" marker. */
    suspend fun refetch(song: Song): Doc? {
        noneMarker(song.path).delete()
        file(song.path).delete()
        return load(song, fetch = true)
    }

    fun save(path: String, text: String?) {
        if (text.isNullOrBlank()) {
            file(path).delete()
            noneMarker(path).writeText("")
        } else {
            file(path).writeText(text)
            noneMarker(path).delete()
        }
    }

    fun forget(path: String) {
        file(path).delete()
        noneMarker(path).delete()
    }

    private val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(text: String): Doc {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = ArrayList<Line>()
        var synced = false
        for (rawLine in text.lines()) {
            val matches = tag.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                val t = rawLine.trim()
                if (t.isNotEmpty() && !t.startsWith("[")) lines += Line(-1, t)
                continue
            }
            synced = true
            val content = rawLine.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                lines += Line(maxOf(0L, min * 60000 + sec * 1000 + frac - offset), content)
            }
        }
        return if (synced) {
            Doc(lines.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }, true, text)
        } else Doc(lines, false, text)
    }
}
