package tv.candiamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.candiamp.data.LastFmCredentials
import tv.candiamp.data.LastFmException
import tv.candiamp.pairing.LastFmServer
import tv.candiamp.pairing.PairingServer

/**
 * Last.fm setup from the TV's point of view: show the LAN address and code, let the phone post
 * the API key and secret, then poll `auth.getSession` while the phone authorizes the app.
 */
@Composable
fun LastFmSetupScreen(credentials: LastFmCredentials, onClose: () -> Unit) {
    val server = remember { LastFmServer(credentials) }
    val addresses = remember { PairingServer.localAddresses() }

    var linked by remember { mutableStateOf(credentials.isLinked) }
    var linkedUser by remember { mutableStateOf(credentials.username) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(linked) { runCatching { first.requestFocus() } }

    fun startServer() {
        error = null
        if (!server.start()) error = server.message ?: "Couldn't open the Last.fm setup page."
    }

    fun forgetKey() {
        server.stop()
        credentials.clearAll()
        linked = false
        linkedUser = null
        error = null
    }

    LaunchedEffect(Unit) {
        if (!credentials.isLinked) startServer()
    }
    DisposableEffect(Unit) { onDispose { server.stop() } }

    // The phone has been authorized; turn the token into a session key. Error 14 simply means
    // the viewer has not pressed "authorize" yet.
    LaunchedEffect(server.authUrl) {
        val token = server.token ?: return@LaunchedEffect
        val client = credentials.client() ?: return@LaunchedEffect
        while (true) {
            try {
                val session = withContext(Dispatchers.IO) { client.session(token) }
                credentials.saveSession(session.username, session.key)
                linked = true
                linkedUser = session.username
                error = null
                server.markLinked(session.username)
                break
            } catch (e: LastFmException) {
                if (e.code == 14) {
                    delay(2000)
                    continue
                }
                error = if (e.code == 15) {
                    "That code expired. Reload the page on your phone."
                } else {
                    e.message ?: "Last.fm didn't accept the authorization."
                }
                server.fail(error.orEmpty())
                break
            } catch (e: Exception) {
                error = e.message ?: "Last.fm didn't answer."
                server.fail(error.orEmpty())
                break
            }
        }
    }

    val configured = server.hasKeys || credentials.isConfigured
    val problem = error ?: server.message

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHead("Last.fm", subtitle = "Scrobbling and stats, only if you want them") }

        if (linked) {
            item { Field("Account", linkedUser.orEmpty()) }
            credentials.maskedKey()?.let { key -> item { Field("API key", key) } }
            item {
                Text(
                    "This TV is linked. The scrobbling update will use this account; nothing is sent yet.",
                    style = Type.sub.copy(fontSize = 13.sp, lineHeight = 19.sp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Pill("Done", onClick = onClose, primary = true, modifier = Modifier.focusRequester(first))
                    Pill("Reconnect", onClick = {
                        server.stop()
                        credentials.clearSession()
                        linked = false
                        linkedUser = null
                        error = null
                        startServer()
                    })
                    Pill("Disconnect", onClick = {
                        server.stop()
                        credentials.clearSession()
                        linked = false
                        linkedUser = null
                        error = null
                    })
                }
            }
            item {
                Pill("Forget API key", onClick = { forgetKey() }, modifier = Modifier.padding(top = 4.dp))
            }
            item { Attribution(linkedUser) }
        } else {
            item {
                Status(
                    if (configured) {
                        "Your API key is saved. Link Last.fm from the page on your phone."
                    } else {
                        "Add your Last.fm API key and shared secret from a phone on the same Wi-Fi."
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (!server.running) {
                        Pill("Try again", onClick = { startServer() }, primary = true)
                    }
                    Pill("Done", onClick = onClose, modifier = Modifier.focusRequester(first))
                    if (configured) {
                        Pill("Forget API key", onClick = { forgetKey() })
                    }
                }
            }

            if (server.running) {
                item { Step("1", "Open this address on a phone on the same Wi-Fi") }
                if (addresses.isEmpty()) {
                    item { Address("<this TV's address>", server.port) }
                } else {
                    addresses.forEach { ip -> item { Address("http://$ip", server.port) } }
                }
                item { Step("2", "Type this code on the page") }
                item {
                    Box(
                        Modifier.background(Colors.bg2, RoundedCornerShape(12.dp))
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                    ) {
                        Text(server.code, style = Type.code)
                    }
                }
                item { Step("3", "Paste your API key and shared secret, then authorize") }
                item {
                    Text(
                        "The page explains where to find both. They stay on this TV; the secret is " +
                            "encrypted with the Android keystore. Nothing is scrobbled until you authorize.",
                        style = Type.sub.copy(fontSize = 13.sp, lineHeight = 19.sp),
                    )
                }
                if (server.authUrl != null) {
                    item {
                        Text(
                            "Waiting for Last.fm…  Tap “Open Last.fm and authorize” on the phone.",
                            style = Type.body.copy(color = Colors.accent),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                item { Status(problem ?: "Opening the setup page…") }
            }

            if (problem != null && server.running) item { Status(problem) }
            item { Attribution() }
        }
    }
}

@Composable
private fun Address(host: String, port: Int) {
    val shown = if (host.startsWith("http")) "$host:$port" else "http://$host:$port"
    Text(shown, style = Type.section.copy(fontSize = 22.sp, color = Colors.accent))
}

@Composable
private fun Step(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Box(
            Modifier.background(Colors.accent, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Text(number, style = Type.sub.copy(color = Colors.accentDark, fontSize = 14.sp)) }
        Text(text, style = Type.section.copy(fontSize = 17.sp))
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = Type.sub)
        Text(value, style = Type.body.copy(fontSize = 16.sp))
    }
}

@Composable
private fun Attribution(username: String? = null) {
    Spacer(Modifier.height(8.dp))
    Text(
        if (username.isNullOrBlank()) "Powered by Last.fm"
        else "Powered by Last.fm · last.fm/user/$username",
        style = Type.sub.copy(fontSize = 12.sp),
    )
}
