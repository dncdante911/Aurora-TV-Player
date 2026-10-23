package com.aurora.tvplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.aurora.tvplayer.BuildConfig
import com.aurora.tvplayer.net.SmbDataSource

/**
 * smb:// goes to [SmbDataSource], everything else (files, content://, http, HLS, DASH) to Media3's
 * default source, so one playlist can mix network folders and links.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource private constructor(private val smb: DataSource, private val other: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) smb else other
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
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
