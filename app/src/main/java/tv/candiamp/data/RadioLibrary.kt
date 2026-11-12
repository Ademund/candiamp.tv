package tv.candiamp.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bandcamp Radio, kept apart from [MusicLibrary] on purpose: it is a scraper, not an API, and a
 * change to Bandcamp's HTML must never be able to affect collection playback.
 *
 * The index and each parsed episode are cached on disk. Signed stream URLs are never written to
 * the cache — they expire — so [streamUrl] re-reads the episode page at play time and keeps the
 * result in memory for a little while.
 */
class RadioLibrary(private val context: Context) {

    var shows by mutableStateOf<List<RadioEpisode>>(emptyList())
        private set
    var syncing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private val source = BandcampRadioSource()
    private val dir = File(context.filesDir, "radio")
    private val indexFile = File(dir, "index.json")

    private var syncedAt = 0L
    private var cacheVersion = 0
    private val episodes = HashMap<String, RadioEpisode>()
    /** Episode id → (signed URL, resolved at). Memory only, by design. */
    private val streams = HashMap<String, Pair<String, Long>>()

    init {
        readIndex()
        readEpisodes()
        applyCachedDurations()
    }

    fun isStale(): Boolean =
        cacheVersion != VERSION || syncedAt == 0L || System.currentTimeMillis() - syncedAt > 3 * 60 * 60 * 1000

    fun cached(id: String): RadioEpisode? = episodes[id] ?: readEpisode(id)

    /** Reads the episode index. Failures keep whatever the cache had. */
    suspend fun sync() {
        if (syncing) return
        syncing = true
        lastError = null
        try {
            status = "Reading Bandcamp Radio…"
            val list = source.shows()
            android.util.Log.i("candiamp", "radio index: ${list.size} episodes")
            shows = list.map { summary ->
                episodes[summary.id]?.let { detail ->
                    summary.copy(durationSec = detail.durationSec, tracks = emptyList())
                } ?: summary
            }
            syncedAt = System.currentTimeMillis()
            cacheVersion = VERSION
            writeIndex()
            status = ""
        } catch (e: Exception) {
            lastError = e.message ?: "Couldn't read Bandcamp Radio."
            android.util.Log.w("candiamp", "radio index failed", e)
        } finally {
            syncing = false
        }
    }

    /**
     * One episode with its tracklist, from cache when it is still fresh. A failed refresh returns
     * the stale copy rather than an error, which is the whole point of caching it.
     */
    suspend fun episode(id: String, force: Boolean = false): RadioEpisode? {
        val cached = cached(id)
        if (!force && cached != null && !isEpisodeStale(cached)) return cached
        return try {
            val page = withContext(Dispatchers.IO) { source.episode(id) }
            page.streamUrl?.let { streams[id] = it to System.currentTimeMillis() }
            saveEpisode(page.episode)
            page.episode
        } catch (e: Exception) {
            lastError = e.message ?: "Couldn't read that episode."
            android.util.Log.w("candiamp", "radio episode $id failed", e)
            cached
        }
    }

    /** A fresh signed URL for the compiled episode stream, or null with [lastError] set. */
    suspend fun streamUrl(id: String): String? {
        streams[id]?.let { (url, resolvedAt) ->
            if (System.currentTimeMillis() - resolvedAt < 10 * 60 * 1000) return url
        }
        val fresh = episode(id, force = true)?.let { streams[id]?.first }
        if (fresh == null) lastError = "Bandcamp didn't hand out a stream for this episode."
        return fresh
    }

    fun clear() {
        episodes.clear()
        streams.clear()
        shows = emptyList()
        syncedAt = 0L
        dir.deleteRecursively()
    }

    private fun isEpisodeStale(episode: RadioEpisode): Boolean =
        episode.fetchedAt <= 0L || System.currentTimeMillis() - episode.fetchedAt > 7 * 24 * 60 * 60 * 1000

    private fun fileFor(id: String): File =
        File(dir, "ep-" + id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private fun readEpisode(id: String): RadioEpisode? = runCatching {
        val file = fileFor(id)
        if (!file.exists()) return null
        json.decodeFromString(RadioEpisode.serializer(), file.readText()).also { episodes[id] = it }
    }.getOrNull()

    private fun saveEpisode(episode: RadioEpisode) {
        episodes[episode.id] = episode
        shows = shows.map { show ->
            if (show.id == episode.id) show.copy(durationSec = episode.durationSec) else show
        }
        runCatching {
            dir.mkdirs()
            fileFor(episode.id).writeText(json.encodeToString(RadioEpisode.serializer(), episode))
        }
    }

    private fun readIndex() {
        if (!indexFile.exists()) return
        runCatching {
            val cache = json.decodeFromString(RadioCache.serializer(), indexFile.readText())
            syncedAt = cache.syncedAt
            cacheVersion = cache.version
            shows = cache.shows
        }
    }

    private fun writeIndex() {
        runCatching {
            dir.mkdirs()
            indexFile.writeText(json.encodeToString(RadioCache.serializer(), RadioCache(shows, syncedAt, VERSION)))
        }
    }

    /** Cached episode files carry durations the index does not. */
    private fun readEpisodes() {
        runCatching {
            dir.listFiles().orEmpty()
                .filter { it.name.startsWith("ep-") && it.name.endsWith(".json") }
                .forEach { file ->
                    runCatching {
                        val episode = json.decodeFromString(RadioEpisode.serializer(), file.readText())
                        episodes[episode.id] = episode
                    }
                }
        }
    }

    private fun applyCachedDurations() {
        shows = shows.map { show ->
            episodes[show.id]?.let { show.copy(durationSec = it.durationSec) } ?: show
        }
    }

    companion object {
        const val VERSION = 1
    }
}
