package tv.candiamp.data

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmStatsTest {

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `profile parses counts, registration and the largest image`() {
        val profile = parseUser(
            obj(
                """
                {"user":{"name":"rj","realname":"RJ","playcount":"1234","artist_count":"5",
                "track_count":"99","album_count":"7","url":"https://www.last.fm/user/rj",
                "registered":{"unixtime":"1200000000"},
                "image":[
                  {"size":"small","#text":"https://img/small.jpg"},
                  {"size":"mega","#text":"https://img/mega.jpg"}
                ]}}
                """.trimIndent(),
            ),
        )

        assertEquals("rj", profile.username)
        assertEquals("RJ", profile.realName)
        assertEquals(1234L, profile.playcount)
        assertEquals(5, profile.artistCount)
        assertEquals(99, profile.trackCount)
        assertEquals(7, profile.albumCount)
        assertEquals(1_200_000_000L, profile.registeredAt)
        assertEquals("https://img/mega.jpg", profile.imageUrl)
    }

    @Test
    fun `placeholder images become null`() {
        val profile = parseUser(
            obj(
                """
                {"user":{"name":"rj","playcount":"0",
                "image":[{"size":"mega","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"}]}}
                """.trimIndent(),
            ),
        )
        assertNull(profile.imageUrl)
    }

    @Test
    fun `top artists parse name, playcount and image`() {
        val artists = parseTopArtists(
            obj(
                """
                {"topartists":{"artist":[
                  {"name":"Cher","playcount":"42","url":"https://www.last.fm/music/Cher",
                   "image":[{"size":"mega","#text":"https://img/cher.jpg"}]},
                  {"name":"Björk","playcount":"17"}
                ],"@attr":{"user":"rj"}}}
                """.trimIndent(),
            ),
        )

        assertEquals(2, artists.size)
        assertEquals("Cher", artists[0].name)
        assertEquals(42, artists[0].playcount)
        assertEquals("https://img/cher.jpg", artists[0].imageUrl)
        assertEquals("Björk", artists[1].name)
        assertNull(artists[1].imageUrl)
    }

    @Test
    fun `top albums parse the nested artist and tolerate a single object`() {
        val albums = parseTopAlbums(
            obj(
                """
                {"topalbums":{"album":{"name":"Believe","playcount":"7",
                "artist":{"name":"Cher","url":"https://www.last.fm/music/Cher"},
                "image":[{"size":"extralarge","#text":"https://img/believe.jpg"}]}}}
                """.trimIndent(),
            ),
        )

        assertEquals(1, albums.size)
        assertEquals("Believe", albums[0].name)
        assertEquals("Cher", albums[0].artist)
        assertEquals(7, albums[0].playcount)
        assertEquals("https://img/believe.jpg", albums[0].imageUrl)
    }

    @Test
    fun `top tracks parse duration and artist`() {
        val tracks = parseTopTracks(
            obj(
                """
                {"toptracks":{"track":[
                  {"name":"Believe","playcount":"5","duration":"240",
                   "artist":{"name":"Cher","mbid":"x"},"image":[]}
                ]}}
                """.trimIndent(),
            ),
        )

        assertEquals(1, tracks.size)
        assertEquals("Believe", tracks[0].name)
        assertEquals("Cher", tracks[0].artist)
        assertEquals(240, tracks[0].durationSec)
        assertEquals(5, tracks[0].playcount)
    }

    @Test
    fun `recent tracks parse now-playing, album and timestamp`() {
        val recent = parseRecentTracks(
            obj(
                """
                {"recenttracks":{"track":[
                  {"name":"Believe","artist":{"#text":"Cher"},"album":{"#text":"Believe"},
                   "url":"https://www.last.fm/music/Cher/_/Believe",
                   "@attr":{"nowplaying":"true"},"image":[]},
                  {"name":"Song","artist":{"#text":"Artist"},"album":{"#text":""},
                   "date":{"uts":"1700000000","#text":"14 Nov 2023, 22:13"},"image":[]}
                ]}}
                """.trimIndent(),
            ),
        )

        assertEquals(2, recent.size)
        assertTrue(recent[0].nowPlaying)
        assertNull(recent[0].playedAt)
        assertEquals("Believe", recent[0].album)
        assertEquals(1_700_000_000L, recent[1].playedAt)
        assertTrue(!recent[1].nowPlaying)
    }

    @Test
    fun `periods map to the Last-fm api values`() {
        assertEquals("7day", LastFmPeriod.WEEK.api)
        assertEquals("1month", LastFmPeriod.MONTH.api)
        assertEquals("3month", LastFmPeriod.QUARTER.api)
        assertEquals("12month", LastFmPeriod.YEAR.api)
        assertEquals("overall", LastFmPeriod.ALL.api)
        assertEquals(5, LastFmPeriod.entries.size)
    }
}
