package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPlaceTest {
    private val page = (0 until 10).map { "Sentence number $it of the page." }

    private fun placeAt(i: Int, sentences: List<String> = page) =
        SavedPlace(i, anchorFor(sentences[i]), sentences.size)

    @Test fun anUnchangedPageResumesAtTheSameIndex() {
        assertEquals(4, placeAt(4).locateIn(page))
    }

    @Test fun sentencesAddedBeforeThePlaceDoNotMoveIt() {
        val longer = listOf("New intro.", "Another new line.", "And a third.") + page
        assertEquals(7, placeAt(4).locateIn(longer))
    }

    @Test fun sentencesRemovedBeforeThePlaceDoNotMoveIt() {
        assertEquals(1, placeAt(4).locateIn(page.drop(3)))
    }

    @Test fun aRepeatedSentenceResolvesToTheNearestCopy() {
        val repeating = listOf("Yes.", "a", "b", "c", "d", "Yes.", "e", "f", "g", "Yes.")
        // Saved at the copy in the middle; one sentence is then lost before it.
        val place = SavedPlace(5, "Yes.", repeating.size)
        assertEquals(4, place.locateIn(repeating.drop(1)))
    }

    @Test fun whitespaceDifferencesDoNotBreakAnAnchor() {
        val reflowed = page.toMutableList().also { it[4] = "Sentence  number\n4 of the page." }
        assertEquals(4, placeAt(4).locateIn(reflowed))
    }

    @Test fun aLongSentenceIsMatchedOnItsStart() {
        val long = "x".repeat(ANCHOR_LENGTH * 3)
        val place = SavedPlace(0, anchorFor(long), 1)
        assertEquals(ANCHOR_LENGTH, place.anchor.length)
        assertEquals(1, place.locateIn(listOf("first", long)))
    }

    @Test fun aSentenceThatIsGoneScalesToTheSameFractionOfThePage() {
        val place = SavedPlace(50, "This sentence no longer exists.", 100)
        val rewritten = (0 until 200).map { "Other text $it." }
        assertEquals(100, place.locateIn(rewritten))
    }

    @Test fun aSentenceThatIsGoneFromASameSizedPageKeepsTheIndex() {
        val place = SavedPlace(5, "This sentence no longer exists.", page.size)
        assertEquals(5, place.locateIn(page))
    }

    @Test fun anEntryFromBeforeAnchorsKeepsItsIndex() {
        assertEquals(7, SavedPlace(7).locateIn(page.drop(3)))
    }

    @Test fun aPlacePastTheEndWithNoMatchStaysPastTheEnd() {
        // resumeIndexFor turns this into "start over"; it must not be clamped here
        // into looking like a position on the last line.
        assertEquals(50, SavedPlace(50).locateIn(page))
    }

    @Test fun aShortBreakDoesNotRewind() {
        assertEquals(20, rewindAfterBreak(20, SHORT_BREAK_MS - 1, emptyList()))
    }

    @Test fun aBreakOfSomeMinutesGoesBackTwoSentences() {
        assertEquals(18, rewindAfterBreak(20, SHORT_BREAK_MS, emptyList()))
    }

    @Test fun aLongBreakGoesBackThreeSentences() {
        assertEquals(17, rewindAfterBreak(20, LONG_BREAK_MS, emptyList()))
    }

    @Test fun rewindingStopsAtTheStartOfThePage() {
        assertEquals(0, rewindAfterBreak(1, LONG_BREAK_MS, emptyList()))
    }

    @Test fun rewindingStaysInsideTheCurrentSection() {
        assertEquals(19, rewindAfterBreak(20, LONG_BREAK_MS, listOf(5, 19, 30)))
    }

    @Test fun startingOnAHeadingStaysOnIt() {
        assertEquals(19, rewindAfterBreak(19, LONG_BREAK_MS, listOf(5, 19)))
    }

    @Test fun aClockSetBackwardsDoesNotRewind() {
        assertEquals(20, rewindAfterBreak(20, -LONG_BREAK_MS, emptyList()))
    }
}
