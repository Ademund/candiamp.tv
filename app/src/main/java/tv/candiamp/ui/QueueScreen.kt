package tv.candiamp.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.candiamp.data.durationText
import tv.candiamp.playback.Playback

/**
 * The player's timeline, played tracks included. OK plays a row; long-press opens move and remove
 * so the remote never needs anything but the D-pad.
 */
@Composable
fun QueueScreen() {
    val queue = Playback.queue
    val index = Playback.queueIndex
    var actionsFor by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (queue.isNotEmpty()) runCatching { first.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pagePadding,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                PageHead(
                    "Queue",
                    subtitle = when {
                        queue.isEmpty() -> "Nothing queued"
                        else -> "${queue.size} tracks · OK plays · long-press to move or remove"
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            if (queue.isEmpty()) {
                item { Status("Play a record, or add tracks to the queue, and they will show up here.") }
            }
            itemsIndexed(queue) { position, entry ->
                QueueRow(
                    entry = entry,
                    number = position + 1,
                    current = position == index,
                    played = position < index,
                    focusRequester = if (position == 0) first else null,
                    onClick = { Playback.jumpTo(position) },
                    onLongClick = { actionsFor = position },
                )
            }
        }

        val target = actionsFor
        if (target != null && target in queue.indices) {
            QueueActions(
                entry = queue[target],
                canMoveUp = target > 0,
                canMoveDown = target < queue.lastIndex,
                onPlay = { actionsFor = null; Playback.jumpTo(target) },
                onMoveUp = { actionsFor = null; Playback.moveQueueItem(target, target - 1) },
                onMoveDown = { actionsFor = null; Playback.moveQueueItem(target, target + 1) },
                onRemove = { actionsFor = null; Playback.removeQueueItem(target) },
                onDismiss = { actionsFor = null },
            )
        }
    }
}

@Composable
private fun QueueRow(
    entry: Playback.QueueEntry,
    number: Int,
    current: Boolean,
    played: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Colors.bg2 else Color.Transparent,
            focusedContainerColor = Colors.bg3,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Colors.accent), shape = RoundedCornerShape(8.dp)),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (current) "▶" else "$number",
                style = Type.sub.copy(
                    fontSize = if (current) 13.sp else 12.sp,
                    color = if (current) Colors.accent else Colors.muted,
                ),
                modifier = Modifier.width(28.dp),
            )
            RawCover(entry.artworkUrl, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = Type.body.copy(color = if (current) Colors.accent else if (played) Colors.muted else Colors.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        entry.artist.takeIf { it.isNotBlank() },
                        entry.album?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = Type.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current) Text("Playing", style = Type.sub.copy(color = Colors.accent))
            Text(durationText(entry.durationSec), style = Type.sub)
        }
    }
}

/** The long-press menu for one queue row. */
@Composable
private fun QueueActions(
    entry: Playback.QueueEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .width(420.dp)
                .background(Colors.bg2, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Queue", style = Type.sub)
                Text(entry.title, style = Type.section, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        entry.artist.takeIf { it.isNotBlank() },
                        entry.album?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = Type.sub,
                )
                Spacer(Modifier.height(6.dp))
                Pill(
                    text = "Play now",
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(first),
                    primary = true,
                )
                if (canMoveUp) Pill("Move up", onClick = onMoveUp)
                if (canMoveDown) Pill("Move down", onClick = onMoveDown)
                Pill("Remove from queue", onClick = onRemove)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
