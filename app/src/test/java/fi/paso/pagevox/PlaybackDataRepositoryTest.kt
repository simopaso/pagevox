package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ── Narration text ────────────────────────────────────────────────────────

    @Test
    fun spokenTextIsUsedForSpeechWhileVerbatimTextIsPublished() {
        PlaybackDataRepository.setSentences(
            listOf("Kotlin[1] is concise."),
            spokenSentences = listOf("Kotlin is concise.")
        )
        assertEquals("Kotlin[1] is concise.", PlaybackDataRepository.getSentence(0))
        assertEquals("Kotlin is concise.", PlaybackDataRepository.getSpokenSentence(0))
    }

    @Test
    fun aMisalignedSpokenListIsIgnored() {
        // Speaking sentence n's text at index n+1 would be worse than not
        // cleaning at all, so a list that doesn't line up is dropped wholesale.
        PlaybackDataRepository.setSentences(
            listOf("One.", "Two."),
            spokenSentences = listOf("One.")
        )
        assertEquals("Two.", PlaybackDataRepository.getSpokenSentence(1))
    }

    @Test
    fun durationsAreEstimatedFromWhatIsActuallySpoken() {
        PlaybackDataRepository.setSentences(listOf("one two three four five"))
        val verbatimTotal = PlaybackDataRepository.totalDurationMs
        PlaybackDataRepository.setSentences(
            listOf("one two three four five"),
            spokenSentences = listOf("one")
        )
        assertTrue(PlaybackDataRepository.totalDurationMs < verbatimTotal)
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    @Test
    fun sectionStartsDriveNextAndPreviousNavigation() {
        PlaybackDataRepository.setSentences(
            List(10) { "Sentence $it." },
            sectionStarts = listOf(0, 3, 7),
            sectionTitles = listOf("Intro", "History", "Usage")
        )
        assertTrue(PlaybackDataRepository.isSectionStart(3))
        assertFalse(PlaybackDataRepository.isSectionStart(4))
        assertEquals(3, PlaybackDataRepository.nextSectionStart(0))
        assertEquals(7, PlaybackDataRepository.nextSectionStart(3))
        // Nothing after the last section: the caller must not jump anywhere.
        assertNull(PlaybackDataRepository.nextSectionStart(8))
        assertEquals(3, PlaybackDataRepository.previousSectionStart(5))
        assertNull(PlaybackDataRepository.previousSectionStart(0))
    }

    @Test
    fun sectionTitleCoversEverySentenceUntilTheNextHeading() {
        PlaybackDataRepository.setSentences(
            List(10) { "Sentence $it." },
            sectionStarts = listOf(3, 7),
            sectionTitles = listOf("History", "Usage")
        )
        // Text ahead of the first heading belongs to no section.
        assertNull(PlaybackDataRepository.sectionTitleAt(0))
        assertEquals("History", PlaybackDataRepository.sectionTitleAt(3))
        assertEquals("History", PlaybackDataRepository.sectionTitleAt(6))
        assertEquals("Usage", PlaybackDataRepository.sectionTitleAt(9))
    }

    @Test
    fun sectionStartsOutsideTheSentenceRangeAreDropped() {
        PlaybackDataRepository.setSentences(
            listOf("a", "b", "c"),
            sectionStarts = listOf(0, 9),
            sectionTitles = listOf("Kept", "Bogus")
        )
        assertEquals(listOf(0), PlaybackDataRepository.sectionStarts)
    }

    @Test
    fun clearResetsSections() {
        PlaybackDataRepository.setSentences(
            listOf("a", "b"),
            sectionStarts = listOf(0),
            sectionTitles = listOf("Intro")
        )
        PlaybackDataRepository.clear()
        assertEquals(emptyList<Int>(), PlaybackDataRepository.sectionStarts)
        assertNull(PlaybackDataRepository.sectionTitleAt(0))
    }

    // ── Time remaining ────────────────────────────────────────────────────────

    @Test
    fun remainingTimeShrinksTowardsTheEndOfThePage() {
        PlaybackDataRepository.setSentences(listOf("one", "two", "three"))
        val fromStart = PlaybackDataRepository.baseRemainingMsFrom(0)
        val fromMiddle = PlaybackDataRepository.baseRemainingMsFrom(1)
        assertTrue(fromStart > fromMiddle)
        assertEquals(0L, PlaybackDataRepository.baseRemainingMsFrom(3))
        assertEquals(0L, PlaybackDataRepository.baseRemainingMsFrom(99))
    }

    @Test
    fun remainingTimeIsMeasuredAtOneTimesSpeed() {
        // It is persisted with the history entry and displayed later, possibly at
        // a different speed, so the stored value must not bake the rate in.
        PlaybackDataRepository.setSentences(listOf("one", "two", "three"))
        val atNormalSpeed = PlaybackDataRepository.baseRemainingMsFrom(0)
        PlaybackDataRepository.speechRate = 2.0f
        assertEquals(atNormalSpeed, PlaybackDataRepository.baseRemainingMsFrom(0))
    }
}
