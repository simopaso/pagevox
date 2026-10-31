package fi.paso.pagevox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamePathTest {

    @Test
    fun queryAndFragmentRewritesAreTheSamePage() {
        assertTrue(samePath("https://e.com/story/1", "https://e.com/story/1?utm_source=x"))
        assertTrue(samePath("https://e.com/story/1", "https://e.com/story/1#chapter-3"))
        assertTrue(samePath("https://e.com/story/1/", "https://E.com/story/1"))
        assertTrue(samePath("http://e.com/a", "https://e.com/a"))
    }

    @Test
    fun aNewPathIsANewPage() {
        assertFalse(samePath("https://e.com/stories/x/1", "https://e.com/stories/x/8"))
        assertFalse(samePath("https://e.com/collections", "https://e.com/stories/x"))
        assertFalse(samePath("https://a.com/p", "https://b.com/p"))
        assertFalse(samePath("https://e.com:8443/p", "https://e.com/p"))
    }

    @Test
    fun garbageIsNeverTheSame() {
        assertFalse(samePath("not a url", "not a url"))
    }
}
