package com.brenyd.vmusic.data

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Converts songs to AAC (.m4a) with Media3 Transformer (hardware encoder, no ffmpeg needed).
 * The original file is kept; the copy goes to Music/vMusic/Converted.
 */
object Converter {
    val busy = MutableStateFlow<String?>(null)

    suspend fun toAac(s: Song, kbps: Int): Boolean {
        val out = File(Library.app.cacheDir, "convert_${System.currentTimeMillis()}.m4a")
        busy.value = s.title
        try {
            val ok = suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val enc = DefaultEncoderFactory.Builder(Library.app)
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(kbps * 1000).build())
                        .build()
                    val t = Transformer.Builder(Library.app)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(enc)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()
                    t.start(MediaItem.fromUri(s.uri), out.absolutePath)
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
                }
            }
            if (!ok || !out.exists()) return false
            val name = "${s.artist} - ${s.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m4a"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/vMusic/Converted")
                }
                val uri = Library.app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
                Library.app.contentResolver.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } } ?: return false
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "vMusic/Converted").apply { mkdirs() }
                out.copyTo(File(dir, name), overwrite = true)
                android.media.MediaScannerConnection.scanFile(Library.app, arrayOf(File(dir, name).path), null, null)
            }
            // keep the cover and matched info for the new copy once it's scanned
            val ov = Library.override(s.path)
            if (ov != null) Library.pendingCopyInfo[name.substringBeforeLast('.')] = ov
            Library.requestRescan()
            return true
        } finally {
            out.delete()
            busy.value = null
        }
    }
}
