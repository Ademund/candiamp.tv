package tv.candiamp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.candiamp.data.Album
import tv.candiamp.data.Artist
import tv.candiamp.data.MusicLibrary
import tv.candiamp.data.RadioLibrary
import tv.candiamp.data.Song
import tv.candiamp.data.durationText

val LocalLibrary = staticCompositionLocalOf<MusicLibrary> { error("No library") }
val LocalRadio = staticCompositionLocalOf<RadioLibrary> { error("No radio") }
val LocalOpenAlbum = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalOpenArtist = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalOpenRadioEpisode = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun CoverArt(coverArt: String?, modifier: Modifier = Modifier, letter: String? = null) {
    val url = LocalLibrary.current.coverUrl(coverArt)
    Box(modifier.background(Colors.bg2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        } else if (letter != null) {
            Text(letter.take(1).uppercase(), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Colors.muted)
        }
    }
}

@Composable
fun AlbumCard(album: Album, modifier: Modifier = Modifier, width: Dp? = 200.dp, focusRequester: FocusRequester? = null) {
    val open = LocalOpenAlbum.current
    Column(modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))) {
        Surface(
            onClick = { open(album.id) },
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Colors.bg2, focusedContainerColor = Colors.bg2),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Colors.text))),
        ) {
            Box {
                CoverArt(album.coverArt, Modifier.fillMaxWidth().aspectRatio(1f), letter = album.name)
                if (album.unavailable) {
                    Text(
                        "no audio",
                        style = Type.badge,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(album.name, style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(album.artist ?: "", style = Type.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ArtistCard(artist: Artist, modifier: Modifier = Modifier, width: Dp? = 160.dp, focusRequester: FocusRequester? = null) {
    val open = LocalOpenArtist.current
    Column(
        modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = { open(artist.id) },
            modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (width != null) Modifier.size(width) else Modifier.fillMaxWidth().aspectRatio(1f)),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Colors.bg2, focusedContainerColor = Colors.bg2),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Colors.text), shape = CircleShape)),
        ) {
            CoverArt(
                artist.coverArt,
                Modifier.fillMaxSize().clip(CircleShape),
                letter = artist.name,
            )
        }
        Text(
            artist.name,
            style = Type.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            when {
                artist.isLabel && artist.albums.size == 1 -> "Label · 1 record"
                artist.isLabel -> "Label · ${artist.albums.size} records"
                artist.albums.size == 1 -> "1 record"
                else -> "${artist.albums.size} records"
            },
            style = Type.sub,
        )
    }
}

@Composable
fun TrackRow(
    song: Song,
    number: String,
    playing: Boolean,
    metadata: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Colors.bg2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Colors.bg3))),
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (metadata == null) 52.dp else 60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(number, style = Type.sub.copy(color = if (playing) Colors.accent else Colors.muted), modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = Type.body.copy(color = if (playing) Colors.accent else Colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (metadata != null) {
                    Text(metadata, style = Type.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(durationText(song.durationSec), style = Type.sub)
        }
    }
}

@Composable
fun SectionRow(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column {
        Text(title, style = Type.section)
        if (subtitle != null) Text(subtitle, style = Type.sub, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun AlbumsRow(albums: List<Album>, firstFocus: FocusRequester? = null) {
    if (albums.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // room for the focus ring, which grows past the card
        contentPadding = PaddingValues(start = 10.dp, end = 28.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album, focusRequester = if (album === albums.first()) firstFocus else null)
        }
    }
}

@Composable
fun ArtistsRow(artists: List<Artist>, firstFocus: FocusRequester? = null) {
    if (artists.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 28.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            ArtistCard(artist, focusRequester = if (artist === artists.first()) firstFocus else null)
        }
    }
}

/** The title of a page. Plain text: focus belongs on things you can act on. */
@Composable
fun PageHead(title: String, subtitle: String? = null, crumb: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        if (crumb != null) Text(crumb, style = Type.sub.copy(fontSize = 13.sp))
        Text(title, style = Type.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = Type.sub.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Colors.accent else Colors.bg2,
            contentColor = if (primary) Colors.accentDark else Colors.text,
            focusedContainerColor = if (primary) Colors.accent else Colors.bg3,
            focusedContentColor = if (primary) Colors.accentDark else Colors.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Colors.text), shape = CircleShape)),
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun Status(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.sub.copy(fontSize = 15.sp), modifier = modifier.fillMaxWidth().padding(vertical = 28.dp))
}
