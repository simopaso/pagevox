package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupTest {

    private val pages = listOf(
        WebPage("https://example.com/a", "Article A", position = 12, sentenceCount = 40, remainingMs = 90_000L),
        WebPage("https://example.com/b", "Article B")
    )

    @Test
    fun roundTripKeepsEverything() {
        val backup = LibraryBackup(
            homeUrl = "https://home.example",
            lastUrl = "https://example.com/a",
            lastSentenceIndex = 12,
            forceDarkWeb = true,
            textZoom = 130,
            speechRate = 1.25f,
            readerMode = true,
            followAlong = false,
            selectedVoice = "fi-fi-x-fia-local",
            history = pages,
            bookmarks = pages.take(1)
        )
        val restored = parseLibraryBackup(backup.toJson("2.20", "2026-09-25T10:00:00Z"))
        assertEquals(backup, restored)
    }

    @Test
    fun missingSettingsStayUnset() {
        // An older or hand-trimmed backup must not reset settings it doesn't
        // mention — null means "leave the current value alone".
        val restored = parseLibraryBackup("""{"format":"pagevox-backup","version":1}""")
        assertNull(restored.homeUrl)
        assertNull(restored.textZoom)
        assertNull(restored.speechRate)
        assertNull(restored.readerMode)
        assertTrue(restored.history.isEmpty())
    }

    @Test
    fun falseAndZeroAreRealValuesNotMissing() {
        // org.json's opt getters return false/0 for a missing key too; the parser
        // must still tell an explicit false apart from absence.
        val restored = parseLibraryBackup(
            """{"format":"pagevox-backup","settings":{"forceDarkWeb":false,"lastSentenceIndex":0}}"""
        )
        assertEquals(false, restored.forceDarkWeb)
        assertEquals(0, restored.lastSentenceIndex)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val restored = parseLibraryBackup(
            """{"format":"pagevox-backup","settings":{"textZoom":5000,"speechRate":40,"lastSentenceIndex":-3}}"""
        )
        assertEquals(MAX_TEXT_ZOOM, restored.textZoom)
        assertEquals(3.0f, restored.speechRate)
        assertEquals(0, restored.lastSentenceIndex)
    }

    @Test
    fun badPageRowsAreSkippedNotFatal() {
        val restored = parseLibraryBackup(
            """{"format":"pagevox-backup","history":[{"title":"no url"},{"url":"https://ok.example","position":-1},"junk"]}"""
        )
        assertEquals(listOf(WebPage("https://ok.example", "https://ok.example")), restored.history)
    }

    @Test(expected = InvalidBackupException::class)
    fun unrelatedJsonIsRejected() {
        parseLibraryBackup("""{"hello":"world"}""")
    }

    @Test(expected = InvalidBackupException::class)
    fun nonJsonIsRejected() {
        parseLibraryBackup("PK\u0003\u0004 definitely a zip file")
    }
}
