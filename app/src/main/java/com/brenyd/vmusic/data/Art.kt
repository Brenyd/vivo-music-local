package com.brenyd.vmusic.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Artwork cache: embedded covers, downloaded covers, artist photos and covers picked by the user. */
object Art {
    private const val MAX = 1200

    fun key(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    fun embeddedFile(path: String) = File(Library.artDir, "e_${key(path)}.jpg")
    fun matchedFile(path: String) = File(Library.artDir, "m_${key(path)}.jpg")
    fun customFile(path: String) = File(Library.artDir, "c_${key(path)}_${System.currentTimeMillis()}.jpg")
    fun artistFile(name: String) = File(Library.artDir, "a_${key(name.lowercase())}.jpg")

    /** content:// URI other apps (Android Auto, system UI) can read. */
    fun contentUri(path: String?): Uri? {
        if (path == null) return null
        val f = File(path)
        if (!f.exists()) return null
        return Uri.parse("content://${Library.app.packageName}.art/${f.name}")
    }

    /** Extract the cover embedded in an audio file. Returns null if there is none. */
    fun extractEmbedded(raw: RawSong): File? {
        val out = embeddedFile(raw.path)
        if (out.exists()) return out
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(Library.app, raw.uri)
            val pic = mmr.embeddedPicture ?: return null
            return if (saveImage(pic, out)) out else null
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Decode, downscale and store an image as JPEG. */
    fun saveImage(bytes: ByteArray, out: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return false
        var sample = 1
        while (opts.outWidth / (sample * 2) >= MAX && opts.outHeight / (sample * 2) >= MAX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        return saveBitmap(bmp, out)
    }

    fun saveBitmap(bmp: Bitmap, out: File): Boolean {
        val scaled = if (bmp.width > MAX || bmp.height > MAX) {
            val r = MAX.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true)
        } else bmp
        val tmp = File(out.path + ".tmp")
        FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return tmp.renameTo(out)
    }

    /** Copy an image the user picked (photo picker URI) as a song cover. */
    fun importCover(songPath: String, src: Uri): File? {
        val bytes = Library.app.contentResolver.openInputStream(src)?.use { it.readBytes() } ?: return null
        val out = customFile(songPath)
        return if (saveImage(bytes, out)) out else null
    }
}

/** Read-only provider for files in the art cache (album covers and artist photos only). */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..") || !name.endsWith(".jpg")) return null
        val f = File(File(context!!.filesDir, "art"), name)
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
