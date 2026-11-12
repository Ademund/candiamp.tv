package tv.candiamp.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.candiamp.data.RadioTrack

class PlaybackRadioTest {

    private val tracks = listOf(
        RadioTrack(id = "a", title = "First", timecodeSec = 0, durationSec = 190),
        RadioTrack(id = "b", title = "Second", timecodeSec = 190, durationSec = 169),
        RadioTrack(id = "c", title = "Third", timecodeSec = 359, durationSec = 200),
    )

    @Test
    fun `the first track owns everything before its successor`() {
        assertEquals(0, radioTrackIndex(tracks, 0))
        assertEquals(0, radioTrackIndex(tracks, 189_999))
    }

    @Test
    fun `a timecode boundary switches track exactly`() {
        assertEquals(1, radioTrackIndex(tracks, 190_000))
        assertEquals(2, radioTrackIndex(tracks, 359_000))
    }

    @Test
    fun `past the last timecode stays on the last track`() {
        assertEquals(2, radioTrackIndex(tracks, 10_000_000))
    }

    @Test
    fun `an empty tracklist has no active track`() {
        assertEquals(-1, radioTrackIndex(emptyList(), 0))
    }
}
