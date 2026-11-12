package tv.candiamp.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ScrobbleQueueTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("candiamp-scrobbles", ".json").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun entry(artist: String, track: String, timestamp: Long) = ScrobbleEntry(
        artist = artist,
        track = track,
        timestamp = timestamp,
    )

    @Test
    fun `batches are oldest first and capped at fifty`() {
        val queue = ScrobbleQueue(file)
        val now = System.currentTimeMillis() / 1000L
        repeat(60) { index -> queue.add(entry("artist", "track $index", now + index)) }

        val batch = queue.takeBatch(50)

        assertEquals(50, batch.size)
        assertEquals("track 0", batch.first().track)
        assertEquals("track 49", batch.last().track)
    }

    @Test
    fun `removing a batch keeps the unsent entries and survives a reload`() {
        val queue = ScrobbleQueue(file)
        val now = System.currentTimeMillis() / 1000L
        repeat(60) { index -> queue.add(entry("artist", "track $index", now + index)) }
        val batch = queue.takeBatch(50)

        assertEquals(10, queue.remove(batch))
        assertEquals(10, ScrobbleQueue(file).count())
    }

    @Test
    fun `the same play is not queued twice`() {
        val queue = ScrobbleQueue(file)
        val now = System.currentTimeMillis() / 1000L
        queue.add(entry("Artist", "Track", now))
        queue.add(entry("artist", "track", now))

        assertEquals(1, queue.count())
    }

    @Test
    fun `entries older than thirteen days are pruned`() {
        val queue = ScrobbleQueue(file)
        val now = System.currentTimeMillis() / 1000L
        queue.add(entry("old", "old", now - 14L * 24 * 60 * 60))
        queue.add(entry("new", "new", now))

        assertEquals(1, queue.count())
        assertEquals("new", queue.takeBatch().single().artist)
    }

    @Test
    fun `a written queue is readable by a fresh instance`() {
        ScrobbleQueue(file).add(entry("artist", "track", System.currentTimeMillis() / 1000L))

        assertTrue(file.exists())
        assertEquals(1, ScrobbleQueue(file).count())
    }
}
