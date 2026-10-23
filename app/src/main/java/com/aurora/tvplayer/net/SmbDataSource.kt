package com.aurora.tvplayer.net

import android.net.Uri
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
 * up to the position, so jumping inside a 4K remux is instant and no temporary copy is made.
 */
@OptIn(UnstableApi::class)
class SmbDataSource private constructor() : BaseDataSource(true) {
    private var file: SmbFile? = null
    private var input: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val session = SmbConnection.active ?: throw IOException("Подключение к сетевой папке завершено. Откройте её снова.")
        try {
            val smb = session.file(dataSpec.uri.toString())
            file = smb
            val size = smb.length()
            if (dataSpec.position > size) throw IOException("Позиция за концом файла")
            val stream = SmbRandomAccessFile(smb, "r")
            input = stream
            stream.seek(dataSpec.position)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - dataSpec.position
            else minOf(dataSpec.length, size - dataSpec.position)
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
        val count = input?.read(buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (count == -1) return C.RESULT_END_OF_INPUT
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
        } finally {
            input = null
            file?.close()
            file = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        val FACTORY = DataSource.Factory { SmbDataSource() }
    }
}
