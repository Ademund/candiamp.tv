package tv.candiamp.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The collection, cached on disk. Sync reads artists and album cards only; a record's track list
 * is fetched the first time it is opened, and kept afterwards.
 */
class MusicLibrary(private val context: Context, private val credentials: Credentials) {

    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    var syncing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private val cacheFile = File(context.filesDir, "library.json")
    private val details = HashMap<String, Album>()
    private val dates = HashMap<String, Long>()

    init {
        readCache()
    }

    fun client(): SubsonicClient? = credentials.client()

    fun coverUrl(coverArt: String?): String? = runCatching { client()?.coverUrl(coverArt) }.getOrNull()

    fun streamUrl(song: Song): String? = runCatching { client()?.streamUrl(song.id) }.getOrNull()

    val albums: List<Album> get() = snapshot?.albums.orEmpty()
    val artists: List<Artist> get() = snapshot?.artists.orEmpty()

    /** Records with audio to stream; the "no audio" ones stay on the records and artist pages only. */
    private val playableAlbums: List<Album> get() = albums.filterNot { it.unavailable }

    fun album(id: String?): Album? = id?.let { wanted -> details[wanted] ?: albums.firstOrNull { it.id == wanted } }
    fun artist(id: String?): Artist? = id?.let { wanted -> artists.firstOrNull { it.id == wanted } }

    // Both feed the home page, which only offers something you can play.
    fun newest(count: Int): List<Album> = playableAlbums.sortedByDescending { created(it) }.take(count)

    fun shuffle(count: Int): List<Album> = playableAlbums.shuffled().take(count)

    fun byArtist(artistId: String): List<Album> = albums.filter { it.artistId == artistId }

    /** True when the cache is missing, built by an older version, or simply old. */
    fun isStale(): Boolean {
        val current = snapshot ?: return true
        if (current.version != VERSION) return true
        return System.currentTimeMillis() - current.syncedAt > 6 * 60 * 60 * 1000
    }

    private fun created(album: Album): Long = dates.getOrPut(album.id) {
        runCatching { DATE.parse(album.created.orEmpty())?.time ?: 0L }.getOrDefault(0L)
    }

