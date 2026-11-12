package tv.candiamp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bandcamp Radio is not in the Subsonic API, so this reads the website instead.
 *
 * The pages are server-rendered and carry one `data-blob` attribute holding HTML-escaped JSON:
 *  - `https://bandcamp.com/radio` → `appData.shows[]` (the episode index);
 *  - `https://bandcamp.com/radio/<show>/<episode>` → `appData.show` with `compiledTrack`
 *    (the whole episode as one signed mp3) and `tracks[]` (the timestamped tracklist).
 *
 * Scraping is fragile by nature, so this class does nothing but fetch and parse. Anything
 * unreadable raises [RadioException] with a sentence fit for the screen; callers fall back to
 * their cache. It must never be allowed to break collection playback.
 */
class BandcampRadioSource(private val base: String = "https://bandcamp.com/radio") {

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun shows(): List<RadioEpisode> = withContext(Dispatchers.IO) {
        parseShows(fetch(base))
    }

    suspend fun episode(id: String): RadioPage = withContext(Dispatchers.IO) {
        parseEpisode(fetch("$base/${id.trim('/')}"))
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "candiamp/0.1 (Android TV)")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RadioException("Bandcamp Radio answered ${response.code}.")
                }
                response.body.string().ifBlank {
                    throw RadioException("Bandcamp Radio sent an empty page.")
                }
            }
        } catch (e: RadioException) {
            throw e
        } catch (e: IOException) {
            throw RadioException("Can't reach Bandcamp Radio. Check the TV's connection.")
        }
    }

    private fun pageData(html: String): JsonObject {
        val blob = dataBlob(html)
            ?: throw RadioException("Bandcamp's radio page has changed shape. Nothing here is broken — this source just can't read it.")
        return (runCatching { json.parseToJsonElement(blob) }.getOrNull() as? JsonObject)
            ?: throw RadioException("Bandcamp's radio page didn't parse.")
    }

    fun parseShows(html: String): List<RadioEpisode> {
        val app = pageData(html).obj("appData")
            ?: throw RadioException("Bandcamp's radio page has no episode list.")
        val shows = app.objects("shows").mapNotNull { it.toSummary() }
        if (shows.isEmpty()) throw RadioException("Bandcamp Radio has no episodes to list right now.")
        return shows
    }

    fun parseEpisode(html: String): RadioPage {
        val show = pageData(html).obj("appData")?.obj("show")
            ?: throw RadioException("Bandcamp's episode page has no episode on it.")
        val itemUrl = show.text("itemUrl").orEmpty()
        val id = itemUrl.substringAfter("/radio/", "").trim('/')
            .ifBlank { show.text("itemId").orEmpty() }
        if (id.isBlank()) throw RadioException("Bandcamp's episode page has no episode id.")

        val tracks = show.objects("tracks").map { track ->
            val album = track.obj("album")
            RadioTrack(
                id = track.text("id").orEmpty(),
                title = track.text("title") ?: "Untitled",
                artistName = track.text("artistName") ?: album?.text("artistName"),
                albumTitle = album?.text("title"),
                timecodeSec = (track.double("timecode") ?: 0.0).toInt().coerceAtLeast(0),
                durationSec = (track.double("duration") ?: 0.0).toInt().coerceAtLeast(0),
            )
        }.sortedBy { it.timecodeSec }

        val summary = show.obj("tracksSummary")
        val compiled = show.obj("compiledTrack")
        val duration = summary?.double("totalDuration") ?: compiled?.double("duration") ?: 0.0

        val episode = RadioEpisode(
            id = id,
            title = show.text("title") ?: "Untitled episode",
            show = show.text("subtitle") ?: show.obj("franchise")?.text("title"),
            date = show.text("date"),
            description = show.text("description"),
            imageId = show.text("imageId"),
            durationSec = duration.toInt().coerceAtLeast(0),
            tracks = tracks,
            fetchedAt = System.currentTimeMillis(),
        )
        return RadioPage(episode, compiled?.text("streamUrl"))
    }

    private fun JsonObject.toSummary(): RadioEpisode? {
        val id = text("itemUrl").orEmpty().substringAfter("/radio/", "").trim('/')
        if (id.isBlank()) return null
        return RadioEpisode(
            id = id,
            title = text("title") ?: "Untitled episode",
            show = text("franchiseName") ?: text("subtitle"),
            date = text("date"),
            description = text("description"),
            imageId = text("imageId"),
        )
    }

    companion object {
        private const val MARKER = "data-blob"

        /**
         * Pulls the `data-blob` attribute out of the HTML. The JSON inside is entity-escaped
         * (`&quot;` for quotes), and the attribute itself is quoted with `"` or `'`.
         */
        fun dataBlob(html: String): String? {
            val start = html.indexOf(MARKER)
            if (start < 0) return null
            var from = start + MARKER.length
            while (from < html.length && html[from].isWhitespace()) from++
            if (from >= html.length || html[from] != '=') return null
            from++
            while (from < html.length && html[from].isWhitespace()) from++
            if (from >= html.length) return null
            val quote = html[from]
            if (quote != '"' && quote != '\'') return null
            val end = html.indexOf(quote, from + 1)
            if (end < 0) return null
            return unescape(html.substring(from + 1, end))
        }

        fun unescape(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '&') {
                    val end = value.indexOf(';', index + 1)
                    if (end in (index + 1)..(index + 10)) {
                        val entity = value.substring(index + 1, end)
                        val decoded = when (entity) {
                            "quot" -> "\""
                            "apos" -> "'"
                            "amp" -> "&"
                            "lt" -> "<"
                            "gt" -> ">"
                            "nbsp" -> " "
                            else -> numericEntity(entity)
                        }
                        if (decoded != null) {
                            append(decoded)
                            index = end + 1
                            continue
                        }
                    }
                }
                append(char)
                index++
            }
        }

        private fun numericEntity(entity: String): String? {
            val code = when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                else -> null
            } ?: return null
            return runCatching { String(Character.toChars(code)) }.getOrNull()
        }
    }
}
