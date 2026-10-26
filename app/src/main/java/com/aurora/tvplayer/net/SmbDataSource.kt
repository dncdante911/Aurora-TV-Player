package com.aurora.tvplayer.net

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Seekable read-only Media3 source over SMB. Seeking moves the file pointer instead of reading
 * up to the position, so jumping inside a 100 GB remux is instant and no temporary copy is made.
 * Extractors ask for small pieces; every SMB read is a network round trip, so data is fetched in
 * 1 MB chunks and served from memory — this is what keeps 4K60 remuxes (80–100 Mbit/s) fluid.
 */
@OptIn(UnstableApi::class)
class SmbDataSource private constructor() : BaseDataSource(true) {
    private var file: SmbFile? = null
    private var input: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    /** Bytes still to deliver to the player (buffered + not yet fetched). */
    private var remaining = 0L
    /** Bytes not yet fetched from the server. */
    private var unfetched = 0L
    /** Allocated on the first SMB open: the routing source creates this object for every load. */
    private var chunk: ByteArray? = null
    private var chunkPos = 0
    private var chunkLen = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            // After Android stopped the app the share comes back from the saved login, if any.
            val session = SmbConnection.forUrl(dataSpec.uri.toString())
            val smb = session.file(dataSpec.uri.toString())
            file = smb
            val size = smb.length()
            if (dataSpec.position > size) throw IOException("Позиция за концом файла")
            val stream = SmbRandomAccessFile(smb, "r")
            input = stream
            stream.seek(dataSpec.position)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - dataSpec.position
            else minOf(dataSpec.length, size - dataSpec.position)
            unfetched = remaining
            if (chunk == null) chunk = ByteArray(CHUNK_SIZE)
            chunkPos = 0
            chunkLen = 0
            uri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            runCatching { close() }
            throw e as? IOException ?: IOException("Ошибка чтения сетевой папки", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkPos == chunkLen) {
            val stream = input ?: return C.RESULT_END_OF_INPUT
            val chunk = chunk ?: return C.RESULT_END_OF_INPUT
            val fetched = stream.read(chunk, 0, minOf(CHUNK_SIZE.toLong(), unfetched).toInt())
            if (fetched <= 0) return C.RESULT_END_OF_INPUT
            unfetched -= fetched
            chunkPos = 0
            chunkLen = fetched
        }
        val count = minOf(length, chunkLen - chunkPos)
        System.arraycopy(chunk!!, chunkPos, buffer, offset, count)
        chunkPos += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        // A dropped connection makes close() throw too; the player must still be told the transfer ended.
        val stream = input
        val smb = file
        input = null
        file = null
        uri = null
        try {
            stream?.close()
        } catch (e: IOException) {
            Log.w("AuroraSmb", "Close failed", e)
        } finally {
            runCatching { smb?.close() }
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 1 shl 20
        val FACTORY = DataSource.Factory { SmbDataSource() }
    }
}
