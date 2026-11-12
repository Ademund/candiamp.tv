package tv.candiamp.data

import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Last.fm answered with an error code, or could not be reached. */
class LastFmException(message: String, val code: Int? = null) : Exception(message) {
    /** The session is gone; the user has to link the account again. */
    val needsReauth: Boolean get() = code == 9 || code == 4

    /** Worth retrying later (offline/slow/failed at Last.fm). */
    val retryable: Boolean get() = code == 11 || code == 16 || code == 29
}

data class LastFmSession(val username: String, val key: String)

data class LastFmUser(val username: String, val playcount: Long)

/**
 * A small Last.fm client.
 *
 * The API wants every call signed: sort the parameters by name, concatenate `name+value`, append
 * the shared secret, md5 it. `format` and `callback` are **not** part of the signature (see
 * `last.fm/api/authspec`). Writes are form-encoded POSTs.
 *
 * This class is deliberately blocking (OkHttp); callers use `withContext(Dispatchers.IO)`.
 */
class LastFmClient(
    private val apiKey: String,
    private val sharedSecret: String,
    private val sessionKey: String? = null,
    private val apiRoot: String = API_ROOT,
) {

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Step 1 of the desktop auth flow. The token is single-use and valid for 60 minutes. */
    fun requestToken(): String = parseToken(call("auth.getToken"))

    /** Step 3: turn an authorized token into the session key that is stored on the TV. */
    fun session(token: String): LastFmSession =
        parseSession(call("auth.getSession", listOf("token" to token)))

    fun userInfo(): LastFmUser = parseUser(call("user.getInfo", authenticated = true))

    internal fun call(
        method: String,
        params: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = false,
    ): JsonObject {
        val signed = LinkedHashMap<String, String>()
        signed["method"] = method
        signed["api_key"] = apiKey
        if (authenticated) {
            signed["sk"] = sessionKey ?: throw LastFmException("Last.fm is not connected.")
        }
        for ((name, value) in params) signed[name] = value

        val form = FormBody.Builder()
            .add("format", "json")
            .add("api_sig", signature(signed, sharedSecret))
            .apply { signed.forEach { (name, value) -> add(name, value) } }
            .build()
        val request = Request.Builder()
            .url(apiRoot)
            .post(form)
            .header("User-Agent", USER_AGENT)
            .build()

        val status: Int
        val text: String
        try {
            http.newCall(request).execute().use { response ->
                status = response.code
                text = response.body.string()
            }
        } catch (e: IOException) {
            throw LastFmException("Can't reach Last.fm. Check the TV's connection.")
        }

        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        parseError(root)?.let { throw it }
        if (status !in 200..299) throw LastFmException("Last.fm answered HTTP $status.")
        return root ?: throw LastFmException("Last.fm sent an unexpected answer.")
    }

    companion object {
        const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
        const val AUTH_URL = "https://www.last.fm/api/auth/"
        const val USER_AGENT = "candiamp/0.1 (Android TV)"

        /** The string Last.fm expects in `api_sig`. */
        internal fun signature(params: Map<String, String>, secret: String): String =
            md5(
                params.entries
                    .filterNot { it.key == "api_sig" || it.key == "format" || it.key == "callback" }
                    .sortedBy { it.key }
                    .joinToString("") { it.key + it.value } + secret,
            )

        internal fun authUrl(apiKey: String, token: String): String =
            AUTH_URL + "?api_key=" + encode(apiKey) + "&token=" + encode(token)

        internal fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

internal fun parseToken(root: JsonObject): String =
    root.text("token") ?: throw LastFmException("Last.fm did not hand out a token.")

internal fun parseSession(root: JsonObject): LastFmSession {
    val session = root.obj("session") ?: throw LastFmException("Last.fm did not create a session.")
    val username = session.text("name") ?: throw LastFmException("Last.fm sent a session without a username.")
    val key = session.text("key") ?: throw LastFmException("Last.fm sent a session without a key.")
    return LastFmSession(username, key)
}

internal fun parseUser(root: JsonObject): LastFmUser {
    val user = root.obj("user") ?: throw LastFmException("Last.fm did not return the account.")
    return LastFmUser(
        username = user.text("name").orEmpty(),
        playcount = user.long("playcount") ?: 0L,
    )
}

internal fun parseError(root: JsonObject?): LastFmException? {
    val code = root?.int("error") ?: return null
    return LastFmException(root.text("message") ?: "Last.fm error $code.", code)
}
