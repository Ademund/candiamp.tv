package tv.candiamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.candiamp.data.Credentials
import tv.candiamp.pairing.PairingServer
import java.io.File

/**
 * First run: the TV cannot ask for a password with a remote, so the phone types it.
 */
@Composable
fun SetupScreen(credentials: Credentials, onConnected: () -> Unit) {
    val context = LocalContext.current
    val server = remember { PairingServer(PairingServer.makeCode()) { s, u, p -> credentials.save(s, u, p) } }
    val addresses = remember { PairingServer.localAddresses() }
    var imported by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { server.start() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { server.stop() } }
    LaunchedEffect(server.saved) { if (server.saved) onConnected() }

    Row(Modifier.fillMaxSize().background(Colors.bg).padding(horizontal = 72.dp, vertical = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text("candiamp", style = Type.hero)
            Spacer(Modifier.height(6.dp))
            Text(
                "Your Bandcamp collection, on the TV. This is an unofficial client that uses the Subsonic API Bandcamp added in July 2026.",
                style = Type.sub.copy(fontSize = 14.sp),
                modifier = Modifier.width(620.dp),
            )

            Spacer(Modifier.height(32.dp))
            Step("1", "Open this address on a phone on the same Wi-Fi")
            Spacer(Modifier.height(8.dp))
            if (server.running) {
                addresses.ifEmpty { listOf("<this TV's address>") }.forEach { ip ->
                    Text("http://$ip:${server.port}", style = Type.section.copy(fontSize = 22.sp, color = Colors.accent))
                }
            } else {
                Text("Couldn't open a port. Restart the app, or use the import below.", style = Type.sub)
            }

            Spacer(Modifier.height(28.dp))
            Step("2", "Type the code from the page into this field")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.background(Colors.bg2, RoundedCornerShape(12.dp)).padding(horizontal = 28.dp, vertical = 12.dp)) {
                Text(if (server.running) server.code else "----", style = Type.code)
            }

            Spacer(Modifier.height(28.dp))
            Step("3", "Paste the Subsonic credentials from Bandcamp")
            Text(
                "bandcamp.com → Settings → Fan → Subsonic → generate credentials. They never leave your home network.",
                style = Type.sub,
                modifier = Modifier.width(620.dp).padding(top = 6.dp),
            )

            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill(if (server.running) "Waiting for your phone…" else "Retrying…", onClick = { if (!server.running) server.start() })
                Pill("Import a file instead", onClick = {
                    imported = importFile(credentials, context.getExternalFilesDir(null), File("/sdcard/Download/candiamp.txt"))
                    if (imported == "ok") onConnected()
                })
            }
            if (imported != null && imported != "ok") {
                Text(imported.orEmpty(), style = Type.sub, modifier = Modifier.padding(top = 10.dp))
            }
        }

        Column(Modifier.width(360.dp).padding(start = 40.dp), verticalArrangement = Arrangement.Center) {
            Text("Why this way?", style = Type.section)
            Spacer(Modifier.height(10.dp))
            Text(
                "A TV has no keyboard worth using. The pairing page is served by the TV itself, on your own network — there is no server in the middle.",
                style = Type.sub.copy(lineHeight = 21.sp),
            )
            Spacer(Modifier.height(18.dp))
            Text("Not on Wi-Fi? Push a file instead:", style = Type.section.copy(fontSize = 15.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "candiamp.txt with three lines — server, username, password — into Download on the TV.",
                style = Type.sub,
            )
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.background(Colors.accent, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Text(number, style = Type.sub.copy(color = Colors.accentDark, fontSize = 14.sp)) }
        Text(text, style = Type.section.copy(fontSize = 17.sp))
    }
}

private fun importFile(credentials: Credentials, privateDir: File?, download: File): String? {
    val candidates = listOfNotNull(privateDir?.resolve("candiamp.txt"), download)
    val file = candidates.firstOrNull { it.exists() } ?: return "No candiamp.txt found in Download."
    return runCatching {
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return "candiamp.txt needs three lines: server, username, password."
        credentials.save(lines[0], lines[1], lines[2])
        "ok"
    }.getOrElse { "Couldn't read candiamp.txt: ${it.message}" }
}
