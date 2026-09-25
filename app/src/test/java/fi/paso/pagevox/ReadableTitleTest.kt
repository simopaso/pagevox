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
}
