package tv.candiamp.playback

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.candiamp.data.Album
import tv.candiamp.data.Song

/**
 * One player for the whole app, over the process lifetime, so a record keeps playing while the
 * viewer browses. Bandcamp signs its stream URLs, so a URL is resolved at the moment it is played.
 */
object Playback {

    data class NowPlaying(
        val title: String,
        val artist: String,
        val albumName: String?,
        val albumId: String?,
        val coverUrl: String?,
        val position: Int,
        val total: Int,
    )

    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var current by mutableStateOf<NowPlaying?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set

    val hasNext: Boolean get() = exo?.hasNextMediaItem() == true
    val hasPrevious: Boolean get() = exo?.hasPreviousMediaItem() == true

    fun player(context: Context): ExoPlayer {
        exo?.let { return it }
        val player = ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) startTicker() else stopTicker()
            }

            override fun onPlaybackStateChanged(state: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
                if (player.isPlaying) startTicker()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { read(it, player) }
            }
        })
        session = MediaSession.Builder(context.applicationContext, player).build()
        exo = player
        return player
    }

    private fun syncPosition() {
        exo?.let {
            positionMs = it.currentPosition.coerceAtLeast(0L)
            durationMs = it.duration.coerceAtLeast(0L)
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                syncPosition()
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        syncPosition()
    }

    private fun read(item: MediaItem, player: ExoPlayer) {
        val meta = item.mediaMetadata
        current = NowPlaying(
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            albumName = meta.albumTitle?.toString(),
            albumId = meta.extras?.getString(KEY_ALBUM_ID),
            coverUrl = meta.artworkUri?.toString(),
            position = player.currentMediaItemIndex,
            total = player.mediaItemCount,
        )
    }

    fun playRecord(
        context: Context,
        album: Album,
        songs: List<Song>,
        startIndex: Int,
        coverUrl: String?,
        streamUrl: (Song) -> String?,
    ) = start(context, album, songs, startIndex, { coverUrl }, streamUrl)

    /** A queue that is not a record — search results, for instance. */
    fun playSongs(
        context: Context,
        songs: List<Song>,
        startIndex: Int,
        streamUrl: (Song) -> String?,
        artwork: (Song) -> String?,
    ) {
        if (songs.isEmpty()) return
        val first = songs[startIndex.coerceIn(0, songs.lastIndex)]
        val queue = Album(
            id = "queue:${first.albumId.orEmpty()}",
            name = first.albumName ?: "Search results",
            artist = first.artistName,
            coverArt = first.coverArt,
        )
        start(context, queue, songs, startIndex, artwork, streamUrl)
    }

    private fun start(
        context: Context,
        album: Album,
        songs: List<Song>,
        startIndex: Int,
        artwork: (Song) -> String?,
        streamUrl: (Song) -> String?,
    ) {
        if (songs.isEmpty()) return
        val player = player(context)
        val items = songs.mapNotNull { song ->
            val url = streamUrl(song) ?: return@mapNotNull null
            val meta = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistName ?: album.artist)
                .setAlbumTitle(song.albumName ?: album.name)
                .setTrackNumber(song.track)
                .setExtras(android.os.Bundle().apply { putString(KEY_ALBUM_ID, album.id) })
            artwork(song)?.let { meta.setArtworkUri(Uri.parse(it)) }
            MediaItem.Builder()
                .setUri(url)
                .setMediaId(song.id)
                .setMediaMetadata(meta.build())
                .build()
        }
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.playWhenReady = true
        startTicker()
        val first = songs[startIndex.coerceIn(0, songs.lastIndex)]
        current = NowPlaying(
            title = first.title,
            artist = first.artistName ?: album.artist.orEmpty(),
            albumName = first.albumName ?: album.name,
            albumId = album.id,
            coverUrl = artwork(first),
            position = index,
            total = songs.size,
        )
    }

    fun toggle() {
        exo?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() {
        exo?.seekToNextMediaItem()
        syncPosition()
    }

    fun previous() {
        exo?.seekToPreviousMediaItem()
        syncPosition()
    }

    fun seekBy(deltaMs: Long) {
        exo?.let { it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, it.duration.coerceAtLeast(0L))) }
        syncPosition()
    }

    fun seekTo(fraction: Float) {
        exo?.let {
            val duration = it.duration
            if (duration > 0) it.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        }
        syncPosition()
    }

    const val KEY_ALBUM_ID = "albumId"
}
