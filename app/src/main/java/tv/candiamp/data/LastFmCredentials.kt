package tv.candiamp.data

import android.content.Context

/**
 * The Last.fm account this TV uses.
 *
 * The API key is not sensitive on its own and stays in plain preferences; the shared secret and
 * the session key are sealed by the Android keystore. Nothing is sent to Last.fm until the user
 * has linked an account from the setup page.
 */
class LastFmCredentials(context: Context) {

    private val prefs = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)
    private var cached: LastFmClient? = null
    private var cachedKey: String? = null

    var apiKey: String?
        get() = prefs.getString("apiKey", null)
        set(value) {
            prefs.edit().putString("apiKey", value).apply()
            cached = null
        }

    var sharedSecret: String?
        get() = prefs.getString("sharedSecret", null)?.let { KeystoreCrypto.decrypt(SECRET_ALIAS, it) }
        set(value) {
            if (value == null) prefs.edit().remove("sharedSecret").apply()
            else prefs.edit().putString("sharedSecret", KeystoreCrypto.encrypt(SECRET_ALIAS, value)).apply()
            cached = null
        }

    var sessionKey: String?
        get() = prefs.getString("sessionKey", null)?.let { KeystoreCrypto.decrypt(SESSION_ALIAS, it) }
        set(value) {
            if (value == null) prefs.edit().remove("sessionKey").apply()
            else prefs.edit().putString("sessionKey", KeystoreCrypto.encrypt(SESSION_ALIAS, value)).apply()
            cached = null
        }

    var username: String?
        get() = prefs.getString("username", null)
        set(value) {
            prefs.edit().putString("username", value).apply()
            cached = null
        }

    /** Both pieces needed to talk to Last.fm at all. */
    val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank() && !sharedSecret.isNullOrBlank()

    /** A session key plus the account it belongs to. */
    val isLinked: Boolean
        get() = !sessionKey.isNullOrBlank() && !username.isNullOrBlank()

    fun client(): LastFmClient? {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return null
        val secret = sharedSecret?.takeIf { it.isNotBlank() } ?: return null
        val session = sessionKey
        val cacheKey = "$key|$secret|${session.orEmpty()}"
        cached?.let { if (cachedKey == cacheKey) return it }
        return LastFmClient(key, secret, session).also {
            cached = it
            cachedKey = cacheKey
        }
    }

    fun saveApiKey(apiKey: String, sharedSecret: String) {
        this.apiKey = apiKey.trim()
        this.sharedSecret = sharedSecret.trim()
    }

    fun saveSession(username: String, key: String) {
        this.username = username.trim()
        this.sessionKey = key.trim()
    }

    fun clearSession() {
        sessionKey = null
        username = null
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        cached = null
    }

    /** `abcd…wxyz`, for showing which key is configured without printing the whole thing. */
    fun maskedKey(): String? = apiKey?.takeIf { it.isNotBlank() }?.let { key ->
        if (key.length <= 8) "••••••••" else key.take(4) + "…" + key.takeLast(4)
    }

    companion object {
        private const val SECRET_ALIAS = "candiamp-lastfm-secret"
        private const val SESSION_ALIAS = "candiamp-lastfm-session"
    }
}
