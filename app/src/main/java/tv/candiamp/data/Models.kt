package tv.candiamp.data

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val albumId: String? = null,
    val artistId: String? = null,
    val albumName: String? = null,
    val artistName: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val durationSec: Int = 0,
    val coverArt: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val sizeBytes: Long = 0,
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val durationSec: Int = 0,
    val created: String? = null,
    val genre: String? = null,
    val songs: List<Song> = emptyList(),
    /** Bandcamp lists this, but there is no album or track behind it to stream. */
    val unavailable: Boolean = false,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albums: List<Album> = emptyList(),
    /** Bandcamp returns labels and artists in one list; a label's page credits several artists. */
    val isLabel: Boolean = false,
)

@Serializable
data class Snapshot(
    val artists: List<Artist>,
    val albums: List<Album>,
    val syncedAt: Long,
    /** Bumped when the shape or the meaning of the cached library changes. */
    val version: Int = 0,
)

/** What `search3` returns: your own collection, matched by name. */
data class SearchHit(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/** Everything the app needs to know about one record. */
val Album.headline: String get() = artist ?: "Unknown artist"

fun Album.runtime(): String = durationText(durationSec)
fun Song.runtime(): String = durationText(durationSec)

fun durationText(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
