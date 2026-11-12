package tv.candiamp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio parser reads Bandcamp's HTML, so these fixtures pin the shape that was measured in
 * November 2026. They are deliberately small: enough to catch a silent field rename.
 */
class BandcampRadioTest {

    private fun page(payload: String): String =
        """<!doctype html><html><body><div id="ArchiveApp" data-blob="${payload.replace("\"", "&quot;")}"></div></body></html>"""

    @Test
    fun `index parses episode summaries`() {
        val payload = """
            {"appData":{"shows":[
              {"itemUrl":"/radio/bandcamp-selects/1018","title":"Robocobra Quartet","franchiseName":"Bandcamp Selects","date":"09 Nov 2026 00:00:00 GMT","description":"Belfast's spoken-word, Jazz-punk outfit","imageId":48231961},
              {"itemUrl":"/radio/the-metal-show/1015","title":"Enslaved","franchiseName":"The Metal Show","date":"06 Nov 2026 00:00:00 GMT","imageId":48231989}
            ],"numPages":50,"currentPage":1}}
        """.trimIndent()

        val shows = BandcampRadioSource().parseShows(page(payload))

        assertEquals(2, shows.size)
        assertEquals("bandcamp-selects/1018", shows[0].id)
        assertEquals("Robocobra Quartet", shows[0].title)
        assertEquals("Bandcamp Selects", shows[0].show)
        assertEquals("https://f4.bcbits.com/img/0048231961_170.jpg", shows[0].artUrl())
        assertEquals("https://bandcamp.com/radio/bandcamp-selects/1018", shows[0].pageUrl)
        assertEquals(0, shows[0].durationSec)
    }

    @Test
    fun `episode parses compiled stream, tracklist and summary duration`() {
        val payload = """
            {"appData":{"show":{
              "itemType":"radio","itemId":1018,"itemUrl":"/radio/bandcamp-selects/1018",
              "title":"Robocobra Quartet","subtitle":"Bandcamp Selects","imageId":48231961,
              "date":"09 Nov 2026 00:00:00 GMT","description":"Belfast&#39;s spoken-word, Jazz-punk outfit",
              "compiledTrack":{"streamUrl":"https://bandcamp.com/stream_redirect?enc=mp3-128&track_id=1&ts=2&t=abc","duration":5346.9},
              "tracks":[
                {"id":1,"title":"Say Less","artistName":"Joshua Idehen","duration":192.93,"timecode":0,"album":null,"streamUrl":"https://t4.bcbits.com/stream/x"},
                {"id":2,"title":"AAAH!","artistName":"Odette","duration":171.016,"timecode":190,"album":{"id":3,"title":"Blink","artId":2376601169},"streamUrl":"https://t4.bcbits.com/stream/y"}
              ],
              "tracksSummary":{"totalCount":2,"totalDuration":5347.0}
            }}}
        """.trimIndent()

        val parsed = BandcampRadioSource().parseEpisode(page(payload))
        val episode = parsed.episode

        assertEquals("bandcamp-selects/1018", episode.id)
        assertEquals("Robocobra Quartet", episode.title)
        assertEquals("Bandcamp Selects", episode.show)
        assertEquals(5347, episode.durationSec)
        assertEquals("Belfast's spoken-word, Jazz-punk outfit", episode.description)
        assertEquals(2, episode.tracks.size)
        assertNull(episode.tracks[0].albumTitle)
        assertEquals("Blink", episode.tracks[1].albumTitle)
        assertEquals(190, episode.tracks[1].timecodeSec)
        assertEquals(171, episode.tracks[1].durationSec)
        assertEquals("Say Less", episode.tracks[0].title)
        assertEquals(
            "https://bandcamp.com/stream_redirect?enc=mp3-128&track_id=1&ts=2&t=abc",
            parsed.streamUrl,
        )
    }

    @Test
    fun `a changed page shape throws a readable error`() {
        val error = assertThrows(RadioException::class.java) {
            BandcampRadioSource().parseShows("<html><body><p>Nothing to see</p></body></html>")
        }
        assertTrue(error.message.orEmpty().contains("changed shape"))
    }

    @Test
    fun `a page with an empty show list throws instead of returning nothing`() {
        val error = assertThrows(RadioException::class.java) {
            BandcampRadioSource().parseShows(page("""{"appData":{"shows":[]}}"""))
        }
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun `data-blob accepts single quotes and decodes entities`() {
        val blob = BandcampRadioSource.dataBlob(
            """<div data-blob = '{"title":"Rock &amp; Roll &#39;26&#39;"}'></div>""",
        )
        assertEquals("""{"title":"Rock & Roll '26'"}""", blob)
    }
}
