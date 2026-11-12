package tv.candiamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import tv.candiamp.data.Album
import tv.candiamp.data.Artist
import tv.candiamp.data.LastFmAlbum
import tv.candiamp.data.LastFmArtist
import tv.candiamp.data.LastFmCredentials
import tv.candiamp.data.LastFmPage
import tv.candiamp.data.LastFmPeriod
import tv.candiamp.data.LastFmRecentTrack
import tv.candiamp.data.LastFmStats
import tv.candiamp.data.LastFmTrack
import tv.candiamp.data.LastFmUser
import tv.candiamp.data.MusicLibrary
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LastFmStatsScreen(lastFm: LastFmCredentials, stats: LastFmStats, onOpenSettings: () -> Unit) {
    if (!lastFm.isLinked) {
        NotLinked(onOpenSettings)
        return
    }
    LinkedStats(stats)
}

@Composable
private fun NotLinked(onOpenSettings: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHead("Last.fm", subtitle = "Your listening, from Last.fm") }
        item { Status("Link your Last.fm account from Settings to see your top artists, albums and tracks.") }
        item { Pill("Open Settings", onClick = onOpenSettings, primary = true, modifier = Modifier.focusRequester(first)) }
    }
}

@Composable
private fun LinkedStats(stats: LastFmStats) {
    val library = LocalLibrary.current
    val openArtist = LocalOpenArtist.current
    val openAlbum = LocalOpenAlbum.current

    var period by remember { mutableStateOf(LastFmPeriod.ALL) }
    var page by remember { mutableStateOf<LastFmPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // The period row lives inside a lazy list; give the first layout a moment to place it.
        kotlinx.coroutines.delay(250)
        runCatching { first.requestFocus() }
            .onFailure { android.util.Log.w("candiamp", "stats focus failed", it) }
    }
    LaunchedEffect(period, reload) {
        loading = true
        error = null
        try {
            page = stats.load(period, force = forceNext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't read Last.fm."
        } finally {
            forceNext = false
            loading = false
        }
    }

    val record = page

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHead("Last.fm", subtitle = "Your listening, from Last.fm") }

        if (record != null) item { ProfileHeader(record.profile) }

        item {
            PeriodRow(
                period = period,
                first = first,
                onPeriod = { chosen ->
                    if (chosen != period) {
                        period = chosen
                        notice = null
                    }
                },
                onRefresh = {
                    forceNext = true
                    notice = null
                    reload++
                },
            )
        }

        notice?.let { item { Status(it) } }
        if (loading) item { Status("Reading Last.fm…") }
        if (!loading) {
            error?.let { item { Status("Last.fm: $it") } }
            if (error != null) {
                item {
                    Pill(
                        text = "Try again",
                        onClick = {
                            forceNext = true
                            reload++
                        },
                    )
                }
            }
        }

        if (record == null) {
            if (!loading && error == null) item { Status("No stats yet. Scrobble something, then come back.") }
        } else {
            item { SectionTitle("Top artists") }
            if (record.artists.isEmpty()) {
                item { Status("No top artists for this period.") }
            } else {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(start = 10.dp, end = 28.dp),
                    ) {
                        items(record.artists) { artist ->
                            val local = findLocalArtist(library, artist.name)
                            ArtistCard(
                                artist = artist,
                                inCollection = local != null,
                                onClick = {
                                    if (local != null) openArtist(local.id)
                                    else notice = "${artist.name} isn't in your Bandcamp collection."
                                },
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Top albums") }
            if (record.albums.isEmpty()) {
                item { Status("No top albums for this period.") }
            } else {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(start = 10.dp, end = 28.dp),
                    ) {
                        items(record.albums) { album ->
                            val local = findLocalAlbum(library, album)
                            AlbumCard(
                                album = album,
                                inCollection = local != null,
                                onClick = {
                                    if (local != null) openAlbum(local.id)
                                    else notice = "${album.name} isn't in your Bandcamp collection."
                                },
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Top tracks") }
            if (record.tracks.isEmpty()) {
                item { Status("No top tracks for this period.") }
            } else {
                itemsIndexed(record.tracks) { index, track ->
                    val local = findLocalArtist(library, track.artist)
                    TrackRow(
                        rank = index + 1,
                        track = track,
                        inCollection = local != null,
                        onClick = {
                            if (local != null) openArtist(local.id)
                            else notice = "${track.artist} isn't in your Bandcamp collection."
                        },
                    )
                }
            }

            item { SectionTitle("Recently played") }
            if (record.recent.isEmpty()) {
                item { Status("Nothing played yet.") }
            } else {
                itemsIndexed(record.recent) { _, recent ->
                    val local = findLocalArtist(library, recent.artist)
                    RecentRow(
                        track = recent,
                        inCollection = local != null,
                        onClick = {
                            if (local != null) openArtist(local.id)
                            else notice = "${recent.artist} isn't in your Bandcamp collection."
                        },
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Powered by Last.fm", style = Type.sub.copy(fontSize = 12.sp))
                record?.profile?.url?.let { url ->
                    Text(url, style = Type.sub.copy(fontSize = 12.sp))
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: LastFmUser) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatsImage(profile.imageUrl, Modifier.size(96.dp), profile.username, 36.sp)
        Column {
            PageHead(profile.username, subtitle = profile.realName)
            Text(
                buildString {
                    append(count(profile.playcount)).append(" scrobbles")
                    if (profile.artistCount > 0) append(" · ").append(count(profile.artistCount)).append(" artists")
                    profile.registeredAt?.let { append(" · since ").append(year(it)) }
                },
                style = Type.sub.copy(fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun PeriodRow(
    period: LastFmPeriod,
    first: FocusRequester,
    onPeriod: (LastFmPeriod) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LastFmPeriod.entries.forEach { item ->
            Pill(
                text = item.label,
                onClick = { onPeriod(item) },
                primary = item == period,
                modifier = if (item == period) Modifier.focusRequester(first) else Modifier,
            )
        }
        Pill("Refresh", onClick = onRefresh)
    }
}

@Composable
private fun ArtistCard(artist: LastFmArtist, inCollection: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(148.dp)) {
        StatsSurface(onClick = onClick) {
            Box {
                StatsImage(
                    artist.imageUrl,
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    artist.name,
                    30.sp,
                )
                if (inCollection) CollectionBadge(Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            artist.name,
            style = Type.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text("${count(artist.playcount)} scrobbles", style = Type.sub)
    }
}

@Composable
private fun AlbumCard(album: LastFmAlbum, inCollection: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(142.dp)) {
        StatsSurface(onClick = onClick) {
            Box {
                StatsImage(
                    album.imageUrl,
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    album.name,
                    30.sp,
                )
                if (inCollection) CollectionBadge(Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            album.name,
            style = Type.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(album.artist, style = Type.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${count(album.playcount)} scrobbles", style = Type.sub)
    }
}

@Composable
private fun TrackRow(rank: Int, track: LastFmTrack, inCollection: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Colors.bg2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Colors.bg3))),
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(count(rank.toLong()), style = Type.sub, modifier = Modifier.width(22.dp))
            StatsImage(track.imageUrl, Modifier.size(36.dp), track.name, 14.sp)
            Column(Modifier.weight(1f)) {
                Text(track.name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = Type.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inCollection) Text("artist in collection", style = Type.sub.copy(color = Colors.accent))
            Text("${count(track.playcount)}", style = Type.sub)
        }
    }
}

@Composable
private fun RecentRow(track: LastFmRecentTrack, inCollection: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Colors.bg2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Colors.bg3))),
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (track.nowPlaying) "Now" else playedOn(track.playedAt),
                style = Type.sub.copy(color = if (track.nowPlaying) Colors.accent else Colors.muted),
                modifier = Modifier.width(56.dp),
            )
            StatsImage(track.imageUrl, Modifier.size(36.dp), track.name, 14.sp)
            Column(Modifier.weight(1f)) {
                Text(track.name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.artist, track.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = Type.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (inCollection) Text("artist in collection", style = Type.sub.copy(color = Colors.accent))
        }
    }
}

@Composable
private fun StatsSurface(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Colors.bg2, focusedContainerColor = Colors.bg2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Colors.text))),
        content = { content() },
    )
}

@Composable
private fun StatsImage(url: String?, modifier: Modifier, letter: String?, letterSize: TextUnit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Colors.bg2),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (letter?.isNotBlank() == true) {
            Text(letter.take(1).uppercase(), color = Colors.muted, fontSize = letterSize)
        }
    }
}

@Composable
private fun CollectionBadge(modifier: Modifier = Modifier) {
    Text(
        "in collection",
        style = Type.badge,
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Type.section, modifier = Modifier.padding(top = 6.dp))
}

private fun findLocalArtist(library: MusicLibrary, name: String): Artist? =
    library.artists.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

private fun findLocalAlbum(library: MusicLibrary, album: LastFmAlbum): Album? {
    val exact = library.albums.firstOrNull {
        it.name.equals(album.name, ignoreCase = true) && it.artist.equals(album.artist, ignoreCase = true)
    }
    if (exact != null) return exact
    return library.albums.filter { it.name.equals(album.name, ignoreCase = true) }.singleOrNull()
}

private fun playedOn(epochSec: Long?): String =
    epochSec?.let { MONTH_DAY.format(Date(it * 1000L)) } ?: ""

private fun year(epochSec: Long): String = YEAR.format(Date(epochSec * 1000L))

private fun count(value: Int): String = count(value.toLong())

private fun count(value: Long): String = COUNT.format(value)

private val COUNT: NumberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())
private val MONTH_DAY = SimpleDateFormat("d MMM", Locale.US)
private val YEAR = SimpleDateFormat("yyyy", Locale.US)
