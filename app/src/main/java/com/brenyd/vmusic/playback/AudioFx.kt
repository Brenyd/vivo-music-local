package com.brenyd.vmusic.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.brenyd.vmusic.data.Library
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow

/**
 * Sound settings shared by the playback service and the UI (same process):
 * equalizer + bass boost, ReplayGain, fade between songs, hi-res / bit-perfect USB output.
 */
object AudioFx {
    private const val TAG = "AudioFx"

    // ------------------------------------------------------------------ settings
    private val p get() = Library.prefs

    var eqEnabled: Boolean
        get() = p.getBoolean("eq_on", false)
        set(v) { p.edit().putBoolean("eq_on", v).apply(); eq?.enabled = v; bass?.enabled = v && bassStrength > 0; refresh() }
    var bassStrength: Int // 0..1000
        get() = p.getInt("bass", 0)
        set(v) { p.edit().putInt("bass", v).apply(); runCatching { bass?.setStrength(v.toShort()); bass?.enabled = eqEnabled && v > 0 } }
    /** off / track / album */
    var replayGainMode: String
        get() = p.getString("rg_mode", "track") ?: "track"
        set(v) { p.edit().putString("rg_mode", v).apply(); onGainSettingsChanged?.invoke() }
    /** extra gain (dB) for files without ReplayGain tags and as pre-amp */
    var replayGainPreamp: Float
        get() = p.getFloat("rg_preamp", 0f)
        set(v) { p.edit().putFloat("rg_preamp", v).apply(); onGainSettingsChanged?.invoke() }
    /** fade between songs, seconds (0 = off, gapless) */
    var fadeSeconds: Int
        get() = p.getInt("fade_s", 0)
        set(v) { p.edit().putInt("fade_s", v).apply() }
    /** 32-bit float output so 24-bit files are not reduced to 16-bit (applies on next app start) */
    var hiResOutput: Boolean
        get() = p.getBoolean("hires", true)
        set(v) { p.edit().putBoolean("hires", v).apply() }
    /** Android 14+: ask the system for an unmixed, bit-perfect path to a USB DAC */
    var bitPerfect: Boolean
        get() = p.getBoolean("bitperfect", false)
        set(v) { p.edit().putBoolean("bitperfect", v).apply(); updateBitPerfect() }

    internal var onGainSettingsChanged: (() -> Unit)? = null

    // ------------------------------------------------------------------ equalizer
    data class Band(val index: Int, val centerHz: Int, val level: Int)
    data class EqState(val available: Boolean, val bands: List<Band>, val min: Int, val max: Int, val presets: List<String>)

    val state = MutableStateFlow(EqState(false, emptyList(), -1500, 1500, emptyList()))
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null

    fun attach(sessionId: Int) {
        release()
        if (sessionId == 0) return
        try {
            eq = Equalizer(0, sessionId).apply {
                val saved = p.getString("eq_levels", null)?.split(",")?.mapNotNull { it.toShortOrNull() }
                if (saved != null && saved.size == numberOfBands.toInt()) saved.forEachIndexed { i, l -> setBandLevel(i.toShort(), l) }
                enabled = eqEnabled
            }
        } catch (e: Exception) {
            Log.w(TAG, "equalizer unavailable", e)
        }
        try {
            bass = BassBoost(0, sessionId).apply {
                if (strengthSupported) setStrength(bassStrength.toShort())
                enabled = eqEnabled && bassStrength > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "bass boost unavailable", e)
        }
        refresh()
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null
        bass = null
    }

