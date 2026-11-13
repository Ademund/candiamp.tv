package tv.candiamp.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import tv.candiamp.data.Song

/** One button in an [ActionSheet]. */
internal data class SheetAction(val label: String, val onClick: () -> Unit)

/**
 * A modal list of actions for one row. The first action is primary and takes focus, so a long-press
 * is one DPAD press away from doing something. Back closes the sheet.
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
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
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
