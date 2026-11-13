package tv.candiamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import tv.candiamp.data.Song

/** One button in an [ActionSheet]. */
internal data class SheetAction(val label: String, val onClick: () -> Unit)

private val DIRECTIONS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * A modal list of actions for one row. Back closes the sheet.
 *
 * The sheet is opened by a long-press, which means the remote's OK is still down when it appears.
 * Focus therefore starts on the sheet itself and every key is swallowed until that OK comes up;
 * only then does the first action take focus. Without this, the repeat key-downs that follow a
 * long-press land on "Play now" and fire it immediately.
 */
@Composable
internal fun ActionSheet(
    heading: String,
    title: String,
    subtitle: String?,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val holder = remember { FocusRequester() }
    // A long-press opens this sheet while OK is still held. Give focus to the sheet itself until
    // the key comes up, otherwise the repeats (or the release) press whatever button just took
    // focus — which is always "Play now".
    var awaitRelease by remember { mutableStateOf(true) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .width(420.dp)
                .background(Colors.bg2, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .focusRequester(holder)
                .focusable()
                .onKeyEvent { event ->
                    if (!awaitRelease) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        awaitRelease = false
                        runCatching { first.requestFocus() }
                        return@onKeyEvent true
                    }
                    // If the release never arrives (focus changed windows), the first D-pad press
                    // means the viewer is choosing: arm the sheet and put focus on Play now.
                    if (event.key in DIRECTIONS) {
                        awaitRelease = false
                        runCatching { first.requestFocus() }
                        return@onKeyEvent true
                    }
                    true
                },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(heading, style = Type.sub)
                Text(title, style = Type.section, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = Type.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                actions.forEachIndexed { index, action ->
                    Pill(
                        text = action.label,
                        onClick = action.onClick,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        primary = index == 0,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { holder.requestFocus() } }
}

/** What a long-press on a track offers. Queueing a track does not need its record to be loaded. */
@Composable
internal fun TrackActions(
    song: Song,
    albumTitle: String?,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheet(
        heading = "Track",
        title = song.title,
        subtitle = listOfNotNull(
            song.artistName?.takeIf { it.isNotBlank() },
            (albumTitle ?: song.albumName)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { null },
        actions = listOf(
            SheetAction("Play now", onPlay),
            SheetAction("Play next", onPlayNext),
            SheetAction("Add to queue", onAddToQueue),
        ),
        onDismiss = onDismiss,
    )
}
