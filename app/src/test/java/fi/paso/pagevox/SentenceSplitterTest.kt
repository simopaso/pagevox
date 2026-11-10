package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun splitsPlainSentences() {
        assertEquals(
            listOf("First sentence.", "Second one!", "And a third?"),
            splitIntoSentences("First sentence. Second one! And a third?")
        )
    }

    @Test
    fun keepsTitlesTogether() {
        assertEquals(
            listOf("Dr. Smith met Mrs. Jones."),
            splitIntoSentences("Dr. Smith met Mrs. Jones.")
        )
    }

    @Test
    fun keepsLatinAbbreviations() {
        assertEquals(
            listOf("Fruit, e.g. apples, is healthy.", "Vegetables too."),
            splitIntoSentences("Fruit, e.g. apples, is healthy. Vegetables too.")
        )
    }

    @Test
    fun keepsInitials() {
        assertEquals(
            listOf("J. R. R. Tolkien wrote it."),
            splitIntoSentences("J. R. R. Tolkien wrote it.")
        )
    }

    @Test
    fun doesNotSplitBeforeLowercase() {
        assertEquals(
            listOf("It was cheap... and cheerful."),
            splitIntoSentences("It was cheap... and cheerful.")
        )
    }

    @Test
    fun noBeforeNumberIsAbbreviation() {
        assertEquals(
            listOf("See No. 5 for details."),
            splitIntoSentences("See No. 5 for details.")
        )
    }

    @Test
    fun noAsWordEndsSentence() {
        assertEquals(
            listOf("The answer is no.", "We moved on."),
            splitIntoSentences("The answer is no. We moved on.")
        )
    }

    @Test
    fun keepsFinnishAbbreviations() {
        assertEquals(
            listOf("Marjat, esim. mustikat, ovat terveellisiä.", "Syö niitä."),
            splitIntoSentences("Marjat, esim. mustikat, ovat terveellisiä. Syö niitä.")
        )
    }

    @Test
    fun handlesBlankAndSingleSentence() {
        assertEquals(emptyList<String>(), splitIntoSentences("   "))
        assertEquals(listOf("No terminator at all"), splitIntoSentences("No terminator at all"))
    }

    @Test
    fun trimsPieces() {
        assertEquals(
            listOf("One.", "Two."),
            splitIntoSentences("  One.   Two.  ")
        )
    }

    @Test
    fun rangesPointAtTheSentencesInTheSourceString() {
        // The range form is what lets a sentence found in narration-cleaned text
        // be recovered verbatim from the original — see NarrationText.
        val text = "  One.   Two.  "
        val ranges = splitIntoSentenceRanges(text)
        assertEquals(listOf(2..5, 9..12), ranges)
        assertEquals(listOf("One.", "Two."), ranges.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun rangesSkipWhitespaceOnlyPieces() {
        assertEquals(emptyList<IntRange>(), splitIntoSentenceRanges("   "))
    }

    @Test
    fun textWithNoSentenceEndsIsCutIntoSpeakablePieces() {
        val text = (1..1500).joinToString(" ") { "word$it" }
        val pieces = splitIntoSentences(text)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= MAX_SENTENCE_CHARS })
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun oneUnbrokenRunIsStillCut() {
        val pieces = splitIntoSentences("x".repeat(MAX_SENTENCE_CHARS * 2 + 10))
        assertEquals(listOf(MAX_SENTENCE_CHARS, MAX_SENTENCE_CHARS, 10), pieces.map { it.length })
    }

    @Test
    fun anOrdinarySentenceIsLeftWhole() {
        val long = "This " + "is ".repeat(300) + "long."
        assertEquals(listOf(long, "Next one."), splitIntoSentences("$long Next one."))
    }
}
