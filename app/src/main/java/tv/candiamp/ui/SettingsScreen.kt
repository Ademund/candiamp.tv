package tv.candiamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.candiamp.data.Credentials
import tv.candiamp.data.LastFmCredentials
import tv.candiamp.data.SubsonicClient
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    credentials: Credentials,
    lastFm: LastFmCredentials,
    onSignOut: () -> Unit,
    onOpenLastFm: () -> Unit,
) {
    val library = LocalLibrary.current
    val scope = rememberCoroutineScope()
    val snapshot = library.snapshot
    val server = credentials.server.orEmpty()
    val host = SubsonicClient.hostOf(server).ifBlank { server }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 40.dp, top = 16.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHead("Settings", subtitle = "candiamp is an unofficial Bandcamp client") }

        item { Fact("Server", host) }
        item { Fact("Account", credentials.user.orEmpty()) }
        item { Fact("Records", "${library.albums.size}") }
        item { Fact("Artists", "${library.artists.size}") }
        item {
            val when_ = snapshot?.syncedAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            Fact("Last sync", when_ ?: "never")
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                Pill(if (library.syncing) "Syncing…" else "Sync now", primary = true, onClick = {
                    if (!library.syncing) scope.launch { library.sync() }
                })
                Pill("Sign out", onClick = {
                    library.clear()
                    credentials.clear()
                    onSignOut()
                })
            }
        }

        if (library.syncing) item { Status(library.status.ifBlank { "Reading your collection…" }) }
        library.lastError?.let { error -> item { Status("Last sync failed: $error") } }

        item {
            Column(Modifier.padding(top = 26.dp)) {
                Text("Last.fm", style = Type.section)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !lastFm.isConfigured -> "Not configured. Add your Last.fm API key from a phone, then link your account."
                        lastFm.isLinked -> "Connected as ${lastFm.username}."
                        else -> "Your API key is set. Link your Last.fm account to finish."
                    },
                    style = Type.sub.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    modifier = Modifier.padding(end = 240.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill(
                        text = if (lastFm.isConfigured) "Manage Last.fm" else "Set up Last.fm",
                        onClick = onOpenLastFm,
                        primary = true,
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(top = 26.dp)) {
                Text("About", style = Type.section)
                Spacer(Modifier.height(8.dp))
                Text(
                    "candiamp streams the records you have bought or collected on Bandcamp, using the Subsonic " +
                        "endpoint Bandcamp published in July 2026. It keeps your credentials on this TV, in the " +
                        "Android keystore, and talks to bandcamp.com directly. Last.fm is optional and stays off " +
                        "until you link an account.",
                    style = Type.sub.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    modifier = Modifier.padding(end = 240.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("Not affiliated with Bandcamp. Powered by Last.fm when linked.", style = Type.sub.copy(fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = Type.sub)
        Text(value, style = Type.body.copy(fontSize = 16.sp))
    }
}
