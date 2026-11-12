package tv.candiamp.data

import kotlinx.serialization.Serializable

/** One track inside a radio episode's timestamped tracklist. */
@Serializable
data class RadioTrack(
    val id: String = "",
    val title: String,
    val artistName: String? = null,
    val albumTitle: String? = null,
    /** Seconds measured from the start of the compiled episode stream. */
    val timecodeSec: Int = 0,
    val durationSec: Int = 0,
)

/**
 * A Bandcamp Radio episode.
 *
 * Split deliberately: [id], [title], the tracklist and the metadata are cacheable, while the
 * signed stream URL is not — it carries `ts`/`t` and expires, so it is resolved at play time and
 * kept only in memory (see [BandcampRadioSource] and [RadioLibrary]).
 */
@Serializable
data class RadioEpisode(
    /** `bandcamp-selects/1018` — the path below `/radio/`, also the cache key. */
    val id: String,
    val title: String,
    /** The show or franchise, e.g. "Bandcamp Selects". */
    val show: String? = null,
    /** Raw Bandcamp date, e.g. `09 Nov 2026 00:00:00 GMT`. */
    val date: String? = null,
    val description: String? = null,
    /** Tap into `https://f4.bcbits.com/img/<imageId>_<size>.jpg`. */
    val imageId: String? = null,
    /** Whole-episode duration; 0 until the episode page has been read once. */
    val durationSec: Int = 0,
    val tracks: List<RadioTrack> = emptyList(),
    /** When this copy was parsed, so a very old cache can be refreshed. */
    val fetchedAt: Long = 0,
) {
    val pageUrl: String get() = "https://bandcamp.com/radio/$id"

    fun artUrl(size: Int = 170): String? = imageId?.let { "https://f4.bcbits.com/img/${it.padStart(10, '0')}_$size.jpg" }
}

/** The episode page as parsed: metadata plus the signed URL for the whole episode. */
data class RadioPage(val episode: RadioEpisode, val streamUrl: String?)

@Serializable
data class RadioCache(
    val shows: List<RadioEpisode>,
    val syncedAt: Long,
    val version: Int = 0,
)

/** A scraped page we could not read. The message is safe to show on the TV. */
class RadioException(message: String) : Exception(message)
