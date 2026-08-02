package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards the resume-position invariants that a background/lock-screen pause used
 * to lose. [PlaybackDataRepository.currentIndex] is the process-scoped source of
 * truth for where a plain Play/resume starts: it is seeded when a page's
 * sentences are (re)built, advanced by the service as it reads, and — crucially —
 * survives the service instance being destroyed while paused. These tests pin
 * that seeding/coercion/reset behaviour so it can't silently regress.
 */
class PlaybackDataRepositoryTest {

    @Before
    fun reset() {
        PlaybackDataRepository.clear()
        PlaybackDataRepository.speechRate = 1.0f
    }

    @Test
    fun setSentencesSeedsResumeIndexFromStartIndex() {
        PlaybackDataRepository.setSentences(
            listOf("One.", "Two.", "Three.", "Four."),
            language = "en",
            pageUrl = "https://example.com/a",
            startIndex = 2
        )
        assertEquals(2, PlaybackDataRepository.currentIndex)
        assertEquals("https://example.com/a", PlaybackDataRepository.pageUrl)
    }

    @Test
    fun setSentencesDefaultsResumeIndexToZero() {
        PlaybackDataRepository.setSentences(listOf("One.", "Two."))
        assertEquals(0, PlaybackDataRepository.currentIndex)
    }

    @Test
    fun startIndexAboveRangeIsClampedToLastSentence() {
        PlaybackDataRepository.setSentences(listOf("One.", "Two.", "Three."), startIndex = 99)
        assertEquals(2, PlaybackDataRepository.currentIndex)
    }

    @Test
    fun negativeStartIndexIsClampedToZero() {
        PlaybackDataRepository.setSentences(listOf("One.", "Two."), startIndex = -5)
        assertEquals(0, PlaybackDataRepository.currentIndex)
    }

    @Test
    fun serviceAdvancedIndexSurvivesUntilCleared() {
        // The service publishes its live position here; it must persist across the
        // service instance being torn down (paused MediaSessionService reclaimed
        // after a few minutes) so a resume in the same process resumes correctly.
        PlaybackDataRepository.setSentences(listOf("a", "b", "c", "d", "e"), startIndex = 0)
        PlaybackDataRepository.currentIndex = 3
        assertEquals(3, PlaybackDataRepository.currentIndex)
        // Loading a different page resets the resume point.
        PlaybackDataRepository.clear()
        assertEquals(0, PlaybackDataRepository.currentIndex)
    }

    @Test
    fun clearResetsPageScopedState() {
        PlaybackDataRepository.setSentences(
            listOf("x", "y"),
            language = "fi",
            pageUrl = "https://e.com",
            startIndex = 1
        )
        PlaybackDataRepository.clear()
        assertEquals(0, PlaybackDataRepository.currentIndex)
        assertNull(PlaybackDataRepository.pageUrl)
        assertNull(PlaybackDataRepository.language)
        assertEquals(emptyList<String>(), PlaybackDataRepository.sentences)
    }

    @Test
    fun positionMsMapsBackToTheSentenceIndex() {
        PlaybackDataRepository.setSentences(listOf("One two three.", "Four five.", "Six."))
        assertEquals(0, PlaybackDataRepository.indexAtPositionMs(0))
        val startOfSecond = PlaybackDataRepository.getSentenceStartMs(1)
        assertEquals(1, PlaybackDataRepository.indexAtPositionMs(startOfSecond + 1))
    }
}
