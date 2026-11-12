package tv.candiamp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The periods Last.fm exposes for the top lists. */
enum class LastFmPeriod(val api: String, val label: String) {
    WEEK("7day", "7 days"),
    MONTH("1month", "1 month"),
    QUARTER("3month", "3 months"),
    YEAR("12month", "12 months"),
    ALL("overall", "All time"),
}

data class LastFmArtist(
    val name: String,
    val playcount: Int,
    val imageUrl: String?,
    val url: String?,
)

data class LastFmAlbum(
    val name: String,
    val artist: String,
    val playcount: Int,
    val imageUrl: String?,
    val url: String?,
)

data class LastFmTrack(
    val name: String,
    val artist: String,
    val playcount: Int,
    val durationSec: Int?,
    val imageUrl: String?,
    val url: String?,
)

data class LastFmRecentTrack(
    val name: String,
    val artist: String,
    val album: String?,
    val imageUrl: String?,
    val url: String?,
    val playedAt: Long?,
    val nowPlaying: Boolean,
)

data class LastFmPage(
    val profile: LastFmUser,
    val artists: List<LastFmArtist>,
    val albums: List<LastFmAlbum>,
    val tracks: List<LastFmTrack>,
    val recent: List<LastFmRecentTrack>,
)

/**
 * Last.fm stats, fetched on demand and kept in memory for ten minutes. The profile and recent
 * list are shared across periods; the three top lists are cached per period.
 */
class LastFmStats(private val credentials: LastFmCredentials) {

    private val cache = HashMap<String, Pair<Long, Any>>()
    private val mutex = Mutex()

    suspend fun load(period: LastFmPeriod, force: Boolean = false): LastFmPage = mutex.withLock {
        withContext(Dispatchers.IO) {
            val client = credentials.client() ?: throw LastFmException("Last.fm is not connected.")
            val user = credentials.username ?: throw LastFmException("Last.fm is not connected.")
            val profile = section("profile", force) { client.userInfo() }
            val artists = section("artists:${period.api}", force) {
                client.topArtists(user, period.api)
            }
            val albums = section("albums:${period.api}", force) {
                client.topAlbums(user, period.api)
            }
            val tracks = section("tracks:${period.api}", force) {
                client.topTracks(user, period.api)
            }
            val recent = section("recent", force) { client.recentTracks(user) }
            LastFmPage(profile, artists, albums, tracks, recent)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> section(key: String, force: Boolean, fetch: () -> T): T {
        val now = System.currentTimeMillis()
        val hit = cache[key]
        if (!force && hit != null && now - hit.first < TTL_MS) return hit.second as T
        val value = fetch()
        cache[key] = now to (value as Any)
        return value
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}

internal fun parseTopArtists(root: JsonObject): List<LastFmArtist> =
    root.obj("topartists")?.objects("artist").orEmpty().mapNotNull { item ->
        val name = item.text("name") ?: return@mapNotNull null
        LastFmArtist(
            name = name,
            playcount = item.int("playcount") ?: 0,
            imageUrl = item.lastFmImage(),
            url = item.text("url"),
        )
    }

internal fun parseTopAlbums(root: JsonObject): List<LastFmAlbum> =
    root.obj("topalbums")?.objects("album").orEmpty().mapNotNull { item ->
        val name = item.text("name") ?: return@mapNotNull null
        LastFmAlbum(
            name = name,
            artist = item.obj("artist")?.text("name") ?: item.text("artist") ?: "",
            playcount = item.int("playcount") ?: 0,
            imageUrl = item.lastFmImage(),
            url = item.text("url"),
        )
    }

internal fun parseTopTracks(root: JsonObject): List<LastFmTrack> =
    root.obj("toptracks")?.objects("track").orEmpty().mapNotNull { item ->
        val name = item.text("name") ?: return@mapNotNull null
        LastFmTrack(
            name = name,
            artist = item.obj("artist")?.text("name") ?: item.text("artist") ?: "",
            playcount = item.int("playcount") ?: 0,
            durationSec = item.int("duration"),
            imageUrl = item.lastFmImage(),
            url = item.text("url"),
        )
    }

internal fun parseRecentTracks(root: JsonObject): List<LastFmRecentTrack> =
    root.obj("recenttracks")?.objects("track").orEmpty().mapNotNull { item ->
        val name = item.text("name") ?: return@mapNotNull null
        LastFmRecentTrack(
            name = name,
            artist = item.obj("artist")?.text("#text") ?: item.text("artist") ?: "",
            album = item.obj("album")?.text("#text") ?: item.text("album"),
            imageUrl = item.lastFmImage(),
            url = item.text("url"),
            playedAt = item.obj("date")?.long("uts"),
            nowPlaying = item.obj("@attr")?.text("nowplaying") == "true" ||
                item.text("nowplaying") == "true",
        )
    }

/**
 * Last.fm sizes an image into `small`…`mega`; a couple of hashes are the "no image" stars.
 */
internal fun JsonObject.lastFmImage(): String? {
    val images = objects("image")
    val preferred = images.firstOrNull { it.text("size") == "mega" }
        ?: images.firstOrNull { it.text("size") == "extralarge" }
        ?: images.firstOrNull { it.text("size") == "large" }
        ?: images.lastOrNull()
    val url = preferred?.text("#text") ?: return null
    if (url.isBlank()) return null
    if (url.contains(PLACEHOLDER_STAR) || url.contains(PLACEHOLDER_ALBUM)) return null
    return url
}

private const val PLACEHOLDER_STAR = "2a96cbd8b46e442fc41c2b86b821562f"
private const val PLACEHOLDER_ALBUM = "c6f59c1e5e7240a4c0d427abd71f3dbb"
