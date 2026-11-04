package tv.candiamp.data

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SubsonicException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { AUTH, NETWORK, SERVER, FORMAT }
}

/**
 * A Bandcamp Subsonic client.
 *
 * Bandcamp answers in four different ways, so [call] normalises them:
 *  - HTTP 500 with an empty body when the credentials are wrong;
 *  - HTTP 200 with `{"error":true,"error_message":"bad version"}` for endpoints it does not have;
 *  - HTTP 200 with a Subsonic `status: failed` envelope for a rejected request;
 *  - HTTP 200 with XML when a stream id is bad.
 * `ping` is never used as a health check: it says ok for any credentials.
 */
class SubsonicClient(server: String, private val user: String, private val password: String, private val salt: String) {

    val base: String = normalize(server)
    private val token: String = md5(password + salt)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun coverUrl(id: String?, size: Int = 700): String? {
        if (id.isNullOrBlank()) return null
        return "$base/rest/getCoverArt.view?${auth()}&id=${enc(id)}&size=$size"
    }

    fun streamUrl(songId: String): String = "$base/rest/stream.view?${auth()}&id=${enc(songId)}"

    private fun auth(): String = "u=${enc(user)}&t=$token&s=$salt&v=1.16.1&c=candiamp&f=json"

    private fun call(endpoint: String, params: List<Pair<String, String>> = emptyList()): JsonObject {
        val url = buildString {
            append(base).append("/rest/").append(endpoint).append(".view?").append(auth())
            for ((k, v) in params) append('&').append(k).append('=').append(enc(v))
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "candiamp/0.1 (Android TV)")
            .build()
        val body = try {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw SubsonicException("Can't reach Bandcamp. Check the TV's connection.", SubsonicException.Kind.NETWORK)
        }
        if (body.isBlank()) {
            throw SubsonicException("Bandcamp did not accept the credentials.", SubsonicException.Kind.AUTH)
        }
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw SubsonicException(
            "Unexpected answer from $endpoint: ${body.take(160).replace('\n', ' ')}",
            SubsonicException.Kind.FORMAT,
        )
        root.obj("error")?.let { throw SubsonicException(it.text("error_message") ?: "Server error", SubsonicException.Kind.SERVER) }
        val response = root.obj("subsonic-response")
            ?: throw SubsonicException(
                "No Subsonic envelope from $endpoint: ${body.take(160).replace('\n', ' ')}",
                SubsonicException.Kind.FORMAT,
            )
        if (response.text("status") != "ok") {
            val message = response.obj("error")?.text("message")
            throw SubsonicException(message ?: "Bandcamp said no.", SubsonicException.Kind.SERVER)
        }
        return response
    }

    fun artists(): List<Artist> = call("getArtists")
        .obj("artists")
        ?.objects("index")
        ?.flatMap { it.objects("artist") }
        ?.map { it.toArtist() }
        ?: emptyList()

    fun artist(id: String): Artist {
        val a = call("getArtist", listOf("id" to id)).obj("artist")
            ?: throw SubsonicException("That artist is not there anymore.", SubsonicException.Kind.SERVER)
        return a.toArtist().copy(albums = a.objects("album").map { it.toAlbum() })
    }

    fun album(id: String): Album {
        val a = call("getAlbum", listOf("id" to id)).obj("album")
            ?: throw SubsonicException("That album is not there anymore.", SubsonicException.Kind.SERVER)
        return a.toAlbum().copy(songs = a.objects("song").map { it.toSong() })
    }

    fun albumList(type: String, size: Int): List<Album> = call(
        "getAlbumList2", listOf("type" to type, "size" to size.toString()),
    ).obj("albumList2")?.objects("album")?.map { it.toAlbum() } ?: emptyList()

    fun genres(): List<Pair<String, Int>> = call("getGenres")
        .obj("genres")
        ?.objects("genre")
        ?.mapNotNull { g -> (g.text("value") ?: return@mapNotNull null) to (g.int("albumCount") ?: 0) }
        ?.filter { it.second > 0 }
        ?: emptyList()

    /**
     * Bandcamp's search covers your own collection only — no catalogue-wide results, and song
     * matching is on title. A miss is a miss, not an error.
     */
    fun search(query: String): SearchHit {
        val result = call(
            "search3",
            listOf(
                "query" to query,
                "artistCount" to "20",
                "albumCount" to "40",
                "songCount" to "50",
            ),
        ).obj("searchResult3") ?: return SearchHit()
        return SearchHit(
            artists = result.objects("artist").map { it.toArtist() },
            albums = result.objects("album").map { it.toAlbum() },
            songs = result.objects("song").map { it.toSong() },
        )
    }

    private fun JsonObject.toArtist() = Artist(
        id = text("id") ?: "",
        name = text("name") ?: "Unknown artist",
        coverArt = text("coverArt"),
    )

    private fun JsonObject.toAlbum() = Album(
        id = text("id") ?: "",
        name = text("name") ?: "Untitled",
        artist = text("artist"),
        artistId = text("artistId"),
        coverArt = text("coverArt"),
        songCount = int("songCount") ?: 0,
        durationSec = int("duration") ?: 0,
        created = text("created"),
        genre = text("genre"),
    )

    private fun JsonObject.toSong() = Song(
        id = text("id") ?: "",
        title = text("title") ?: "Untitled",
        albumId = text("albumId"),
        artistId = text("artistId"),
        albumName = text("album"),
        artistName = text("artist"),
        track = int("track"),
        year = int("year"),
        durationSec = int("duration") ?: 0,
        coverArt = text("coverArt"),
        suffix = text("suffix"),
        bitRate = int("bitRate"),
        sizeBytes = long("size") ?: 0,
    )

    companion object {
        fun normalize(server: String): String {
            var s = server.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            // People paste the fan-settings page, or bandcamp.com; the API lives here.
            if (s.endsWith("bandcamp.com")) s = "$s/api/subsonic"
            // The published server URL is .../api/subsonic, but calls live under .../rest/.
            if (s.endsWith("/rest")) s = s.removeSuffix("/rest")
            return s
        }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun hostOf(server: String): String = runCatching { URI(normalize(server)).host ?: "" }.getOrDefault("")
    }
}
