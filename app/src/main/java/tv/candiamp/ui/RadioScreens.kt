package tv.candiamp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import tv.candiamp.data.RadioEpisode
import tv.candiamp.data.RadioTrack
import tv.candiamp.data.durationText
import tv.candiamp.playback.Playback
import java.text.SimpleDateFormat
import java.util.Locale

private val radioDateIn = SimpleDateFormat("dd MMM yyyy HH:mm:ss z", Locale.US)
private val radioDateOut = SimpleDateFormat("dd MMM yyyy", Locale.US)

/** `09 Nov 2026 00:00:00 GMT` → `09 Nov 2026`. */
private fun radioDate(raw: String?): String? = raw?.let {
    runCatching { radioDateIn.parse(it)?.let(radioDateOut::format) }.getOrNull()
}

@Composable
fun RadioScreen() {
    val radio = LocalRadio.current
    val open = LocalOpenRadioEpisode.current
    val shows = radio.shows
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(Unit) {
        // TVs stay on for days; don't rely on launch time alone to notice a new episode.
        if (radio.isStale() && !radio.syncing) radio.sync()
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHead("Radio", subtitle = "Bandcamp Radio · free, curated, new shows every week")
        }
        if (shows.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Status(
                        when {
                            radio.syncing -> radio.status.ifBlank { "Reading Bandcamp Radio…" }
                            radio.lastError != null -> "Couldn't reach Bandcamp Radio: ${radio.lastError}"
                            else -> "No episodes right now. Bandcamp Radio is free — try again later."
                        },
                    )
                    if (!radio.syncing) {
                        Pill(
                            text = "Try again",
                            onClick = { scope.launch { radio.sync() } },
                            modifier = Modifier.focusRequester(first),
                        )
                    }
                }
            }
        }
        items(shows, key = { it.id }) { episode ->
            RadioCard(episode, if (episode.id == shows.first().id) first else null) { open(episode.id) }
        }
        if (shows.isNotEmpty() && radio.lastError != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Status("Showing the cached list. ${radio.lastError}")
            }
        }
    }
}

@Composable
private fun RadioCard(episode: RadioEpisode, focusRequester: FocusRequester?, onOpen: () -> Unit) {
    Column {
        Surface(
            onClick = onOpen,
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Colors.bg2, focusedContainerColor = Colors.bg2),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Colors.text))),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)).background(Colors.bg2),
            ) {
                AsyncImage(
                    model = episode.artUrl(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            episode.title,
            style = Type.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(episode.show, radioDate(episode.date)).joinToString(" · "),
            style = Type.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (episode.durationSec > 0) Text(durationText(episode.durationSec), style = Type.sub)
    }
}

/** One episode: the compiled stream on Play, and the timestamped tracklist below it. */
@Composable
fun RadioEpisodeScreen(episodeId: String) {
    val radio = LocalRadio.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var episode by remember(episodeId) { mutableStateOf(radio.cached(episodeId)) }
    var loading by remember(episodeId) { mutableStateOf(episode == null) }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }
    var starting by remember(episodeId) { mutableStateOf(false) }

    LaunchedEffect(episodeId) {
        val known = radio.cached(episodeId)
        if (known != null) {
            episode = known
            loading = false
        }
        val fresh = radio.episode(episodeId)
        if (fresh != null) episode = fresh
        if (fresh == null) error = radio.lastError ?: "Couldn't read this episode."
        loading = false
    }

    val playFocus = remember(episodeId) { FocusRequester() }
    LaunchedEffect(episodeId, episode?.tracks?.size) { runCatching { playFocus.requestFocus() } }

    val now = Playback.current
    val playingThis = now?.albumId == Playback.RADIO_PREFIX + episodeId
    val positionSec = if (playingThis) (Playback.positionMs / 1000).toInt() else -1
    val currentTrackIndex =
        if (playingThis) episode?.tracks?.indexOfLast { it.timecodeSec <= positionSec } ?: -1 else -1
    val activeTrack = episode?.tracks?.getOrNull(currentTrackIndex)

    val record = episode

    fun start(atSec: Int) {
        if (starting) return
        val current = record ?: return
        scope.launch {
            starting = true
            val url = radio.streamUrl(current.id)
            starting = false
            if (url != null) {
                error = null
                Playback.playRadio(
                    context = context,
                    episodeId = current.id,
                    title = current.title,
                    show = current.show,
                    audioUrl = url,
                    coverUrl = current.artUrl(0),
                    startAtMs = atSec * 1000L,
                )
            } else {
                error = radio.lastError ?: "Couldn't start this episode."
            }
        }
    }

    // Radio behaves like radio: selecting an episode starts it. Re-opening the one already
    // playing keeps its place instead of restarting it.
    var startedOnOpen by remember(episodeId) { mutableStateOf(false) }
    LaunchedEffect(episodeId, record?.id) {
        if (!startedOnOpen && record != null) {
            startedOnOpen = true
            if (!playingThis) start(0)
        }
    }

    if (record == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding) {
            item { PageHead("Radio") }
            item {
                Status(
                    when {
                        loading -> "Loading the episode…"
                        error != null -> error.orEmpty()
                        else -> "That episode is not there anymore."
                    },
                )
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(
                    Modifier.width(320.dp).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp)).background(Colors.bg2),
                ) {
                    AsyncImage(
                        model = record.artUrl(0),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    PageHead(
                        record.title,
                        crumb = record.show,
                        subtitle = listOfNotNull(
                            radioDate(record.date),
                            if (record.tracks.isNotEmpty()) "${record.tracks.size} tracks" else null,
                            record.durationSec.takeIf { it > 0 }?.let { durationText(it) },
                        ).joinToString(" · "),
                    )
                    Row(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Pill(
                            text = if (starting) "Starting…" else "Play",
                            onClick = { start(0) },
                            modifier = Modifier.focusRequester(playFocus),
                            primary = true,
                        )
                    }
                    if (!record.description.isNullOrBlank()) {
                        Text(
                            record.description.orEmpty(),
                            style = Type.sub.copy(fontSize = 13.sp, lineHeight = 19.sp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (activeTrack != null) {
                        Text(
                            buildString {
                                append("You're hearing ")
                                append(activeTrack.title)
                                activeTrack.albumTitle?.takeIf { it.isNotBlank() }?.let {
                                    append(", from ")
                                    append(it)
                                }
                            },
                            style = Type.body.copy(color = Colors.accent),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
        if (error != null) item { Status(error.orEmpty()) }
        if (record.tracks.isEmpty()) item { Status("This episode has no tracklist yet.") }
        itemsIndexed(
            record.tracks,
            key = { _, track -> track.id.ifBlank { "tc-" + track.timecodeSec } },
        ) { index, track ->
            RadioTrackRow(
                track = track,
                playing = index == currentTrackIndex && playingThis,
                onClick = {
                    if (playingThis) Playback.seekToPosition(track.timecodeSec * 1000L)
                    else start(track.timecodeSec)
                },
            )
        }
    }
}

@Composable
private fun RadioTrackRow(track: RadioTrack, playing: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Colors.bg2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Colors.bg3))),
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                durationText(track.timecodeSec).ifBlank { "0:00" },
                style = Type.sub.copy(color = if (playing) Colors.accent else Colors.muted),
                modifier = Modifier.width(64.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = Type.body.copy(color = if (playing) Colors.accent else Colors.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(track.artistName, track.albumTitle).joinToString(" · "),
                    style = Type.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(durationText(track.durationSec), style = Type.sub)
        }
    }
}
