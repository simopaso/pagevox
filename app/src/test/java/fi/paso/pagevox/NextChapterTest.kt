package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextChapterTest {

    @Test
    fun onlyWebTextFilesQualify() {
        assertTrue(isTextDocument("https://example.com/book/chapter-01.txt"))
        assertTrue(isTextDocument("http://example.com/notes/README.MD"))
        assertFalse(isTextDocument("https://example.com/article.html"))
        assertFalse(isTextDocument("https://example.com/book/"))
        assertFalse(isTextDocument("file:///android_asset/manual.html"))
        assertFalse(isTextDocument("file:///sdcard/book.txt"))
    }

    @Test
    fun folderKeepsItsEncoding() {
        assertEquals(
            "https://example.com/my%20book/",
            folderOf("https://example.com/my%20book/chapter%201.txt")
        )
    }

    @Test
    fun naturalOrderReadsNumbersAsNumbers() {
        val sorted = listOf("chapter-10.txt", "chapter-2.txt", "Chapter-1.txt", "chapter-02b.txt")
            .sortedWith(naturalOrder)
        assertEquals(listOf("Chapter-1.txt", "chapter-2.txt", "chapter-02b.txt", "chapter-10.txt"), sorted)
    }

    // A trimmed-down Apache autoindex page: sort links, the parent folder, a
    // subfolder, a file of another type, and the chapters out of order.
    private val listing = """
        <html><body><h1>Index of /book</h1><pre>
        <a href="?C=N;O=D">Name</a> <a href="?C=M;O=A">Last modified</a>
        <a href="/">Parent Directory</a>
        <a href="extras/">extras/</a>
        <a href="cover.jpg">cover.jpg</a>
        <a href="chapter-10.txt">chapter-10.txt</a>
        <a href="chapter-1.txt">chapter-1.txt</a>
        <a href="chapter-2.txt">chapter-2.txt</a>
        <a href="/book/chapter-3.txt">chapter-3.txt</a>
        </pre></body></html>
    """.trimIndent()

    @Test
    fun listingGivesTheNextChapterInNaturalOrder() {
        val base = "https://example.com/book/"
        assertEquals("https://example.com/book/chapter-2.txt", nextFromListing(base + "chapter-1.txt", base, listing))
        assertEquals("https://example.com/book/chapter-3.txt", nextFromListing(base + "chapter-2.txt", base, listing))
        assertEquals("https://example.com/book/chapter-10.txt", nextFromListing(base + "chapter-3.txt", base, listing))
    }

    @Test
    fun theLastChapterHasNoNext() {
        val base = "https://example.com/book/"
        assertNull(nextFromListing(base + "chapter-10.txt", base, listing))
    }

    @Test
    fun aFileMissingFromTheListingStillFindsItsSuccessor() {
        val base = "https://example.com/book/"
        assertEquals("https://example.com/book/chapter-10.txt", nextFromListing(base + "chapter-5.txt", base, listing))
    }

    @Test
    fun listingLinksToOtherSitesAreIgnored() {
        val html = """<a href="https://evil.example/book/chapter-2.txt">x</a>"""
        assertNull(nextFromListing("https://example.com/book/chapter-1.txt", "https://example.com/book/", html))
    }

    @Test
    fun incrementBumpsTheLastNumberAndKeepsPadding() {
        assertEquals("https://e.com/b/chapter-10.txt", incrementedFileUrl("https://e.com/b/chapter-9.txt"))
        assertEquals("https://e.com/b/ch10.txt", incrementedFileUrl("https://e.com/b/ch09.txt"))
        assertEquals("https://e.com/b/ch100.txt", incrementedFileUrl("https://e.com/b/ch099.txt"))
        assertEquals("https://e.com/b/part1-4.txt", incrementedFileUrl("https://e.com/b/part1-3.txt"))
        assertEquals("https://e.com/2024/ch2.txt", incrementedFileUrl("https://e.com/2024/ch1.txt"))
        assertNull(incrementedFileUrl("https://e.com/b/preface.txt"))
    }

    @Test
    fun decodeHonoursCharsetAndCleansUp() {
        val latin1 = "Ålesund".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Ålesund", decodeText(latin1, "text/plain; charset=ISO-8859-1"))
        val bomCrlf = "\uFEFFline one\r\nline two\rthree".toByteArray(Charsets.UTF_8)
        assertEquals("line one\nline two\nthree", decodeText(bomCrlf, "text/plain"))
    }

    // acceptNextUrl: the check that doesn't trust the page's JavaScript.

    @Test
    fun aSameSiteChapterLinkIsAccepted() {
        assertEquals(
            "https://e.com/story/8",
            acceptNextUrl("https://e.com/story/7", "https://e.com/story/8")
        )
        assertEquals(
            "https://E.COM/s?ch=2",
            acceptNextUrl("https://e.com/s?ch=1", "https://E.COM/s?ch=2")
        )
    }

    @Test
    fun otherSitesAndSchemesAreRefused() {
        assertNull(acceptNextUrl("https://e.com/story/7", "https://evil.example/story/8"))
        assertNull(acceptNextUrl("https://e.com/story/7", "javascript:alert(1)"))
        assertNull(acceptNextUrl("https://e.com/story/7", "file:///sdcard/x.html"))
        assertNull(acceptNextUrl("https://e.com/story/7", "intent://x#Intent;end"))
    }

    @Test
    fun thePageItselfIsNotItsNextChapter() {
        assertNull(acceptNextUrl("https://e.com/story/7", "https://e.com/story/7/"))
        assertNull(acceptNextUrl("https://e.com/story/7", "https://e.com/story/7#comments"))
    }

    @Test
    fun missingOrJunkIsRefused() {
        assertNull(acceptNextUrl("https://e.com/a", null))
        assertNull(acceptNextUrl("https://e.com/a", "   "))
        assertNull(acceptNextUrl(null, "https://e.com/b"))
        assertNull(acceptNextUrl("https://e.com/a", "https://e.com/" + "x".repeat(3000)))
    }
}
