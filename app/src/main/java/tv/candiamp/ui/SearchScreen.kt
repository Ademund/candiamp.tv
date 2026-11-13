package tv.candiamp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.candiamp.data.SearchHit
import tv.candiamp.data.Song
import tv.candiamp.playback.Playback

private val SEARCH_PADDING = PaddingValues(start = 28.dp, end = 24.dp, top = 16.dp, bottom = 160.dp)

/**
 * `search3`, with a keyboard because a TV remote has no letters on it. The search covers the
 * collection only: Bandcamp's API has no catalogue-wide search.
 */
@Composable
fun SearchScreen() {
    val library = LocalLibrary.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var hit by remember { mutableStateOf<SearchHit?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    val firstKey = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            hit = null
            failure = null
            searching = false
            return@LaunchedEffect
        }
        delay(220)
        searching = true
        failure = null
        runCatching { library.search(query) }
            .onSuccess { hit = it }
            .onFailure { failure = it.message ?: "Search failed." }
        searching = false
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = SEARCH_PADDING,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            PageHead(
                "Search",
                subtitle = if (query.isBlank()) "Your collection — type with the remote" else "“$query”",
            )
        }

        item {
            Keyboard(
                firstKey = firstKey,
                onLetter = { letter -> if (query.length < 40) query += letter },
                onSpace = { if (query.length < 40 && query.isNotEmpty()) query += " " },
                onDelete = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
        }

        when {
            searching -> item { Status("Searching…") }
            failure != null -> item { Status("Search failed: $failure") }
            query.isBlank() -> item {
                Status("Search by artist, record or track name. This searches the records you have collected or bought — Bandcamp has no catalogue-wide search.")
            }
            hit?.isEmpty == true -> item { Status("Nothing in your collection matches “$query”.") }
            hit != null -> {
                val results = hit!!
                // search3 returns bare artist/album stubs — no album lists, and the shared `ci:`
                // artwork — so prefer the copy the library already knows.
                val artists = results.artists.map { stub -> library.artist(stub.id) ?: stub }
                val albums = results.albums.map { stub -> library.album(stub.id) ?: stub }
                if (artists.isNotEmpty()) {
                    item { SectionRow("Artists") { ArtistsRow(artists) } }
                }
                if (albums.isNotEmpty()) {
                    item { SectionRow("Records") { AlbumsRow(albums) } }
                }
                if (results.songs.isNotEmpty()) {
                    item {
                        SectionRow("Tracks", "${results.songs.size} matching") {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                for ((index, song) in results.songs.withIndex()) {
                                    val playing = Playback.current?.title == song.title
                                    val metadata = listOfNotNull(
                                        song.artistName?.takeIf { it.isNotBlank() },
                                        song.albumName?.takeIf { it.isNotBlank() },
                                    ).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
                                    TrackRow(
                                        song = song,
                                        number = "${index + 1}",
                                        playing = playing,
                                        metadata = metadata,
                                        onClick = {
                                            Playback.playSongs(
                                                context = context,
                                                songs = results.songs,
                                                startIndex = index,
                                                streamUrl = library::streamUrl,
                                                artwork = { s -> library.coverUrl(s.coverArt) },
                                            )
                                        },
                                        onLongClick = { actionsSong = song },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    actionsSong?.let { song ->
        val songs = hit?.songs.orEmpty()
        val index = songs.indexOfFirst { it.id == song.id }
        TrackActions(
            song = song,
            albumTitle = song.albumName,
            onPlay = {
                actionsSong = null
                if (index >= 0) {
                    Playback.playSongs(
                        context = context,
                        songs = songs,
                        startIndex = index,
                        streamUrl = library::streamUrl,
                        artwork = { s -> library.coverUrl(s.coverArt) },
                    )
                }
            },
            onPlayNext = {
                actionsSong = null
                Playback.playNext(
                    context = context,
                    album = null,
                    songs = listOf(song),
                    artwork = { s -> library.coverUrl(s.coverArt) },
                    streamUrl = library::streamUrl,
                )
            },
            onAddToQueue = {
                actionsSong = null
                Playback.enqueue(
                    context = context,
                    album = null,
                    songs = listOf(song),
                    artwork = { s -> library.coverUrl(s.coverArt) },
                    streamUrl = library::streamUrl,
                )
            },
            onDismiss = { actionsSong = null },
        )
    }
}

@Composable
private fun Keyboard(
    firstKey: FocusRequester,
    onLetter: (String) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((rowIndex, row) in rows.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((letterIndex, letter) in row.withIndex()) {
                    Key(
                        label = letter.toString(),
                        focusRequester = if (rowIndex == 0 && letterIndex == 0) firstKey else null,
                        onClick = { onLetter(letter.toString()) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("space", null) { onSpace() }
            Key("delete", null) { onDelete() }
            Key("clear", null) { onClear() }
        }
    }
}

@Composable
private fun Key(label: String, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).height(42.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.bg2,
            contentColor = Colors.text,
            focusedContainerColor = Colors.text,
            focusedContentColor = Colors.bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Colors.accent), shape = RoundedCornerShape(8.dp)),
        ),
    ) {
        Box(
            Modifier.widthIn(min = 42.dp).fillMaxHeight().padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
