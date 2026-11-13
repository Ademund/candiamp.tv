package tv.candiamp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistCoverTest {

    private val unique = mapOf("ci:111" to 1)

    // Measured against the live API, 2026-11: Blang's logo came back for five artists and two
    // Stanley Brinks projects shared a picture belonging to neither.
    private val shared = mapOf("ci:39889515" to 5)

    @Test
    fun `an artist's own picture is used when nobody else claims it`() {
        assertEquals(
            "ci:111",
            chooseArtistCover(
                ownImage = "ci:111",
                imageOwners = unique,
                creditedAlbum = "ca:999",
                firstAlbum = "ca:998",
            ),
        )
    }

    @Test
    fun `a shared picture is not the artist's`() {
        assertEquals(
            "ca:999",
            chooseArtistCover(
                ownImage = "ci:39889515",
                imageOwners = shared,
                creditedAlbum = "ca:999",
                firstAlbum = "ca:998",
            ),
        )
    }

    @Test
    fun `shared picture without a record credited to the artist falls back to the first record`() {
        assertEquals(
            "ca:998",
            chooseArtistCover(
                ownImage = "ci:39889515",
                imageOwners = shared,
                creditedAlbum = null,
                firstAlbum = "ca:998",
            ),
        )
    }

    @Test
    fun `no artist picture leaves the records`() {
        assertEquals(
            "ca:999",
            chooseArtistCover(
                ownImage = null,
                imageOwners = emptyMap(),
                creditedAlbum = "ca:999",
                firstAlbum = "ca:998",
            ),
        )
    }

    @Test
    fun `a blank picture is no picture`() {
        assertEquals(
            "ca:998",
            chooseArtistCover(
                ownImage = " ",
                imageOwners = emptyMap(),
                creditedAlbum = null,
                firstAlbum = "ca:998",
            ),
        )
    }

    @Test
    fun `shared picture is still better than nothing when the artist has no records`() {
        assertEquals(
            "ci:39889515",
            chooseArtistCover(
                ownImage = "ci:39889515",
                imageOwners = shared,
                creditedAlbum = null,
                firstAlbum = null,
            ),
        )
    }

    @Test
    fun `an artist with nothing at all gets no artwork`() {
        assertNull(
            chooseArtistCover(
                ownImage = null,
                imageOwners = emptyMap(),
                creditedAlbum = null,
                firstAlbum = null,
            ),
        )
    }
}
