package com.aurora.tvplayer.net

import android.net.Uri
import android.util.Log
import com.aurora.tvplayer.data.Media
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import kotlin.concurrent.thread

/** Login for one share; [password] is empty for guest. */
data class SmbLogin(val domain: String, val user: String, val password: String)

/**
 * One read-only SMB share at a time. Saved servers ([SavedServers]) let it come back by itself
 * after Android stopped the app, so "Продолжить" and the player never ask for the password again.
 */
class SmbConnection private constructor(
    val host: String,
    val share: String,
    private val context: CIFSContext,
    /** Connected with SMB 3 after SMB 2 was refused. */
    val smb3: Boolean,
) {
    class Entry(val name: String, val url: String, val directory: Boolean, val size: Long) {
        val video: Boolean get() = !directory && Media.isVideo(name)
        val subtitle: Boolean get() = !directory && Media.isSubtitle(name)
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

    private fun close() {
        // Logging off talks to the server, which may be gone: never on the caller's thread.
        thread(name = "smb-close", isDaemon = true) { runCatching { context.close() } }
    }

    companion object {
        @Volatile var active: SmbConnection? = null
            private set

        /** Set by the application: saved servers to reconnect without asking. */
        @Volatile var saved: SavedServers? = null

        private val lock = Any()

        fun canOpen(url: String?): Boolean = active?.let { url != null && url.startsWith(it.rootUrl) } == true

        /** "smb://host/share/…" -> host and share, or null for anything else. */
        fun hostAndShare(url: String): Pair<String, String>? {
            if (!url.startsWith("smb://", ignoreCase = true)) return null
            val parts = url.substring(6).split('/')
            if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
            return parts[0] to Uri.decode(parts[1])
        }

        /**
         * The connection that can read [url]: the active one, or a silent reconnect to a saved
         * server (blocking, call off the main thread). Throws when the password is not saved.
         */
        fun forUrl(url: String): SmbConnection {
            active?.takeIf { url.startsWith(it.rootUrl) }?.let { return it }
            synchronized(lock) {
                // Another loader may have reconnected while this one waited.
                active?.takeIf { url.startsWith(it.rootUrl) }?.let { return it }
                val (host, share) = hostAndShare(url) ?: throw IOException("Это не адрес сетевой папки")
                val store = saved ?: throw IOException(DISCONNECTED)
                val server = store.find(host, share) ?: throw IOException(DISCONNECTED)
                val password = store.password(server) ?: throw IOException(DISCONNECTED)
                val connection = connect(server.host, server.share, server.domain, server.user, password, preferSmb3 = server.smb3)
                store.save(server.host, server.share, server.login, "", remember = true, smb3 = connection.smb3)
                return connection
            }
        }

        /** Reconnects to [server] with its saved password; false when there is none or it failed. */
        fun reconnect(server: SmbServer): Boolean = runCatching { forUrl("smb://${server.host}/${Uri.encode(server.share)}/") }.isSuccess

        fun connect(host: String, share: String, domain: String, user: String, password: String, preferSmb3: Boolean = false): SmbConnection {
            require(validHost(host) && share.isNotEmpty() && share.none { it in "/\\:" } && share != "." && share != "..") {
                "Введите адрес сервера и имя общей папки без слэшей"
            }
            val candidate = withDialects(preferSmb3) { base, smb3 ->
                val guest = user.isEmpty() && password.isEmpty()
                val authenticated = if (guest) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(domain, user, password))
                try {
                    SmbConnection(host, share, authenticated, smb3).also { it.list(it.rootUrl) } // Verify before replacing a working connection.
                } catch (guestError: IOException) {
                    if (!guest) throw guestError
                    SmbConnection(host, share, base.withAnonymousCredentials(), smb3).also { it.list(it.rootUrl) }
                }
            }
            synchronized(lock) {
                val previous = active
                active = candidate
                if (previous != null && previous !== candidate) previous.close()
            }
            return candidate
        }

        /** Shares a server offers ("Movies", "Serials"), without hidden ones like C$ or IPC$. */
        fun listShares(host: String, domain: String, user: String, password: String): List<String> {
            require(validHost(host)) { "Введите адрес сервера: имя или IP, например 192.168.1.20" }
            return withDialects(preferSmb3 = false) { base, _ ->
                val context = if (user.isEmpty() && password.isEmpty()) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(domain, user, password))
                try {
                    SmbFile("smb://$host/", context).use { server ->
                        server.listFiles().map { it.name.trimEnd('/') }.filter { it.isNotEmpty() && !it.endsWith("$") }.sortedWith(Media.naturalOrder)
                    }
                } catch (e: Exception) {
                    throw IOException("Не удалось получить список папок", e)
                } finally {
                    runCatching { base.close() }
                }
            }
        }

        /**
         * SMB 2.0–2.1 first, as in 2.x: works with Windows, Samba and NAS without SMB 3 quirks.
         * Servers that refuse SMB 2 (min protocol SMB3) get a second try with SMB 3.x. A host that
         * does not answer or a wrong password is not retried: the result would be the same.
         */
        private fun <T> withDialects(preferSmb3: Boolean, attempt: (CIFSContext, Boolean) -> T): T {
            val order = if (preferSmb3) listOf(true, false) else listOf(false, true)
            var failure: Exception? = null
            for (smb3 in order) {
                val base = BaseContext(PropertyConfiguration(Properties().apply {
                    setProperty("jcifs.smb.client.minVersion", if (smb3) "SMB300" else "SMB202")
                    setProperty("jcifs.smb.client.maxVersion", if (smb3) "SMB311" else "SMB210")
                    setProperty("jcifs.smb.client.responseTimeout", "12000")
                    setProperty("jcifs.smb.client.connTimeout", "12000")
                    setProperty("jcifs.smb.client.soTimeout", "35000")
                }))
                try {
                    return attempt(base, smb3)
                } catch (e: Exception) {
                    runCatching { base.close() }
                    if (failure == null) failure = e
                    Log.w("AuroraSmb", "Connect with ${if (smb3) "SMB3" else "SMB2"} failed", e)
                    if (e is IllegalArgumentException || unreachable(e) || wrongLogin(e)) break
                }
            }
            throw failure!!
        }

        private fun validHost(host: String) = host.matches(Regex("[A-Za-z0-9._-]+"))

        /** A sentence for the user: what went wrong and what to check. */
        fun describe(error: Throwable): String = when {
            error is IllegalArgumentException -> error.message ?: "Проверьте адрес и имя папки"
            unreachable(error) -> "Сервер не отвечает. Проверьте адрес, что компьютер или NAS включён и ТВ в той же сети."
            wrongLogin(error) -> "Неверный логин или пароль. Для домена укажите DOMAIN\\user; гостевой вход на Windows 11 по умолчанию выключен."
            status(error) == SmbException.NT_STATUS_BAD_NETWORK_NAME || status(error) == SmbException.NT_STATUS_OBJECT_NAME_NOT_FOUND ->
                "Общая папка не найдена. Оставьте поле «Общая папка» пустым и нажмите «Подключить» — плеер покажет папки сервера."
            status(error) == SmbException.NT_STATUS_ACCESS_DENIED -> "Нет доступа к папке. Разрешите этому пользователю чтение в настройках общего доступа."
            else -> "Нет соединения или доступа. Проверьте адрес, имя папки, логин и что на сервере включён SMB 2 или 3."
        }

        private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8)

        private fun unreachable(error: Throwable) = causes(error).any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException
        }

        private fun wrongLogin(error: Throwable) = causes(error).any {
            it is SmbAuthException || (it is SmbException && (it.ntStatus == SmbException.NT_STATUS_LOGON_FAILURE || it.ntStatus == SmbException.NT_STATUS_ACCOUNT_DISABLED))
        }

        private fun status(error: Throwable): Int? = causes(error).filterIsInstance<SmbException>().map { it.ntStatus }.firstOrNull { it != 0 }

        const val DISCONNECTED = "Подключение к сетевой папке завершено. Откройте её снова."
    }
}
