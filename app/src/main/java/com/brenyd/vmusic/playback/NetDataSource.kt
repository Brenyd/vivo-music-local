package com.brenyd.vmusic.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import com.brenyd.vmusic.data.Servers
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Data sources for playback: local files / content URIs, HTTP(S) with the WebDAV login added,
 * and smb:// shares (read with jcifs-ng).
 */
@UnstableApi
object NetDataSource {
    fun factory(ctx: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("vivo-music-local/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            val auth = Servers.authHeaderFor(spec.uri)
            if (auth != null) spec.withAdditionalHeaders(mapOf("Authorization" to auth)) else spec
        }
        val default = DefaultDataSource.Factory(ctx, authed)
        return DataSource.Factory { Routing(default.createDataSource()) }
    }

    /** Sends smb:// to [SmbDataSource] and everything else to the default source. */
    private class Routing(private val default: DataSource) : DataSource {
        private var current: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            default.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val src: DataSource = if (dataSpec.uri.scheme == "smb") SmbDataSource().also { s -> listeners.forEach { s.addTransferListener(it) } } else default
            current = src
            return src.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() { try { current?.close() } finally { current = null } }
    }

    private class SmbDataSource : BaseDataSource(true) {
        private var file: SmbRandomAccessFile? = null
        private var uri: Uri? = null
        private var remaining = 0L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            try {
                val f = SmbFile(dataSpec.uri.toString(), Servers.smbContext(dataSpec.uri.host ?: ""))
                val raf = SmbRandomAccessFile(f, "r")
                raf.seek(dataSpec.position)
                file = raf
                val len = raf.length() - dataSpec.position
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else len
            } catch (e: Exception) {
                throw IOException("Can't open ${dataSpec.uri}: ${e.message}", e)
            }
            transferStarted(dataSpec)
            opened = true
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val n = file!!.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n <= 0) return C.RESULT_END_OF_INPUT
            remaining -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            try { file?.close() } finally {
                file = null
                uri = null
                if (opened) { opened = false; transferEnded() }
            }
        }
    }
}
