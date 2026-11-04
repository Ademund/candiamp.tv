package tv.candiamp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the pairing page puts what you typed on your phone. The password lives in the Android
 * keystore, never in plain preferences, and never leaves the TV.
 */
class Credentials(context: Context) {

    private val prefs = context.getSharedPreferences("candiamp", Context.MODE_PRIVATE)
    private var cached: SubsonicClient? = null
    private var cachedKey: String? = null

    var server: String?
        get() = prefs.getString("server", null)
        set(value) {
            prefs.edit().putString("server", value).apply()
        }

    var user: String?
        get() = prefs.getString("user", null)
        set(value) {
            prefs.edit().putString("user", value).apply()
        }

    var password: String?
        get() = prefs.getString("password", null)?.let { decrypt(it) }
        set(value) {
            if (value == null) prefs.edit().remove("password").apply()
            else prefs.edit().putString("password", encrypt(value)).apply()
        }

    /**
     * A salt that survives restarts: the token changes with it, and so would every artwork URL,
     * which would defeat the image cache.
     */
    val salt: String
        get() = prefs.getString("salt", null) ?: randomSalt().also {
            prefs.edit().putString("salt", it).apply()
        }

    val isConfigured: Boolean
        get() = !server.isNullOrBlank() && !user.isNullOrBlank() && !password.isNullOrBlank()

    fun client(): SubsonicClient? {
        val s = server
        val u = user
        val p = password
        if (s.isNullOrBlank() || u.isNullOrBlank() || p.isNullOrBlank()) return null
        val key = "$s|$u|$p"
        cached?.let { if (cachedKey == key) return it }
        return SubsonicClient(s, u, p, salt).also {
            cached = it
            cachedKey = key
        }
    }

    fun save(server: String, user: String, password: String) {
        this.server = SubsonicClient.normalize(server)
        this.user = user.trim()
        this.password = password
    }

    fun clear() {
        prefs.edit().remove("server").remove("user").remove("password").apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val body = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "candiamp-password"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun randomSalt(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { alphabet.random() }.joinToString("")
        }
    }
}
