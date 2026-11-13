package tv.candiamp.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.candiamp.playback.Playback

private fun fraction(): Float {
    val duration = Playback.durationMs
    if (duration <= 0) return 0f
    return (Playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
}

/** mm:ss (or h:mm:ss), with 0:00 rather than an empty string. */
private fun clock(ms: Long): String {
    val seconds = (ms / 1000).toInt().coerceAtLeast(0)
    val minutes = seconds / 60
    val rest = seconds % 60
    return "%d:%02d".format(minutes, rest)
}

@Composable
fun RawCover(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(Colors.bg2, RoundedCornerShape(10.dp))) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

/** The bar at the bottom of every screen, and the way into the full player. */
@Composable
fun NowPlayingBar(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val current = Playback.current ?: return
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Colors.bg2, focusedContainerColor = Colors.bg3),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        // The bar is the way into the player, so make it obvious when the remote is on it.
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Colors.accent), shape = RoundedCornerShape(0.dp)),
        ),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RawCover(current.coverUrl, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = Type.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(current.artist.takeIf { it.isNotBlank() }, current.albumName).joinToString(" · "),
                        style = Type.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(if (Playback.playing) "Playing" else "Paused", style = Type.sub)
                if (Playback.durationMs > 0) {
                    Text(
                        "${clock(Playback.positionMs)} / ${clock(Playback.durationMs)}",
                        style = Type.sub,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Colors.bg3)) {
                Box(Modifier.fillMaxWidth(fraction()).fillMaxHeight().background(Colors.accent))
            }
        }
    }
}

/** The full player: big cover, real seek, the next and previous queue entries, and the queue. */
@Composable
fun NowPlayingScreen(onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val current = Playback.current
    val queue = Playback.queue
    val index = Playback.queueIndex
    val previous = if (index > 0) queue.getOrNull(index - 1) else null
    val next = if (index >= 0) queue.getOrNull(index + 1) else null
    val upcoming = if (index >= 0) queue.size - index - 1 else queue.size
    val focus = remember { FocusRequester() }
    BackHandler { onClose() }

    if (current == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier
                    .padding(horizontal = 72.dp)
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { Playback.seekBy(-10_000); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { Playback.seekBy(10_000); true }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { Playback.toggle(); true }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> { Playback.next(); true }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { Playback.previous(); true }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RawCover(current.coverUrl, Modifier.size(420.dp))
                Column(Modifier.width(560.dp)) {
                    Text(
                        current.title,
                        style = Type.hero.copy(fontSize = 40.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(current.artist, style = Type.section.copy(color = Colors.muted), maxLines = 1)
                    if (!current.albumName.isNullOrBlank()) {
                        Text(current.albumName, style = Type.sub.copy(fontSize = 14.sp), maxLines = 1)
                    }

                    Spacer(Modifier.height(34.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Colors.bg3, RoundedCornerShape(3.dp))) {
                        Box(
                            Modifier.fillMaxWidth(fraction()).fillMaxHeight()
                                .background(Colors.accent, RoundedCornerShape(3.dp)),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(Playback.positionMs), style = Type.sub)
                        Text(clock(Playback.durationMs), style = Type.sub)
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        if (Playback.playing) "Playing" else "Paused",
                        style = Type.section.copy(color = Colors.accent),
                    )

                    if (previous != null) {
                        Spacer(Modifier.height(18.dp))
                        QueuePeek("Previous", previous)
                    }
                    if (next != null) {
                        Spacer(Modifier.height(10.dp))
                        QueuePeek(if (upcoming > 1) "Up next · $upcoming in the queue" else "Up next", next)
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        if (current.albumId?.startsWith(Playback.RADIO_PREFIX) == true) {
                            "Left / Right seek 10 seconds · OK play or pause · Back to browse"
                        } else {
                            "Left / Right seek 10 seconds · OK play or pause · Next / Previous below · Back to browse"
                        },
                        style = Type.sub,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (index > 0) {
                    Pill(text = "Previous", onClick = { Playback.previous() })
                }
                Pill(
                    text = if (Playback.playing) "Pause" else "Play",
                    onClick = { Playback.toggle() },
                    primary = true,
                )
                if (index in 0 until queue.lastIndex) {
                    Pill(text = "Next", onClick = { Playback.next() })
                }
                Pill(
                    text = if (queue.isEmpty()) "Queue" else "Queue · ${queue.size}",
                    onClick = onOpenQueue,
                )
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The queue entry before or after the current track, in the full player. */
@Composable
private fun QueuePeek(label: String, entry: Playback.QueueEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RawCover(entry.artworkUrl, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.sub.copy(fontSize = 11.sp))
            Text(entry.title, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(entry.artist.takeIf { it.isNotBlank() }, entry.album).joinToString(" · "),
                style = Type.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
