package com.aurora.tvplayer.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A share the user connected to; [login] is "user" or "DOMAIN\user", empty for guest. */
data class SmbServer(
    val host: String,
    val share: String,
    val login: String,
    val hasPassword: Boolean,
    /** The server answered only with SMB 3; next time that is tried first. */
    val smb3: Boolean,
    val usedAt: Long,
) {
    val key: String get() = keyOf(host, share)
    val guest: Boolean get() = login.isEmpty()
    val domain: String get() = login.substringBefore('\\', "")
    val user: String get() = login.substringAfter('\\')
    val label: String get() = "$host / $share"

    /** Reconnects silently only when no password has to be typed. */
    val canReconnect: Boolean get() = guest || hasPassword

    companion object {
        fun keyOf(host: String, share: String) = host.lowercase(Locale.ROOT) + "/" + share.lowercase(Locale.ROOT)
    }
}

/**
 * Recently used SMB shares in the "network" preferences. A password is kept only when the user
 * asked for it, encrypted with a key that never leaves the Android Keystore of this TV.
 */
class SavedServers(context: Context) {
    private val prefs = context.getSharedPreferences("network", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<SmbServer> {
        prefs.getString(KEY_SERVERS, null)?.let { raw ->
            return runCatching {
                val array = JSONArray(raw)
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    SmbServer(o.getString("host"), o.getString("share"), o.optString("login"), o.has("password"), o.optBoolean("smb3"), o.optLong("at"))
                }.sortedByDescending { it.usedAt }
            }.getOrDefault(emptyList())
        }
        // 3.3 and earlier kept one server without a password.
        val host = prefs.getString("host", "").orEmpty()
        val share = prefs.getString("share", "").orEmpty()
        if (host.isEmpty() || share.isEmpty()) return emptyList()
        return listOf(SmbServer(host, share, prefs.getString("user", "").orEmpty(), hasPassword = false, smb3 = false, usedAt = 0))
    }

    fun last(): SmbServer? = all().firstOrNull()

    fun find(host: String, share: String): SmbServer? = SmbServer.keyOf(host, share).let { key -> all().firstOrNull { it.key == key } }

    /** Remembers a successful connection; [password] is stored only when [remember] is set. */
    @Synchronized
    fun save(host: String, share: String, login: String, password: String, remember: Boolean, smb3: Boolean) {
        val key = SmbServer.keyOf(host, share)
        val previous = rawEntries().firstOrNull { SmbServer.keyOf(it.getString("host"), it.getString("share")) == key }
        val entry = JSONObject().put("host", host).put("share", share).put("login", login).put("smb3", smb3).put("at", System.currentTimeMillis())
        when {
            remember && password.isNotEmpty() -> SecretBox.encrypt(password)?.let { entry.put("password", it) }
            // Same login, nothing new typed (silent reconnect): keep the stored password.
            remember && previous?.optString("login") == login -> previous.optString("password").takeIf { it.isNotEmpty() }?.let { entry.put("password", it) }
        }
        val others = rawEntries().filterNot { SmbServer.keyOf(it.getString("host"), it.getString("share")) == key }
        write(listOf(entry) + others.take(MAX_SERVERS - 1))
        prefs.edit { putString("host", host).putString("share", share).putString("user", login) }
    }

    @Synchronized
    fun remove(server: SmbServer) = write(rawEntries().filterNot { SmbServer.keyOf(it.getString("host"), it.getString("share")) == server.key })

    @Synchronized
    fun forgetPassword(server: SmbServer) = write(rawEntries().map {
        if (SmbServer.keyOf(it.getString("host"), it.getString("share")) == server.key) it.apply { remove("password") } else it
    })

    /** The stored password, "" for guest, null when none is stored or the key is gone (e.g. after a reset). */
    @Synchronized
    fun password(server: SmbServer): String? {
        if (server.guest) return ""
        val entry = rawEntries().firstOrNull { SmbServer.keyOf(it.getString("host"), it.getString("share")) == server.key } ?: return null
        return entry.optString("password").takeIf { it.isNotEmpty() }?.let(SecretBox::decrypt)
    }

    private fun rawEntries(): List<JSONObject> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return all().map { s ->
            JSONObject().put("host", s.host).put("share", s.share).put("login", s.login).put("smb3", s.smb3).put("at", s.usedAt)
        }
        return runCatching { JSONArray(raw).let { array -> List(array.length()) { array.getJSONObject(it) } } }.getOrDefault(emptyList())
    }

    private fun write(entries: List<JSONObject>) = prefs.edit { putString(KEY_SERVERS, JSONArray(entries).toString()) }

    private companion object {
        const val KEY_SERVERS = "servers_v4"
        const val MAX_SERVERS = 6
    }
}

/** AES-GCM with a non-exportable Keystore key: the ciphertext is useless outside this device. */
private object SecretBox {
    private const val ALIAS = "aurora_smb_passwords"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    fun encrypt(text: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(sealed, Base64.NO_WRAP)
    }.onFailure { Log.w("AuroraSmb", "Password not saved", it) }.getOrNull()

    fun decrypt(value: String): String? = runCatching {
        val sealed = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
    }.onFailure { Log.w("AuroraSmb", "Saved password unreadable", it) }.getOrNull()
}
