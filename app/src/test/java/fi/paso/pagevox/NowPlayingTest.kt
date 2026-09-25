package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {

    private val snapshot = NowPlaying(
        url = "https://example.com/story",
        title = "A story",
        language = "fi",
        sentences = listOf("Otsikko", "Ensimmäinen virke [3].", "Toinen \"lainaus\" virke."),
        spoken = listOf("Otsikko", "Ensimmäinen virke.", "Toinen \"lainaus\" virke."),
        sectionStarts = listOf(0),
        sectionTitles = listOf("Otsikko"),
        imageUrl = "https://example.com/lead.jpg"
    )

    @Test
    fun roundTripKeepsEverything() {
        assertEquals(snapshot, parseNowPlaying(snapshot.toJson()))
    }

    @Test
    fun nullableFieldsRoundTripAsNull() {
        val bare = snapshot.copy(language = null, imageUrl = null)
        assertEquals(bare, parseNowPlaying(bare.toJson()))
    }

    @Test
    fun emptyOrBrokenSnapshotIsNoSnapshot() {
        // A cache file that can't be used must mean "nothing to resume", never
        // an empty page that plays nothing.
        assertNull(parseNowPlaying(snapshot.copy(sentences = emptyList()).toJson()))
        assertNull(parseNowPlaying(snapshot.copy(url = "").toJson()))
        assertNull(parseNowPlaying("{\"sentences\": [\"trunc"))
        assertNull(parseNowPlaying(""))
    }
}