    /**
     * Reads the collection. Two sources, deliberately:
     *  - `getArtists`/`getArtist` say which artist page lists which album (a label page lists its
     *    releases), and only the index has trustworthy names — a label's own `artist.name` is
     *    whatever the first album's artist is called;
     *  - `getAlbumList2` has the album cards with the right display artist, cover and date.
     */
    suspend fun sync() {
        val client = client()
        if (client == null) {
            android.util.Log.w("candiamp", "sync skipped: no client (credentials incomplete)")
            return
        }
        if (syncing) return
        syncing = true
        lastError = null
        android.util.Log.i("candiamp", "sync start against ${client.base} as ${credentials.user}")
        try {
            status = "Reading your collection…"
            val index = withContext(Dispatchers.IO) { client.artists() }
            status = "Reading records…"
            val cards = withContext(Dispatchers.IO) { client.albumList("alphabeticalByName", 500) }
            if (cards.size >= 500) android.util.Log.w("candiamp", "album list hit the 500 cap; paging needed")

            val byId = LinkedHashMap<String, Album>()
            for (card in cards) byId[card.id] = card

            // How many artists in the index claim each `ci:` picture. Bandcamp repeats one image
            // across a label and the artists on it, so a count above one means it is not the
            // artist's own.
            val imageOwners = index
                .mapNotNull { it.coverArt?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()

            val artists = ArrayList<Artist>(index.size)
            for ((position, artist) in index.withIndex()) {
                status = "Reading artists… ${position + 1} of ${index.size}"
                val detail = withContext(Dispatchers.IO) { runCatching { client.artist(artist.id) }.getOrNull() }
                val albums = LinkedHashMap<String, Album>()
                for (album in detail?.albums.orEmpty()) {
                    albums[album.id] = byId[album.id] ?: album.also { byId[it.id] = it }
                }
                val albumList = albums.values.toList()
                // A label page lists records by several different artists under one name.
                val credited = albumList.mapNotNull { it.artist }.distinct()
                val otherArtists = credited.count { !it.equals(artist.name, ignoreCase = true) }
                artists += Artist(
                    id = artist.id,
                    name = artist.name,
                    coverArt = chooseArtistCover(
                        ownImage = artist.coverArt,
                        imageOwners = imageOwners,
                        creditedAlbum = albumList.firstOrNull { it.artist == artist.name }?.coverArt,
                        firstAlbum = albumList.firstOrNull()?.coverArt,
                    ),
                    albums = albumList,
                    isLabel = otherArtists >= 2,
                )
            }

            val merged = byId.values.sortedBy { it.name.lowercase() }
            // Two kinds of entry have nothing to play: vinyl-only records (no tracks at all) and
            // single tracks collected on their own, which Bandcamp lists as a one-track album but
            // cannot open — getAlbum answers `error 70 "not found"`.
            status = "Checking playable records…"
            val checked = LinkedHashMap<String, Album>(merged.size)
            for (album in merged) {
                checked[album.id] = when {
                    album.songCount == 0 -> album.copy(unavailable = true)
                    album.songCount == 1 -> {
                        val full = withContext(Dispatchers.IO) { runCatching { client.album(album.id) }.getOrNull() }
                        if (full == null) {
                            album.copy(unavailable = true)
                        } else {
                            details[album.id] = full
                            album
                        }
                    }
                    else -> album
                }
            }
            val records = checked.values.toList()
            val withPlayable = artists.map { artist ->
                artist.copy(albums = artist.albums.map { album -> checked[album.id] ?: album })
            }
            snapshot = Snapshot(withPlayable, records, System.currentTimeMillis(), VERSION)
            writeCache()
            status = ""
            android.util.Log.i(
                "candiamp",
                "sync done: ${records.size} records, ${artists.size} artists, ${records.count { it.unavailable }} unplayable",
            )
        } catch (e: SubsonicException) {
            lastError = e.message
            android.util.Log.e("candiamp", "sync failed (${e.kind})", e)
        } catch (e: Exception) {
            lastError = e.message ?: "Sync failed."
            android.util.Log.e("candiamp", "sync crashed", e)
        } finally {
            syncing = false
        }
    }

    /** The track list of a record, fetched once. */
    suspend fun albumWithSongs(album: Album): Album {
        details[album.id]?.let { if (it.songs.isNotEmpty()) return it }
        if (album.songs.isNotEmpty()) return album
        val client = client() ?: return album
        val full = withContext(Dispatchers.IO) { client.album(album.id) }
        details[album.id] = full
        return full
    }

    fun clear() {
        details.clear()
        dates.clear()
        snapshot = null
        cacheFile.delete()
    }

    /** Search over the collection, off the main thread. */
    suspend fun search(query: String): SearchHit {
        val client = client() ?: return SearchHit()
        return withContext(Dispatchers.IO) { client.search(query) }
    }

    private fun writeCache() {
        val data = snapshot ?: return
        runCatching {
            cacheFile.writeText(json.encodeToString(Snapshot.serializer(), data))
        }
    }

    private fun readCache() {
        if (!cacheFile.exists()) return
        runCatching {
            snapshot = json.decodeFromString(Snapshot.serializer(), cacheFile.readText())
        }
    }

    companion object {
        /** Bump with any change to how the library is built, so caches refresh on next launch. */
        const val VERSION = 7
        private val DATE = SimpleDateFormat("dd MMM yyyy HH:mm:ss z", Locale.US)
    }
}

/**
 * Artwork for an artist page. `getArtists` gives every artist a profile picture, but Bandcamp
 * repeats one image across a label and everyone it releases: Blang's logo arrived for the label
 * and four of its artists, and two Stanley Brinks projects shared a picture belonging to neither.
 * An image only counts as the artist's own when nobody else in the collection claims it. Without
 * one, a record credited to the artist is a better answer than a stranger's face.
 */
internal fun chooseArtistCover(
    ownImage: String?,
    imageOwners: Map<String, Int>,
    creditedAlbum: String?,
    firstAlbum: String?,
): String? {
    val own = ownImage?.takeIf(String::isNotBlank)
    val shared = own != null && (imageOwners[own] ?: 0) > 1
    return when {
        own != null && !shared -> own
        creditedAlbum != null -> creditedAlbum
        else -> firstAlbum ?: own
    }
}
