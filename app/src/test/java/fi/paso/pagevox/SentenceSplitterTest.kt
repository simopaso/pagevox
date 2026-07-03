package fi.paso.pagevox

import org.junit.Assert.assertEquals
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
}
