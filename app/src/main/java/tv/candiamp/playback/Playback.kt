package tv.candiamp.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import tv.candiamp.data.RadioTrack
import tv.candiamp.data.Song
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * The track the scrobbler cares about. For a record or queue this is the media item; for radio
     * it is the song inside the episode, derived from the timestamped tracklist.
     */
    data class TrackInfo(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val trackNumber: Int?,
        val durationSec: Int,
        val chosenByUser: Boolean,
        val radio: Boolean,
        /** Wall-clock UNIX seconds when this track started, used as the scrobble timestamp. */
        val startedAtSec: Long,
    )

    interface Listener {
        fun onTrackChanged(track: TrackInfo?)
        fun onTick(positionMs: Long, durationMs: Long, playing: Boolean)
    }

    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var radioTracks: List<RadioTrack>? = null
    private var radioEpisodeId: String? = null
    private var radioShow: String? = null
    private var radioIndex = -1

    var current by mutableStateOf<NowPlaying?>(null)
        private set
    var track by mutableStateOf<TrackInfo?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set

    val hasNext: Boolean get() = exo?.hasNextMediaItem() == true
    val hasPrevious: Boolean get() = exo?.hasPreviousMediaItem() == true

    val currentTrack: TrackInfo? get() = track

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyTrack() {
        val value = track
        listeners.toList().forEach { it.onTrackChanged(value) }
    }

    private fun notifyTick() {
        val position = positionMs
        val duration = durationMs
        val isPlaying = playing
        listeners.toList().forEach { it.onTick(position, duration, isPlaying) }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private fun updateTrack(value: TrackInfo?) {
        if (track?.id == value?.id) return
        track = value
        notifyTrack()
    }

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
                if (state == Player.STATE_ENDED) {
                    // Give the scrobbler a final tick so the last track can be evaluated.
                    syncPosition()
                }
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
            if (radioTracks != null) updateRadioTrack(positionMs)
            notifyTick()
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
        if (item.mediaId.startsWith(RADIO_PREFIX)) {
            // An episode is one media item; the scrobbler track comes from its tracklist.
            return
        }
        updateTrack(
            TrackInfo(
                id = item.mediaId,
                title = meta.title?.toString().orEmpty(),
                artist = meta.artist?.toString().orEmpty(),
                album = meta.albumTitle?.toString(),
                trackNumber = meta.trackNumber,
                durationSec = ((meta.durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(0),
                chosenByUser = true,
                radio = false,
                startedAtSec = nowSec(),
            ),
        )
    }

    private fun updateRadioTrack(positionMs: Long) {
        val list = radioTracks ?: return
        val index = radioTrackIndex(list, positionMs)
        if (index < 0 || (index == radioIndex && track != null)) return
        radioIndex = index
        val item = list[index]
        val intoTrackMs = (positionMs - item.timecodeSec * 1000L).coerceAtLeast(0L)
        val now = nowSec()
        updateTrack(
            TrackInfo(
                id = "$RADIO_PREFIX${radioEpisodeId.orEmpty()}:${item.id.ifBlank { item.timecodeSec }}",
                title = item.title,
                artist = item.artistName?.takeIf { it.isNotBlank() } ?: radioShow ?: "Bandcamp Radio",
                album = item.albumTitle,
                trackNumber = null,
                durationSec = item.durationSec,
                chosenByUser = false,
                radio = true,
                startedAtSec = (now - intoTrackMs / 1000L).coerceAtMost(now),
            ),
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

    /**
     * A Bandcamp Radio episode: one long compiled stream rather than a queue. The URL was
     * resolved by [tv.candiamp.data.RadioLibrary] at play time because it expires.
     */
    fun playRadio(
        context: Context,
        episodeId: String,
        title: String,
        show: String?,
        audioUrl: String,
        coverUrl: String?,
        startAtMs: Long = 0L,
        tracks: List<RadioTrack> = emptyList(),
    ) {
        val player = player(context)
        radioTracks = tracks
        radioEpisodeId = episodeId
        radioShow = show
        radioIndex = -1
        updateTrack(null)
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(show ?: "Bandcamp Radio")
            .setExtras(android.os.Bundle().apply { putString(KEY_ALBUM_ID, RADIO_PREFIX + episodeId) })
        coverUrl?.let { metadata.setArtworkUri(Uri.parse(it)) }
        val item = MediaItem.Builder()
            .setUri(audioUrl)
            .setMediaId(RADIO_PREFIX + episodeId)
            .setMediaMetadata(metadata.build())
            .build()
        player.setMediaItems(listOf(item), 0, startAtMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        startTicker()
        current = NowPlaying(
            title = title,
            artist = show.orEmpty(),
            albumName = null,
            albumId = RADIO_PREFIX + episodeId,
            coverUrl = coverUrl,
            position = 0,
            total = 1,
        )
        updateRadioTrack(startAtMs.coerceAtLeast(0L))
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
        radioTracks = null
        radioEpisodeId = null
        radioShow = null
        radioIndex = -1
        val playable = songs.mapNotNull { song ->
            streamUrl(song)?.let { url -> song to url }
        }
        if (playable.isEmpty()) return
        val items = playable.map { (song, url) ->
            val meta = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistName ?: album.artist)
                .setAlbumTitle(song.albumName ?: album.name)
                .setTrackNumber(song.track)
                .setDurationMs(song.durationSec * 1000L)
                .setExtras(android.os.Bundle().apply { putString(KEY_ALBUM_ID, album.id) })
            artwork(song)?.let { meta.setArtworkUri(Uri.parse(it)) }
            MediaItem.Builder()
                .setUri(url)
                .setMediaId(song.id)
                .setMediaMetadata(meta.build())
                .build()
        }
        val index = startIndex.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.playWhenReady = true
        startTicker()
        val first = playable[index].first
        current = NowPlaying(
            title = first.title,
            artist = first.artistName ?: album.artist.orEmpty(),
            albumName = first.albumName ?: album.name,
            albumId = album.id,
            coverUrl = artwork(first),
            position = index,
            total = playable.size,
        )
        updateTrack(
            TrackInfo(
                id = first.id,
                title = first.title,
                artist = first.artistName ?: album.artist.orEmpty(),
                album = first.albumName ?: album.name,
                trackNumber = first.track,
                durationSec = first.durationSec,
                chosenByUser = true,
                radio = false,
                startedAtSec = nowSec(),
            ),
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

    fun seekToPosition(ms: Long) {
        exo?.let {
            val duration = it.duration
            val upper = if (duration > 0) duration else Long.MAX_VALUE
            it.seekTo(ms.coerceIn(0L, upper))
        }
        syncPosition()
    }

    const val KEY_ALBUM_ID = "albumId"

    /** Now-playing ids starting with this prefix are radio episodes, not records. */
    const val RADIO_PREFIX = "radio:"
}

/** The episode track whose timecode is at or before [positionMs]; the first track before its start. */
internal fun radioTrackIndex(tracks: List<RadioTrack>, positionMs: Long): Int {
    if (tracks.isEmpty()) return -1
    val seconds = (positionMs / 1000L).toInt()
    return tracks.indexOfLast { it.timecodeSec <= seconds }.coerceAtLeast(0)
}