    private fun refresh() {
        val e = eq
        if (e == null) {
            state.value = EqState(false, emptyList(), -1500, 1500, emptyList()); return
        }
        runCatching {
            val r = e.bandLevelRange
            state.value = EqState(
                true,
                (0 until e.numberOfBands).map { Band(it, e.getCenterFreq(it.toShort()) / 1000, e.getBandLevel(it.toShort()).toInt()) },
                r[0].toInt(), r[1].toInt(),
                (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
            )
        }
    }

    fun setBand(index: Int, level: Int) {
        val e = eq ?: return
        runCatching { e.setBandLevel(index.toShort(), level.toShort()) }
        saveLevels()
        refresh()
    }

    fun usePreset(index: Int) {
        val e = eq ?: return
        runCatching { e.usePreset(index.toShort()) }
        saveLevels()
        refresh()
    }

    fun resetEq() {
        val e = eq ?: return
        for (i in 0 until e.numberOfBands) runCatching { e.setBandLevel(i.toShort(), 0) }
        saveLevels()
        refresh()
    }

    private fun saveLevels() {
        val e = eq ?: return
        val levels = (0 until e.numberOfBands).joinToString(",") { e.getBandLevel(it.toShort()).toString() }
        p.edit().putString("eq_levels", levels).apply()
    }

    // ------------------------------------------------------------------ ReplayGain
    data class Gain(val trackDb: Float?, val albumDb: Float?, val trackPeak: Float?, val albumPeak: Float?)

    /** ReplayGain values from FLAC/Ogg Vorbis comments, MP3 TXXX frames and M4A iTunes atoms. */
    fun readGain(tracks: Tracks): Gain {
        var tg: Float? = null; var ag: Float? = null; var tp: Float? = null; var ap: Float? = null
        fun take(k: String, v: String) {
            val key = k.lowercase()
            val num = Regex("[-+]?\\d+(?:[.,]\\d+)?").find(v)?.value?.replace(',', '.')?.toFloatOrNull() ?: return
            when (key) {
                "replaygain_track_gain" -> tg = num
                "replaygain_album_gain" -> ag = num
                "replaygain_track_peak" -> tp = num
                "replaygain_album_peak" -> ap = num
            }
        }
        for (g in tracks.groups) for (i in 0 until g.length) {
            val f: Format = g.getTrackFormat(i)
            val md: Metadata = f.metadata ?: continue
            for (j in 0 until md.length()) {
                when (val e = md.get(j)) {
                    is TextInformationFrame -> if (e.id == "TXXX") e.description?.let { d -> e.values.firstOrNull()?.let { take(d, it) } }
                    is InternalFrame -> take(e.description, e.text)
                    else -> if (e.javaClass.simpleName == "VorbisComment") runCatching {
                        // VorbisComment moved packages between Media3 versions; read it by field name
                        val k = e.javaClass.getField("key").get(e) as String
                        val v = e.javaClass.getField("value").get(e) as String
                        take(k, v)
                    }
                }
            }
        }
        return Gain(tg, ag, tp, ap)
    }

    /** Linear volume factor (never above 1, so it can't clip). */
    fun gainFactor(g: Gain): Float {
        val mode = replayGainMode
        if (mode == "off") return 1f
        val db = (if (mode == "album") g.albumDb ?: g.trackDb else g.trackDb ?: g.albumDb) ?: return 10f.pow(replayGainPreamp.coerceAtMost(0f) / 20f)
        val peak = (if (mode == "album") g.albumPeak ?: g.trackPeak else g.trackPeak ?: g.albumPeak) ?: 1f
        var f = 10f.pow((db + replayGainPreamp) / 20f)
        if (peak > 0f) f = f.coerceAtMost(1f / peak)
        return f.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ bit-perfect USB
    val usbStatus = MutableStateFlow("")
    private var audioManager: AudioManager? = null
    @Volatile var currentSampleRate: Int = 0
        private set

    fun initDevices(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = updateBitPerfect()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = updateBitPerfect()
        }, Handler(Looper.getMainLooper()))
        updateBitPerfect()
    }

    fun onTrackSampleRate(rate: Int) {
        if (rate > 0 && rate != currentSampleRate) {
            currentSampleRate = rate
            updateBitPerfect()
        }
    }

    private fun usbDevice(am: AudioManager): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }

    fun updateBitPerfect() {
        val am = audioManager ?: return
        val dev = usbDevice(am)
        if (dev == null) { usbStatus.value = "No USB DAC connected"; return }
        val name = dev.productName?.toString()?.ifBlank { null } ?: "USB DAC"
        if (Build.VERSION.SDK_INT < 34) {
            usbStatus.value = "$name connected · bit-perfect needs Android 14+"
            return
        }
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        try {
            if (!bitPerfect) {
                am.clearPreferredMixerAttributes(attrs, dev)
                usbStatus.value = "$name connected"
                return
            }
            val options = am.getSupportedMixerAttributes(dev).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            if (options.isEmpty()) { usbStatus.value = "$name doesn't support bit-perfect mode"; return }
            val want = currentSampleRate
            val pick = options.firstOrNull { it.format.sampleRate == want && it.format.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT && hiResOutput }
                ?: options.firstOrNull { it.format.sampleRate == want }
                ?: options.maxByOrNull { it.format.sampleRate }!!
            val ok = am.setPreferredMixerAttributes(attrs, dev, pick)
            usbStatus.value = if (ok) "$name · bit-perfect at ${pick.format.sampleRate / 1000.0} kHz" else "$name · couldn't enable bit-perfect"
        } catch (e: Exception) {
            Log.w(TAG, "bit-perfect failed", e)
            usbStatus.value = "$name · bit-perfect not available"
        }
    }
}
