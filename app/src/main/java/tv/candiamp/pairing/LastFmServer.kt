package tv.candiamp.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tv.candiamp.data.LastFmClient
import tv.candiamp.data.LastFmCredentials
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * The Last.fm half of setup, served by the TV on the local network.
 *
 * The phone posts the API key and shared secret here, gets back an "Authorize on Last.fm" link,
 * and the TV polls `auth.getSession` while the viewer approves the application. No secret is ever
 * typed with a TV remote, and the server only runs while this screen is open.
 */
class LastFmServer(private val credentials: LastFmCredentials) {

    val code: String = PairingServer.makeCode()

    var running by mutableStateOf(false)
        private set
    var port by mutableStateOf(0)
        private set
    var authUrl by mutableStateOf<String?>(null)
        private set
    var token by mutableStateOf<String?>(null)
        private set
    var linked by mutableStateOf(false)
        private set
    var linkedUser by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var hasKeys by mutableStateOf(credentials.isConfigured)
        private set

    private var socket: ServerSocket? = null
    private var acceptor: Thread? = null

    fun start(preferredPort: Int = 8080): Boolean {
        if (running) return true
        for (candidate in preferredPort..preferredPort + 8) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(candidate))
                socket = server
                port = candidate
                running = true
                authUrl = null
                token = null
                linked = false
                linkedUser = null
                message = null
                hasKeys = credentials.isConfigured
                android.util.Log.i("candiamp", "lastfm setup page on port $candidate")
                acceptor = Thread { accept(server) }.apply { isDaemon = true; start() }
                if (credentials.isConfigured) beginAuthorization()
                return true
            } catch (e: Exception) {
                // port taken, try the next one
            }
        }
        message = "Couldn't open a port for the Last.fm page."
        return false
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        acceptor = null
    }

    /** Called by the TV once `auth.getSession` succeeded. */
    fun markLinked(username: String) {
        linked = true
        linkedUser = username
        authUrl = null
        message = null
    }

    /** Called by the TV when Last.fm rejected the token or the network failed. */
    fun fail(text: String) {
        message = text
        authUrl = null
    }

    /** Asks Last.fm for a token on a background thread (the UI must not block). */
    private fun beginAuthorization() {
        Thread {
            val client = credentials.client()
            if (client == null) {
                message = "Enter the API key and shared secret first."
                return@Thread
            }
            try {
                startAuthorization(client, credentials.apiKey.orEmpty())
            } catch (e: Exception) {
                message = e.message ?: "Couldn't reach Last.fm."
                android.util.Log.w("candiamp", "lastfm token failed", e)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startAuthorization(client: LastFmClient, apiKey: String) {
        val value = client.requestToken()
        token = value
        authUrl = LastFmClient.authUrl(apiKey, value)
        message = null
    }

    private fun accept(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            Thread { handle(client) }.apply { isDaemon = true; start() }
        }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            runCatching {
                connection.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
                val request = reader.readLine() ?: return@use
                val parts = request.split(' ')
                if (parts.size < 2) return@use
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (method == "POST") readBody(reader, contentLength) else ""
                val form = parseForm(body)
                val out = connection.getOutputStream()

                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        val url = authUrl
                        if (url != null) {
                            respond(out, 200, authorizePage(url))
                        } else {
                            respond(out, 200, formPage(rememberedForm(), message))
                        }
                    }

                    method == "GET" && path == "/change" ->
                        respond(out, 200, formPage(rememberedForm(), null))

                    method == "GET" && path == "/retry" -> {
                        message = null
                        beginAuthorization()
                        respond(out, 200, retryingPage())
                    }

                    method == "GET" && path == "/status" ->
                        respond(out, 200, statusText(), contentType = "text/plain; charset=utf-8")

                    method == "GET" && path == "/done" ->
                        respond(out, 200, donePage())

                    method == "POST" && path == "/save" -> save(form, out)

                    else -> respond(out, 404, formPage(rememberedForm(), "Nothing here."))
                }
            }
        }
    }

    private fun save(form: Map<String, String>, out: OutputStream) {
        val typedCode = form["code"].orEmpty().trim()
        val apiKey = form["apiKey"].orEmpty().trim()
        val secret = form["secret"].orEmpty().trim()
        val values = mapOf("apiKey" to apiKey)

        when {
            !typedCode.equals(code, ignoreCase = true) ->
                respond(out, 403, formPage(values, "That code doesn't match the one on the TV."))

            apiKey.isBlank() || secret.isBlank() ->
                respond(out, 400, formPage(values, "Fill in both the API key and the shared secret."))

            else -> {
                credentials.saveApiKey(apiKey, secret)
                hasKeys = true
                try {
                    startAuthorization(LastFmClient(apiKey, secret), apiKey)
                    respond(out, 200, authorizePage(authUrl.orEmpty()))
                } catch (e: Exception) {
                    message = e.message ?: "Couldn't reach Last.fm."
                    respond(out, 200, formPage(values, message))
                }
            }
        }
    }

    private fun rememberedForm(): Map<String, String>? = credentials.apiKey?.let { mapOf("apiKey" to it) }

    private fun statusText(): String = when {
        linked -> "linked:" + linkedUser.orEmpty()
        message != null -> "error:" + message.orEmpty()
        else -> "pending"
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val count = reader.read(buffer, read, length - read)
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read)
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&')
        .mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index < 0) null
            else URLDecoder.decode(pair.substring(0, index), "UTF-8") to
                URLDecoder.decode(pair.substring(index + 1), "UTF-8")
        }
        .toMap()

    private fun respond(out: OutputStream, status: Int, html: String, contentType: String = "text/html; charset=utf-8") {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun formPage(form: Map<String, String>?, error: String?): String {
        val apiKey = form?.get("apiKey").orEmpty()
        val banner = if (error != null) "<p class=err>${escape(error)}</p>" else ""
        return """
            <!doctype html><html><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1">
            <title>candiamp · Last.fm</title>
            <style>$STYLE</style></head><body>
            <h1>candiamp</h1>
            <p class=sub>Link this TV to Last.fm.</p>
            $banner
            <form method=post action=/save>
              <label>Code shown on the TV</label>
              <input id=code type=text name=code inputmode=latin placeholder="ABCD" autocomplete=off required>
              <label>API key</label>
              <input type=text name=apiKey value="${escape(apiKey)}" autocomplete=off required>
              <label>Shared secret</label>
              <input type=password name=secret autocomplete=off required>
              <button type=submit>Save &amp; continue</button>
            </form>
            <p class=hint>Find both on your Last.fm API account page. If you don't have one, create it at
            last.fm/api/account/create and set its callback URL to https://candiamp.tv/lastfm.
            They stay on this TV; the secret is encrypted with the Android keystore.</p>
            </body></html>
        """.trimIndent()
    }

    private fun authorizePage(url: String): String = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>candiamp · Last.fm</title>
        <style>$STYLE</style></head><body>
        <h1>Authorize candiamp</h1>
        <p class=sub>Last.fm will ask you to let candiamp use your account. Nothing is scrobbled until then.</p>
        <a class=button href="${escape(url)}">Open Last.fm and authorize</a>
        <p id=status class=hint>Waiting for authorization…</p>
        <p class=hint><a href=/change>Use a different API key</a></p>
        <script>
          setInterval(async () => {
            try {
              const text = await (await fetch('/status', {cache: 'no-store'})).text();
              if (text.startsWith('linked:')) location.href = '/done';
              else if (text.startsWith('error:')) document.getElementById('status').textContent = text.slice(6);
              else document.getElementById('status').textContent = 'Waiting for authorization…';
            } catch (e) { /* the TV may have moved on */ }
          }, 2000);
        </script>
        </body></html>
    """.trimIndent()

    private fun retryingPage(): String = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <meta http-equiv="refresh" content="2;url=/">
        <title>candiamp · Last.fm</title><style>$STYLE</style></head><body>
        <h1>Trying again…</h1><p class=sub>Asking Last.fm for a new link.</p>
        </body></html>
    """.trimIndent()

    private fun donePage(): String = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>candiamp · Last.fm</title><style>$STYLE</style></head><body>
        <div class=tick>&#10003;</div>
        <h1>Connected</h1>
        <p class=sub>${escape(linkedUser.orEmpty())} is linked. You can put your phone down.</p>
        </body></html>
    """.trimIndent()

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    companion object {
        private const val STYLE = """
            :root{color-scheme:dark}
            body{margin:0;padding:24px;background:#0b0b0c;color:#f1f1f1;font:16px/1.5 -apple-system,system-ui,sans-serif}
            h1{font-size:20px;margin:0 0 4px}
            p.sub{color:#aaa;margin:0 0 20px;font-size:14px}
            label{display:block;font-size:13px;color:#aaa;margin:14px 0 4px}
            input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #333;background:#17171a;color:#f1f1f1;font-size:16px}
            input#code{text-transform:uppercase;letter-spacing:6px;font-size:22px;text-align:center}
            button,a.button{display:block;width:100%;box-sizing:border-box;margin-top:22px;padding:14px;border:0;border-radius:10px;background:#4aa8b8;color:#08191c;font-weight:700;font-size:16px;text-align:center;text-decoration:none}
            .err{background:#3a1414;border:1px solid #7a2a2a;padding:10px 12px;border-radius:10px;font-size:14px}
            .hint{color:#777;font-size:12px;margin-top:18px}
            .tick{font-size:44px;line-height:1;color:#4aa8b8}
        """
    }
}
