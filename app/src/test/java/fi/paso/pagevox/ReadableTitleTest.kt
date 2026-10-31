package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadableTitleTest {

    @Test
    fun realTitlesPassThroughUntouched() {
        assertEquals("Chapter One", readableTitle("Chapter One", "https://example.com/c1.txt"))
        // A title that merely mentions a site is still a title.
        assertEquals("Why example.com went down", readableTitle("Why example.com went down", null))
    }

    @Test
    fun aUrlTitleBecomesTheFileName() {
        val url = "https://example.com/books/chapter-01.txt"
        assertEquals("chapter-01.txt", readableTitle(url, url))
    }

    @Test
    fun percentEncodingIsDecoded() {
        val url = "https://example.com/my%20notes.txt"
        assertEquals("my notes.txt", readableTitle(url, url))
    }

    @Test
    fun trailingSlashAndQueryDoNotHideTheName() {
        assertEquals("docs", readableTitle("https://example.com/docs/", null))
        assertEquals("read.txt", readableTitle("https://example.com/read.txt?session=abc#top", null))
    }

    @Test
    fun aBareSiteUrlBecomesTheSiteName() {
        assertEquals("example.com", readableTitle("https://www.example.com/", null))
    }

    @Test
    fun nothingUsableIsNull() {
        assertNull(readableTitle("", "https://example.com/a.txt"))
        assertNull(readableTitle("   ", null))
    }

    // What WebView actually reports for an untitled page: Chromium drops the
    // scheme (https too), a leading "www." and decodes %20.

    @Test
    fun chromiumDisplayFormBecomesTheFileName() {
        assertEquals(
            "chapter-01.txt",
            readableTitle("example.com/books/chapter-01.txt", "https://www.example.com/books/chapter-01.txt")
        )
    }

    @Test
    fun chromiumDisplayFormWithSpacesTakesTheNameFromTheRealUrl() {
        assertEquals(
            "my file.txt",
            readableTitle("example.com/books/my file.txt", "https://example.com/books/my%20file.txt")
        )
    }

    @Test
    fun chromiumDisplayFormWithAPort() {
        assertEquals(
            "b.txt",
            readableTitle("files.example.org:8443/a/b.txt", "https://files.example.org:8443/a/b.txt")
        )
    }

    @Test
    fun chromiumDisplayFormWithoutAPageUrl() {
        assertEquals("b.txt", readableTitle("example.com/a/b.txt", null))
    }

    @Test
    fun titlesThatOnlyMentionTheSiteStayTitles() {
        assertEquals("example.com — home", readableTitle("example.com — home", "https://example.com/"))
        assertEquals("Node.js", readableTitle("Node.js", "https://nodejs.org/en/about"))
    }
}
