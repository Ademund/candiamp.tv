package tv.candiamp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.candiamp.data.Credentials
import tv.candiamp.data.LastFmCredentials
import tv.candiamp.data.MusicLibrary
import tv.candiamp.data.RadioLibrary
import tv.candiamp.playback.Playback

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Albums : Screen
    data object Artists : Screen
    data object Radio : Screen
    data object Settings : Screen
    data object LastFmSetup : Screen
    data class Record(val id: String) : Screen
    data class ArtistPage(val id: String) : Screen
    data class RadioShow(val id: String) : Screen
}

@Composable
fun App(credentials: Credentials, library: MusicLibrary, radio: RadioLibrary, lastFm: LastFmCredentials) {
    var connected by remember { mutableStateOf(credentials.isConfigured) }
    if (!connected) {
        SetupScreen(credentials) { connected = true }
        return
    }

    LaunchedEffect(connected) {
        if (library.isStale()) library.sync()
    }
    // Radio is a scraper and is allowed to fail on its own; it never blocks the collection.
    LaunchedEffect(connected) {
        if (radio.isStale()) radio.sync()
    }

    LibraryShell(credentials, library, radio, lastFm) { connected = false }
}

@Composable
private fun LibraryShell(
    credentials: Credentials,
    library: MusicLibrary,
    radio: RadioLibrary,
    lastFm: LastFmCredentials,
    onSignOut: () -> Unit,
) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var showPlayer by remember { mutableStateOf(false) }
    // Detail pages keep the section they were opened from, so the sidebar does not jump to Home.
    var section by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    CompositionLocalProvider(
        LocalLibrary provides library,
        LocalRadio provides radio,
        LocalOpenAlbum provides { id -> stack.add(Screen.Record(id)) },
        LocalOpenArtist provides { id -> stack.add(Screen.ArtistPage(id)) },
        LocalOpenRadioEpisode provides { id -> stack.add(Screen.RadioShow(id)) },
    ) {
        Box(Modifier.fillMaxSize().background(Colors.bg)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(current = if (stack.last() is Screen.Record || stack.last() is Screen.ArtistPage || stack.last() is Screen.RadioShow) section else stack.last(), onGo = { target ->
                    section = target
                    stack.clear()
                    stack.add(target)
                }, onOpenPlayer = { showPlayer = true })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val screen = stack.last()) {
                        Screen.Home -> HomeScreen()
                        Screen.Search -> SearchScreen()
                        Screen.Albums -> AlbumsScreen()
                        Screen.Artists -> ArtistsScreen()
                        Screen.Radio -> RadioScreen()
                        Screen.Settings -> SettingsScreen(
                            credentials = credentials,
                            lastFm = lastFm,
                            onSignOut = { onSignOut() },
                            onOpenLastFm = { stack.add(Screen.LastFmSetup) },
                        )
                        Screen.LastFmSetup -> LastFmSetupScreen(lastFm) { stack.removeAt(stack.lastIndex) }
                        is Screen.Record -> AlbumDetailScreen(screen.id)
                        is Screen.ArtistPage -> ArtistDetailScreen(screen.id)
                        is Screen.RadioShow -> RadioEpisodeScreen(screen.id)
                    }
                }
            }
            NowPlayingBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpen = { showPlayer = true },
            )
            if (showPlayer) NowPlayingScreen { showPlayer = false }
        }
    }
}

@Composable
private fun Sidebar(current: Screen, onGo: (Screen) -> Unit, onOpenPlayer: () -> Unit) {
    val first = remember { FocusRequester() }
    val entries = listOf(
        "Home" to Screen.Home,
        "Search" to Screen.Search,
        "Records" to Screen.Albums,
        "Artists" to Screen.Artists,
        "Radio" to Screen.Radio,
        "Settings" to Screen.Settings,
    )
    val playing = Playback.current

    Column(
        Modifier.width(168.dp).fillMaxHeight().background(Colors.bg).padding(horizontal = 10.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(Colors.accent, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("c", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Colors.accentDark) }
            Spacer(Modifier.width(12.dp))
            Text("candiamp", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Colors.text)
        }
        Spacer(Modifier.height(18.dp))

        for ((index, entry) in entries.withIndex()) {
            val (label, target) = entry
            val selected = when (target) {
                Screen.Home -> current is Screen.Home || current is Screen.Record || current is Screen.ArtistPage
                Screen.Search -> current is Screen.Search
                Screen.Albums -> current is Screen.Albums
                Screen.Artists -> current is Screen.Artists
                Screen.Radio -> current is Screen.Radio || current is Screen.RadioShow
                Screen.Settings -> current is Screen.Settings || current is Screen.LastFmSetup
                else -> false
            }
            SidebarItem(
                label = label,
                selected = selected,
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                onClick = { onGo(target) },
            )
        }

        if (playing != null) {
            Spacer(Modifier.height(10.dp))
            SidebarItem(
                label = if (Playback.playing) "Now playing  ▸" else "Now playing  ‖",
                selected = false,
                modifier = Modifier,
                onClick = onOpenPlayer,
            )
        }
    }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SidebarItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp).fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Colors.bg2 else Colors.bg,
            contentColor = if (selected) Colors.text else Colors.muted,
            focusedContainerColor = Colors.text,
            focusedContentColor = Colors.bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
        }
    }
}
