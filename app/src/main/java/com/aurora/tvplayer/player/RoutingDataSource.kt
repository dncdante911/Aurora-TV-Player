package com.aurora.tvplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.aurora.tvplayer.BuildConfig
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.data.Subtitles
import com.aurora.tvplayer.net.SmbDataSource
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * smb:// goes to [SmbDataSource], everything else (files, content://, http, HLS, DASH) to Media3's
 * default source, so one playlist can mix network folders and links. Subtitle files are read
 * whole and handed over as UTF-8 ([Subtitles.toUtf8]), so Windows-1251 SRT shows Cyrillic.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource private constructor(private val smb: DataSource, private val other: DataSource) : DataSource {
    private var active: DataSource? = null
    /** A subtitle file converted to UTF-8, served from memory instead of [active]. */
    private var memory: ByteArray? = null
    private var memoryPos = 0
    private var memoryUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) smb else other
        // Whole files only: a byte range (HLS segments) must keep its exact length.
        if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong() && Media.isSubtitle(dataSpec.uri.lastPathSegment)) {
            val bytes = readWhole(source, dataSpec)
            memory = bytes
            memoryPos = 0
            memoryUri = dataSpec.uri
            return bytes.size.toLong()
        }
        active = source
        return source.open(dataSpec)
    }

    private fun readWhole(source: DataSource, dataSpec: DataSpec): ByteArray {
        try {
            source.open(dataSpec)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
                if (out.size() > Subtitles.MAX_BYTES) throw IOException("Файл субтитров слишком большой")
            }
            return Subtitles.toUtf8(out.toByteArray())
        } finally {
            source.close()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        memory?.let { bytes ->
            if (memoryPos >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - memoryPos)
            System.arraycopy(bytes, memoryPos, buffer, offset, count)
            memoryPos += count
            return count
        }
        return active!!.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = memoryUri ?: active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        memory = null
        memoryUri = null
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val default = DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setUserAgent("AuroraTV/${BuildConfig.VERSION_NAME}"),
        )

        override fun createDataSource(): DataSource = RoutingDataSource(SmbDataSource.FACTORY.createDataSource(), default.createDataSource())
    }
}
