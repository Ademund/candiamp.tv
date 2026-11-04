package tv.candiamp.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * The phone half of setup. The TV shows a short code and its LAN address; you open that address on
 * your phone, type the code and your Bandcamp Subsonic credentials, and the TV picks them up.
 * Nothing leaves the local network.
 */
class PairingServer(val code: String, private val onSave: (String, String, String) -> Unit) {

    var running by mutableStateOf(false)
        private set
    var port by mutableStateOf(0)
        private set
    var saved by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
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
                android.util.Log.i("candiamp", "pairing page on port $candidate, code $code")
                acceptor = Thread { accept(server) }.apply { isDaemon = true; start() }
                return true
            } catch (e: Exception) {
                // port taken, try the next one
            }
        }
        message = "Couldn't open a port for the pairing page."
        return false
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        acceptor = null
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

                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        respond(connection.getOutputStream(), 200, page(form = null))

                    method == "POST" && path == "/save" -> {
                        val typedCode = form["code"].orEmpty().trim()
                        val server = form["server"].orEmpty().trim()
                        val user = form["user"].orEmpty().trim()
                        val password = form["password"].orEmpty()
                        when {
                            typedCode.uppercase() != code.uppercase() ->
                                respond(connection.getOutputStream(), 403, page(form = form, error = "That code doesn't match the one on the TV."))

                            server.isBlank() || user.isBlank() || password.isBlank() ->
                                respond(connection.getOutputStream(), 400, page(form = form, error = "Fill in all three fields."))

                            else -> {
                                onSave(server, user, password)
                                saved = true
                                respond(connection.getOutputStream(), 200, done())
                            }
                        }
                    }

                    else -> respond(connection.getOutputStream(), 404, page(form = null, error = "Nothing here."))
                }
            }
        }
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

    private fun respond(out: OutputStream, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(if (status == 200) "OK" else "Error").append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun page(form: Map<String, String>?, error: String? = null): String {
        val server = form?.get("server")?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        val user = form?.get("user").orEmpty()
        val banner = if (error != null) "<p class=err>${escape(error)}</p>" else ""
        return """
            <!doctype html><html><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1">
            <title>candiamp setup</title>
            <style>
              :root{color-scheme:dark}
              body{margin:0;padding:24px;background:#0b0b0c;color:#f1f1f1;font:16px/1.5 -apple-system,system-ui,sans-serif}
              h1{font-size:20px;margin:0 0 4px}
              p.sub{color:#aaa;margin:0 0 20px;font-size:14px}
              label{display:block;font-size:13px;color:#aaa;margin:14px 0 4px}
              input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #333;background:#17171a;color:#f1f1f1;font-size:16px}
              input[type=text]#code{text-transform:uppercase;letter-spacing:6px;font-size:22px;text-align:center}
              button{width:100%;margin-top:22px;padding:14px;border:0;border-radius:10px;background:#4aa8b8;color:#08191c;font-weight:700;font-size:16px}
              .err{background:#3a1414;border:1px solid #7a2a2a;padding:10px 12px;border-radius:10px;font-size:14px}
              .hint{color:#777;font-size:12px;margin-top:18px}
            </style></head><body>
            <h1>candiamp</h1>
            <p class=sub>Connect this TV to your Bandcamp collection.</p>
            $banner
            <form method=post action=/save>
              <label>Code shown on the TV</label>
              <input id=code type=text name=code inputmode=latin placeholder="ABCD" autocomplete=off required>
              <label>Server</label>
              <input type=text name=server value="${escape(server)}" required>
              <label>Subsonic username</label>
              <input type=text name=user value="${escape(user)}" autocomplete=username required>
              <label>Subsonic password</label>
              <input type=password name=password autocomplete=current-password required>
              <button type=submit>Connect</button>
            </form>
            <p class=hint>Find these in Bandcamp: Settings &rarr; Fan &rarr; Subsonic &rarr; generate credentials.
            Everything stays on your home network.</p>
            </body></html>
        """.trimIndent()
    }

    private fun done(): String = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>candiamp</title>
        <style>:root{color-scheme:dark}
        body{margin:0;padding:48px 24px;background:#0b0b0c;color:#f1f1f1;font:16px/1.6 -apple-system,system-ui,sans-serif;text-align:center}
        h1{font-size:22px;margin:0 0 8px}p{color:#aaa}
        .tick{font-size:44px;line-height:1;color:#4aa8b8}</style></head>
        <body><div class=tick>&#10003;</div>
        <h1>Connected</h1>
        <p>You can put your phone down. The TV is loading your collection.</p>
        </body></html>
    """.trimIndent()

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    companion object {
        const val DEFAULT_SERVER = "https://bandcamp.com/api/subsonic"

        fun makeCode(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return (1..4).map { alphabet.random() }.joinToString("")
        }

        /** The addresses a phone on the same network can reach. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
