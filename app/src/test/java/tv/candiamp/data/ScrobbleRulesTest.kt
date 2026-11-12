package tv.candiamp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleRulesTest {

    @Test
    fun `thirty second tracks are never scrobbled`() {
        assertFalse(ScrobbleRules.shouldScrobble(30, 10 * 60 * 1000L))
    }

    @Test
    fun `short tracks need half their length`() {
        assertFalse(ScrobbleRules.shouldScrobble(31, 15_499))
        assertTrue(ScrobbleRules.shouldScrobble(31, 15_500))
    }

    @Test
    fun `five minute tracks need two and a half minutes`() {
        assertFalse(ScrobbleRules.shouldScrobble(300, 149_999))
        assertTrue(ScrobbleRules.shouldScrobble(300, 150_000))
    }

    @Test
    fun `long tracks cap at four minutes`() {
        assertFalse(ScrobbleRules.shouldScrobble(600, 239_999))
        assertTrue(ScrobbleRules.shouldScrobble(600, 240_000))
    }

    @Test
    fun `unknown duration uses the four minute rule`() {
        assertFalse(ScrobbleRules.shouldScrobble(0, 239_999))
        assertTrue(ScrobbleRules.shouldScrobble(0, 240_000))
        assertTrue(ScrobbleRules.thresholdMs(0) == 240_000L)
    }
}
