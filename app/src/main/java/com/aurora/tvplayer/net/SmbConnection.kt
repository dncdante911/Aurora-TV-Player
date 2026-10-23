package com.aurora.tvplayer.net

import android.net.Uri
import com.aurora.tvplayer.data.Media
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.IOException
import java.util.Properties

/** One read-only SMB share for the lifetime of the process. The password is never stored. */
class SmbConnection private constructor(val host: String, val share: String, private val context: CIFSContext) {
    class Entry(val name: String, val url: String, val directory: Boolean, val size: Long) {
        val video: Boolean get() = !directory && Media.isVideo(name)
    }

    val rootUrl = "smb://$host/${Uri.encode(share)}/"

    fun list(url: String): List<Entry> {
        if (!url.startsWith(rootUrl)) throw IOException("Папка вне подключённого ресурса")
        val result = try {
            SmbFile(url, context).use { folder ->
                folder.listFiles().map { child ->
                    child.use { item ->
                        val directory = item.isDirectory
                        Entry(item.name.trimEnd('/'), item.path, directory, if (directory) 0 else runCatching { item.length() }.getOrDefault(0))
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("Не удалось прочитать общую папку", e)
        }
        return result.sortedWith(compareBy<Entry> { !it.directory }.thenComparator { a, b -> Media.naturalOrder.compare(a.name, b.name) })
    }

    fun file(url: String): SmbFile {
        if (!url.startsWith(rootUrl)) throw IOException("Файл вне подключённого ресурса")
        return SmbFile(url, context)
    }

    /** Parent folder inside the share, or null at its root. */
    fun parentOf(url: String): String? {
        if (url == rootUrl) return null
        val trimmed = url.trimEnd('/')
        val parent = trimmed.substring(0, trimmed.lastIndexOf('/') + 1)
        return if (parent.startsWith(rootUrl)) parent else rootUrl
    }

    companion object {
        @Volatile var active: SmbConnection? = null
            private set

        fun canOpen(url: String?): Boolean = active?.let { url != null && url.startsWith(it.rootUrl) } == true

        fun connect(host: String, share: String, domain: String, user: String, password: String): SmbConnection {
            require(host.matches(Regex("[A-Za-z0-9._-]+")) && share.isNotEmpty() && share.none { it in "/\\:" } && share != "." && share != "..") {
                "Введите адрес сервера и имя общей папки без слэшей"
            }
            val settings = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                // SMB 2.0–2.1 as in 2.x: works with Windows, Samba and NAS without SMB3 encryption quirks.
                setProperty("jcifs.smb.client.maxVersion", "SMB210")
                setProperty("jcifs.smb.client.responseTimeout", "12000")
                setProperty("jcifs.smb.client.connTimeout", "12000")
            }
            val base: CIFSContext = BaseContext(PropertyConfiguration(settings))
            val authenticated = if (user.isEmpty() && password.isEmpty()) base.withGuestCrendentials()
            else base.withCredentials(NtlmPasswordAuthenticator(domain, user, password))
            var candidate = SmbConnection(host, share, authenticated)
            try {
                candidate.list(candidate.rootUrl) // Verify access before replacing a working connection.
            } catch (guestError: IOException) {
                if (user.isNotEmpty() || password.isNotEmpty()) throw guestError
                candidate = SmbConnection(host, share, base.withAnonymousCredentials())
                candidate.list(candidate.rootUrl)
            }
            active = candidate
            return candidate
        }
    }
}
