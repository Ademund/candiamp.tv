package tv.candiamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tv.candiamp.data.Album
import tv.candiamp.data.runtime
import tv.candiamp.playback.Playback

private val pagePadding = PaddingValues(start = 28.dp, end = 24.dp, top = 16.dp, bottom = 160.dp)

@Composable
fun HomeScreen() {
    val library = LocalLibrary.current
    val snapshot = library.snapshot
    val newest = remember(snapshot) { library.newest(20) }
    val shuffle = remember(snapshot) { library.shuffle(20) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item { PageHead("candiamp", subtitle = "Your Bandcamp collection") }
        when {
            library.syncing && snapshot == null -> item { Status(library.status.ifBlank { "Reading your collection…" }) }
            library.lastError != null && snapshot == null -> item { Status("Couldn't reach Bandcamp: ${library.lastError}") }
            snapshot == null || snapshot.albums.isEmpty() -> item {
                Status("Your collection is empty. Buy or collect something on Bandcamp, then sync from Settings.")
            }
            else -> {
                item { SectionRow("Recently added") { AlbumsRow(newest, first) } }
                item { SectionRow("Something else", "Shuffled every time you open the app") { AlbumsRow(shuffle) } }
                item { SectionRow("Artists") { ArtistsRow(snapshot.artists) } }
                if (library.syncing) item { Status(library.status.ifBlank { "Syncing…" }) }
            }
        }
    }
}

@Composable
fun AlbumsScreen() {
    val library = LocalLibrary.current
    val albums = library.albums
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHead("Records", subtitle = "${albums.size} in your collection")
        }
        if (albums.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Status("Nothing yet. Sync from Settings.") }
        }
        items(albums, key = { it.id }) { album ->
            AlbumCard(album, width = null, focusRequester = if (album.id == albums.first().id) first else null)
        }
    }
}

@Composable
fun ArtistsScreen() {
    val library = LocalLibrary.current
    val artists = library.artists
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHead("Artists", subtitle = "${artists.size} in your collection")
        }
        if (artists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Status("Nothing yet. Sync from Settings.") }
        }
        items(artists, key = { it.id }) { artist ->
            ArtistCard(artist, width = null, focusRequester = if (artist.id == artists.first().id) first else null)
        }
    }
}

@Composable
fun ArtistDetailScreen(artistId: String) {
    val library = LocalLibrary.current
    val artist = remember(artistId, library.snapshot) { library.artist(artistId) }
    if (artist == null) {
        Status("That artist is not in your collection.")
        return
    }
    val albums = remember(artist, library.snapshot) {
        artist.albums.ifEmpty { library.byArtist(artistId) }
    }
    val first = remember(artistId) { FocusRequester() }
    LaunchedEffect(artistId) { runCatching { first.requestFocus() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHead(
                artist.name,
                crumb = if (artist.isLabel) "Label" else "Artist",
                subtitle = when {
                    artist.isLabel && albums.size == 1 -> "1 record from the artists on this label"
                    artist.isLabel -> "${albums.size} records from the artists on this label"
                    albums.size == 1 -> "1 record"
                    else -> "${albums.size} records"
                },
            )
        }
        items(albums, key = { it.id }) { album ->
            AlbumCard(album, width = null, focusRequester = if (album.id == albums.first().id) first else null)
        }
    }
}

@Composable
fun AlbumDetailScreen(albumId: String) {
    val library = LocalLibrary.current
    val context = LocalContext.current
    var album by remember(albumId) { mutableStateOf(library.album(albumId)) }
    var loading by remember(albumId) { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        val known = library.album(albumId) ?: return@LaunchedEffect
        album = known
        // Unplayable entries are known from the sync; opening them would only ask Bandcamp for
        // something it has already said it does not have.
        if (known.songs.isEmpty() && !known.unavailable) {
            loading = true
            album = runCatching { library.albumWithSongs(known) }.getOrDefault(known)
            loading = false
        }
    }

    val record = album
    if (record == null) {
        Status("That record is not in your collection.")
        return
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(record.id, record.songs.size) {
        if (record.songs.isNotEmpty()) runCatching { playFocus.requestFocus() }
    }
    val coverUrl = remember(record.coverArt) { library.coverUrl(record.coverArt) }
    val songs = record.songs
    val now = Playback.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                CoverArt(record.coverArt, Modifier.size(260.dp), letter = record.name)
                Column(Modifier.weight(1f)) {
                    PageHead(
                        record.name,
                        crumb = record.artist,
                        subtitle = listOfNotNull(
                            if (songs.isNotEmpty()) "${songs.size} tracks" else null,
                            record.runtime().ifBlank { null },
                            record.genre?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                    )
                    if (songs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                            Pill("Play", primary = true, modifier = Modifier.focusRequester(playFocus), onClick = {
                                Playback.playRecord(context, record, songs, 0, coverUrl, library::streamUrl)
                            })
                            Pill("Shuffle", onClick = {
                                val shuffled = songs.shuffled()
                                Playback.playRecord(context, record, shuffled, 0, coverUrl, library::streamUrl)
                            })
                        }
                    }
                }
            }
        }
        if (loading) item { Status("Loading the tracks…") }
        if (!loading && songs.isEmpty()) item {
            Status(
                when {
                    record.unavailable && record.songCount == 0 ->
                        "Nothing to stream: Bandcamp lists this release without audio, which is how vinyl-only items appear in a collection."

                    record.unavailable ->
                        "Nothing to stream: this is a single track collected on its own. Bandcamp lists it as a one-track item, " +
                            "but its API has no album or track behind it to open — the website plays it from the release page."

                    else -> "Nothing to stream: Bandcamp returned no tracks for this record."
                },
            )
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            val playing = now?.albumId == record.id && now.title == song.title
            TrackRow(song, "${song.track ?: index + 1}", playing) {
                Playback.playRecord(context, record, songs, index, coverUrl, library::streamUrl)
            }
        }
    }
}
