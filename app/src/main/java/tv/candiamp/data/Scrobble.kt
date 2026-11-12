package tv.candiamp.data

import kotlinx.serialization.Serializable
import java.io.File

/** One play waiting to be sent to Last.fm. */
@Serializable
data class ScrobbleEntry(
    val artist: String,
    val track: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val durationSec: Int? = null,
    /** UNIX seconds, UTC — when the track started playing. */
    val timestamp: Long,
    /** null means Last.fm's default (the user chose it); false for radio. */
    val chosenByUser: Boolean? = null,
)

data class ScrobbleResult(val accepted: Int, val ignored: Int)

/** Last.fm's "when is a scrobble a scrobble" rules. */
object ScrobbleRules {
    const val MIN_TRACK_SECONDS = 30
    const val MAX_LISTEN_MS = 4 * 60 * 1000L

    /** Half the track, capped at four minutes. Unknown duration gets the four-minute rule. */
    fun thresholdMs(durationSec: Int): Long =
        if (durationSec > 0) minOf(durationSec * 500L, MAX_LISTEN_MS) else MAX_LISTEN_MS

    fun shouldScrobble(durationSec: Int, listenedMs: Long): Boolean =
        durationSec !in 1..MIN_TRACK_SECONDS && listenedMs >= thresholdMs(durationSec)
}

/**
 * The durable scrobble queue. Entries are written before they are sent, so a crash or a dead
 * network cannot lose a play. Last.fm rejects anything older than two weeks, so entries are
 * pruned at thirteen days.
 */
class ScrobbleQueue(private val file: File) {

    private val entries = ArrayList<ScrobbleEntry>()

    init {
        readLocked()
    }

    @Synchronized
    fun add(entry: ScrobbleEntry): Int {
        pruneLocked()
        if (entries.none { same(it, entry) }) entries.add(entry)
        if (entries.size > MAX_ENTRIES) {
            entries.sortBy { it.timestamp }
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        writeLocked()
        return entries.size
    }

    @Synchronized
    fun takeBatch(limit: Int = 50): List<ScrobbleEntry> {
        pruneLocked()
        return entries.sortedBy { it.timestamp }.take(limit)
    }

    @Synchronized
    fun remove(batch: List<ScrobbleEntry>): Int {
        val keys = batch.map { key(it) }.toSet()
        entries.removeAll { key(it) in keys }
        writeLocked()
        return entries.size
    }

    @Synchronized
    fun count(): Int {
        pruneLocked()
        return entries.size
    }

    private fun same(a: ScrobbleEntry, b: ScrobbleEntry): Boolean = key(a) == key(b)

    private fun key(entry: ScrobbleEntry): String =
        entry.artist.lowercase() + "|" + entry.track.lowercase() + "|" + entry.timestamp

    private fun pruneLocked(): Boolean {
        val cutoff = System.currentTimeMillis() / 1000L - MAX_AGE_SECONDS
        val removed = entries.removeAll { it.timestamp < cutoff }
        if (removed) writeLocked()
        return removed
    }

    private fun readLocked() {
        if (!file.exists()) return
        runCatching {
            val store = json.decodeFromString(QueueFile.serializer(), file.readText())
            if (store.version == VERSION) entries.addAll(store.entries)
        }
        pruneLocked()
    }

    private fun writeLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            val text = json.encodeToString(QueueFile.serializer(), QueueFile(entries = entries.toList()))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
    }

    @Serializable
    private data class QueueFile(
        val version: Int = VERSION,
        val entries: List<ScrobbleEntry> = emptyList(),
    )

    companion object {
        const val VERSION = 1
        const val MAX_AGE_SECONDS = 13L * 24 * 60 * 60
        const val MAX_ENTRIES = 1_000
    }
}
