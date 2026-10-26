package com.brenyd.vmusic.data

import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Real audio quality of a file, read from the file itself (not guessed from the extension).
 *
 * Tiers (shown as tags in the lists):
 *  - SQ: lossy, below 200 kbps
 *  - HQ: lossy, 200 kbps and up (256-320 kbps typical)
 *  - CD: lossless, 16-bit and up to 48 kHz
 *  - HD: lossless hi-res, more than 16-bit or above 48 kHz
 */
data class AudioQuality(
    val format: String,      // FLAC, MP3, AAC, ALAC, WAV, OPUS…
    val lossless: Boolean,
    val bits: Int,           // 0 = unknown / not applicable (lossy)
    val sampleRate: Int,     // Hz, 0 = unknown
    val kbps: Int,           // 0 = unknown
) {
    val tag: String
        get() = when {
            lossless && (bits > 16 || sampleRate > 48000) -> "HD"
            lossless -> "CD"
            kbps >= 200 -> "HQ"
            else -> "SQ"
        }

    /** "FLAC · 24-bit · 48 kHz" or "MP3 · 320 kbps · 44.1 kHz". */
    val label: String
        get() {
            val parts = mutableListOf(format)
            if (lossless && bits > 0) parts += "$bits-bit"
            if (!lossless && kbps > 0) parts += "$kbps kbps"
            if (sampleRate > 0) {
                val k = sampleRate / 1000.0
                parts += (if (k == k.toInt().toDouble()) "${k.toInt()}" else String.format(Locale.US, "%.1f", k)) + " kHz"
            }
            if (lossless && bits <= 0 && kbps > 0) parts += "$kbps kbps"
            return parts.joinToString(" · ")
        }

    fun toJson(): JSONObject = JSONObject().put("f", format).put("l", lossless).put("b", bits).put("r", sampleRate).put("k", kbps)

    companion object {
        fun fromJson(o: JSONObject) = AudioQuality(o.optString("f"), o.optBoolean("l"), o.optInt("b"), o.optInt("r"), o.optInt("k"))

        /** Read format / bit depth / sample rate / bitrate from the file. */
        fun probe(raw: RawSong): AudioQuality? {
            val ext = raw.path.substringAfterLast('.', "").lowercase(Locale.US)
            val avgKbps = when {
                raw.bitrate > 0 -> raw.bitrate / 1000
                raw.durationMs > 0 && raw.size > 0 -> ((raw.size * 8) / raw.durationMs).toInt()
                else -> 0
            }
            // headers we can read exactly
            when (ext) {
                "flac" -> readHead(raw, 64)?.let { flac(it) }?.let { (bits, rate) ->
                    return AudioQuality("FLAC", true, bits, rate, avgKbps)
                }
                "wav" -> readHead(raw, 256)?.let { wav(it) }?.let { (bits, rate) ->
                    return AudioQuality("WAV", true, bits, rate, avgKbps)
                }
            }
            // everything else: ask the platform's extractor
            val ex = MediaExtractor()
            try {
                ex.setDataSource(Library.app, raw.uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                    var bits = if (f.containsKey("bits-per-sample")) f.getInteger("bits-per-sample") else 0
                    if (bits == 0 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        bits = when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                            else -> 0
                        }
                    }
                    val trackKbps = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else 0
                    val (name, lossless) = codecName(mime, ext)
                    if (lossless && bits == 0) bits = 16
                    return AudioQuality(name, lossless, if (lossless) bits else 0, rate, if (trackKbps > 0) trackKbps else avgKbps)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ex.release() }
            }
            val (name, lossless) = codecName("", ext)
            return if (name.isEmpty()) null else AudioQuality(name, lossless, if (lossless) 16 else 0, 0, avgKbps)
        }

        private fun codecName(mime: String, ext: String): Pair<String, Boolean> = when {
            mime == "audio/flac" || ext == "flac" -> "FLAC" to true
            mime == "audio/alac" -> "ALAC" to true
            mime == "audio/raw" || ext == "wav" -> "WAV" to true
            ext == "aiff" || ext == "aif" -> "AIFF" to true
            ext == "ape" -> "APE" to true
            ext == "wv" -> "WavPack" to true
            ext == "dsf" || ext == "dff" -> "DSD" to true
            mime == "audio/mpeg" || ext == "mp3" -> "MP3" to false
            mime == "audio/mp4a-latm" || ext == "m4a" || ext == "aac" -> "AAC" to false
            mime == "audio/opus" || ext == "opus" -> "OPUS" to false
            mime == "audio/vorbis" || ext == "ogg" -> "OGG" to false
            ext == "wma" -> "WMA" to false
            ext.isNotEmpty() -> ext.uppercase(Locale.US) to false
            else -> "" to false
        }

        private fun readHead(raw: RawSong, n: Int): ByteArray? = runCatching {
            Library.app.contentResolver.openInputStream(raw.uri)?.use { readN(it, n) }
        }.getOrNull() ?: runCatching { File(raw.path).inputStream().use { readN(it, n) } }.getOrNull()

        private fun readN(s: InputStream, n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = s.read(b, off, n - off)
                if (r <= 0) break
                off += r
            }
            return b.copyOf(off)
        }

        /** FLAC STREAMINFO: bits per sample + sample rate. Skips an ID3 tag in front if present. */
        private fun flac(head: ByteArray): Pair<Int, Int>? {
            var i = 0
            if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                return null // rare; let MediaExtractor handle it
            }
            if (head.size < i + 4 + 4 + 18) return null
            if (String(head, i, 4, Charsets.US_ASCII) != "fLaC") return null
            i += 4 + 4 // marker + metadata block header (STREAMINFO is always first)
            val b = { k: Int -> head[i + k].toInt() and 0xFF }
            // bytes 10..13 of STREAMINFO: 20 bits sample rate, 3 bits channels-1, 5 bits bps-1
            val rate = (b(10) shl 12) or (b(11) shl 4) or (b(12) shr 4)
            val bps = (((b(12) and 0x01) shl 4) or (b(13) shr 4)) + 1
            return if (rate > 0 && bps in 4..32) bps to rate else null
        }

        /** WAV "fmt " chunk. */
        private fun wav(head: ByteArray): Pair<Int, Int>? {
            if (head.size < 36 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var i = 12
            while (i + 8 <= head.size) {
                val id = String(head, i, 4, Charsets.US_ASCII)
                val len = le32(head, i + 4)
                if (id == "fmt " && i + 8 + 16 <= head.size) {
                    val rate = le32(head, i + 12)
                    val bits = (head[i + 22].toInt() and 0xFF) or ((head[i + 23].toInt() and 0xFF) shl 8)
                    return bits to rate
                }
                i += 8 + len + (len and 1)
            }
            return null
        }

        private fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
