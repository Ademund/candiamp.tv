package tv.candiamp.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTest {

    @Test
    fun `nothing queued starts at zero`() {
        assertEquals(0, insertionIndex(currentIndex = -1, count = 0, afterCurrent = false))
        assertEquals(0, insertionIndex(currentIndex = -1, count = 0, afterCurrent = true))
    }

    @Test
    fun `add to queue lands at the end`() {
        assertEquals(5, insertionIndex(currentIndex = 2, count = 5, afterCurrent = false))
    }

    @Test
    fun `play next lands straight after the current track`() {
        assertEquals(3, insertionIndex(currentIndex = 2, count = 5, afterCurrent = true))
    }

    @Test
    fun `play next on the last track still lands at the end`() {
        assertEquals(5, insertionIndex(currentIndex = 4, count = 5, afterCurrent = true))
    }

    @Test
    fun `a current index past the queue is clamped`() {
        assertEquals(5, insertionIndex(currentIndex = 9, count = 5, afterCurrent = true))
    }
}
