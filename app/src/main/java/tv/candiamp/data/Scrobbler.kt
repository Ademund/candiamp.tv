package tv.candiamp.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.candiamp.playback.Playback
import java.io.File

/**
 * Turns playback into Last.fm scrobbles.
 *
 * It listens to [Playback] for track changes and 500 ms ticks, counts actual listening time (a
 * seek does not count), sends now-playing once per track, and queues a scrobble the moment the
 * Last.fm threshold is met. Queued entries are written to disk before they are sent and flushed
 * in batches of 50.
 */
class Scrobbler(context: Context, private val credentials: LastFmCredentials) : Playback.Listener {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lastfm", Context.MODE_PRIVATE)
    private val queue = ScrobbleQueue(File(app.filesDir, "lastfm/queue.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val flushMutex = Mutex()

    var enabled by mutableStateOf(prefs.getBoolean("scrobbling", true))
        private set
    var pending by mutableStateOf(queue.count())
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var needsReauth by mutableStateOf(false)
        private set

    private var current: Playback.TrackInfo? = null
    private var listenedMs = 0L
    private var scrobbled = false
    private var lastTickAt = 0L
    private var nextAttemptAt = 0L
    private var retryJob: Job? = null

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            retryJob?.cancel()
            retryJob = null
            nextAttemptAt = 0L
            flushSoon()
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    fun start() {
        current = Playback.currentTrack
        lastTickAt = SystemClock.elapsedRealtime()
        flushSoon()
    }

    fun setScrobbling(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("scrobbling", value).apply()
        if (value) {
            lastError = null
            nextAttemptAt = 0L
            retryJob?.cancel()
            retryJob = null
            flushSoon()
        }
    }

    override fun onTrackChanged(track: Playback.TrackInfo?) {
        // A switch can happen before the next tick: decide on the outgoing track first.
        evaluateCurrent()
        current = track
        listenedMs = 0L
        scrobbled = false
        lastTickAt = SystemClock.elapsedRealtime()
        if (track == null || !active()) return
        if (track.title.isBlank() || track.artist.isBlank()) return
        scope.launch { sendNowPlaying(track) }
    }

    override fun onTick(positionMs: Long, durationMs: Long, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val track = current
        if (track == null) {
            lastTickAt = now
            return
        }
        if (playing && active() && !scrobbled) {
            // Seeks move the position, not the wall clock; cap a tick so a frozen app doesn't count.
            listenedMs += (now - lastTickAt).coerceIn(0L, 1_500L)
        }
        lastTickAt = now
        evaluateCurrent()
    }

    private fun active(): Boolean = enabled && credentials.isConfigured && credentials.isLinked

    private fun evaluateCurrent() {
        val track = current ?: return
        if (scrobbled || !active()) return
        if (track.title.isBlank() || track.artist.isBlank()) return
        if (!ScrobbleRules.shouldScrobble(track.durationSec, listenedMs)) return
        scrobbled = true
        val entry = ScrobbleEntry(
            artist = track.artist,
            track = track.title,
            album = track.album?.takeIf { it.isNotBlank() },
            trackNumber = track.trackNumber,
            durationSec = track.durationSec.takeIf { it > 0 },
            timestamp = track.startedAtSec,
            chosenByUser = if (track.radio) false else null,
        )
        android.util.Log.i(
            "candiamp",
            "lastfm queue ${track.artist} - ${track.title} chosenByUser=${entry.chosenByUser}",
        )
        scope.launch {
            val size = withContext(Dispatchers.IO) { queue.add(entry) }
            pending = size
            flush()
        }
    }

    private suspend fun sendNowPlaying(track: Playback.TrackInfo) {
        val client = credentials.client() ?: return
        try {
            withContext(Dispatchers.IO) {
                client.updateNowPlaying(
                    artist = track.artist,
                    track = track.title,
                    album = track.album,
                    trackNumber = track.trackNumber,
                    durationSec = track.durationSec,
                )
            }
            needsReauth = false
            android.util.Log.i("candiamp", "lastfm now playing: ${track.artist} - ${track.title}")
        } catch (e: LastFmException) {
            if (e.needsReauth) {
                needsReauth = true
                lastError = "Last.fm needs you to reconnect."
            }
            // Now-playing failures are explicitly not retried.
            android.util.Log.w("candiamp", "lastfm now-playing failed (${e.code})", e)
        } catch (e: Exception) {
            android.util.Log.w("candiamp", "lastfm now-playing failed", e)
        }
    }

    private fun flushSoon() {
        if (!active()) return
        scope.launch { flush() }
    }

    private suspend fun flush() {
        if (!active()) return
        if (SystemClock.elapsedRealtime() < nextAttemptAt) return
        flushMutex.withLock {
            val client = credentials.client() ?: return
            while (active()) {
                val batch = withContext(Dispatchers.IO) { queue.takeBatch(50) }
                if (batch.isEmpty()) break
                try {
                    val result = withContext(Dispatchers.IO) { client.scrobble(batch) }
                    withContext(Dispatchers.IO) { queue.remove(batch) }
                    pending = queue.count()
                    lastError = null
                    needsReauth = false
                    retryJob?.cancel()
                    retryJob = null
                    android.util.Log.i(
                        "candiamp",
                        "lastfm scrobbled ${result.accepted}, ignored ${result.ignored}",
                    )
                } catch (e: LastFmException) {
                    lastError = e.message ?: "Last.fm rejected the scrobble."
                    if (e.needsReauth) needsReauth = true
                    val wait = if (e.retryable) 60_000L else 5 * 60_000L
                    nextAttemptAt = SystemClock.elapsedRealtime() + wait
                    scheduleRetry(wait)
                    android.util.Log.w("candiamp", "lastfm scrobble failed (${e.code})", e)
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "Couldn't reach Last.fm."
                    nextAttemptAt = SystemClock.elapsedRealtime() + 60_000L
                    scheduleRetry(60_000L)
                    android.util.Log.w("candiamp", "lastfm scrobble failed", e)
                    break
                }
            }
        }
    }

    private fun scheduleRetry(waitMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(waitMs)
            flush()
        }
    }

    companion object {
        @Volatile
        private var instance: Scrobbler? = null

        /** One scrobbler for the process: playback, and therefore scrobbling, outlives the activity. */
        fun get(context: Context, credentials: LastFmCredentials): Scrobbler =
            instance ?: synchronized(this) {
                instance ?: Scrobbler(context.applicationContext, credentials).also { instance = it }
            }
    }
}
