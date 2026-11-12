package tv.candiamp.data

import android.content.Context

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
        get() = prefs.getString("password", null)?.let { KeystoreCrypto.decrypt(ALIAS, it) }
        set(value) {
            if (value == null) prefs.edit().remove("password").apply()
            else prefs.edit().putString("password", KeystoreCrypto.encrypt(ALIAS, value)).apply()
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

    companion object {
        private const val ALIAS = "candiamp-password"

        fun randomSalt(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { alphabet.random() }.joinToString("")
        }
    }
}
