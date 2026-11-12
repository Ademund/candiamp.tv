package tv.candiamp.data

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the signing rules and the two response shapes the linking flow depends on. */
class LastFmClientTest {

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `signature follows the documented auth example`() {
        val params = mapOf(
            "method" to "auth.getSession",
            "api_key" to "xxxxxxxxxx",
            "token" to "yyyyyy",
        )
        assertEquals(
            "b87d61da3cda91a8b6746c4aef55d6f8",
            LastFmClient.signature(params, "ilovecher"),
        )
    }

    @Test
    fun `signature sorts batch parameters by ascii`() {
        // artist[10] sorts before artist[1], which is exactly the trap in the scrobble docs.
        val params = mapOf(
            "api_key" to "k",
            "method" to "track.scrobble",
            "sk" to "s",
            "artist[1]" to "B",
            "artist[10]" to "C",
            "artist[2]" to "A",
        )
        assertEquals(
            "daaaea5b5e18aae0768e178aaf0d90ec",
            LastFmClient.signature(params, "sec"),
        )
    }

    @Test
    fun `format and callback are not part of the signature`() {
        val base = mapOf("api_key" to "k", "method" to "auth.getToken")
        val extra = base + mapOf("format" to "json", "callback" to "https://example.com/")
        assertEquals(LastFmClient.signature(base, "mysecret"), LastFmClient.signature(extra, "mysecret"))
        assertEquals("0b3350f666ae875a2d72d6f60ac3de11", LastFmClient.signature(extra, "mysecret"))
    }

    @Test
    fun `token response parses`() {
        assertEquals("cf45fe5a3e3cebe168480a086d7fe481", parseToken(obj("""{"token":"cf45fe5a3e3cebe168480a086d7fe481"}""")))
    }

    @Test
    fun `session response parses the username and key`() {
        val session = parseSession(
            obj("""{"session":{"name":"MyLastFMUsername","key":"d580d57f32848f5dcf574d1ce18d78b2","subscriber":"0"}}"""),
        )
        assertEquals("MyLastFMUsername", session.username)
        assertEquals("d580d57f32848f5dcf574d1ce18d78b2", session.key)
    }

    @Test
    fun `error codes are classified`() {
        val auth = parseError(obj("""{"error":9,"message":"Invalid session key - Please re-authenticate"}"""))
        assertNotNull(auth)
        assertEquals(9, auth?.code)
        assertTrue(auth!!.needsReauth)

        val offline = parseError(obj("""{"error":16,"message":"Temporary error"}"""))
        assertTrue(offline!!.retryable)

        val malformed = parseError(obj("""{"error":6,"message":"Missing required parameter artist"}"""))
        assertEquals(6, malformed?.code)
        assertTrue(!malformed!!.needsReauth && !malformed.retryable)

        assertNull(parseError(obj("""{"session":{"name":"x","key":"y"}}""")))
    }

    @Test
    fun `user response parses playcount as a string`() {
        val user = parseUser(
            obj("""{"user":{"name":"rj","playcount":"123456","subscriber":"0"}}"""),
        )
        assertEquals("rj", user.username)
        assertEquals(123456L, user.playcount)
    }

    @Test
    fun `authorization url carries both parameters`() {
        assertEquals(
            "https://www.last.fm/api/auth/?api_key=abc&token=def",
            LastFmClient.authUrl("abc", "def"),
        )
    }
}
