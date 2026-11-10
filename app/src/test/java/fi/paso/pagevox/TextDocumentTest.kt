package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextDocumentTest {
    private fun doc(text: String) = textDocumentBlocks(PageBlock(text, "pre"))
    private fun headings(text: String) = doc(text).filter { it.isHeading }.map { it.text }

    @Test fun chapterLinesBecomeHeadingsAndTheTextBetweenThemStays() {
        val text = "Chapter 1\n\nIt was dark. It rained.\n\nCHAPTER II. The Pool\n\nMore text here."
        assertEquals(listOf("h2", "pre", "h2", "pre"), doc(text).map { it.tag })
        assertEquals(listOf("Chapter 1", "CHAPTER II. The Pool"), headings(text))
        assertEquals("It was dark. It rained.", doc(text)[1].text.trim())
    }

    @Test fun headingsAreFoundInEveryLanguage() {
        val text = listOf(
            "Prologi", "Luku 3", "2. luku", "Kapitel 4: Hemkomsten", "Chapitre 5", "Teil 6 – Heimkehr",
            "Part Two", "Chapter Seven: The End", "Epilogue."
        ).joinToString("\n\n") { "$it\n\nJotain tekstiä tässä." }
        assertEquals(9, headings(text).size)
    }

    @Test fun markdownHeadingsOfBothKinds() {
        val text = "# Title\n\nIntro.\n\n## Usage ##\n\nText.\n\nInstalling\n==========\n\nBody.\n\nNotes\n-----\n\nEnd."
        assertEquals(listOf("Title", "Usage", "Installing", "Notes"), headings(text))
        assertEquals(listOf("h1", "h2", "h1", "h2"), doc(text).filter { it.isHeading }.map { it.tag })
        // The underline row is dropped with its heading, not left to be read.
        assertEquals(false, doc(text).any { it.text.contains("===") || it.text.contains("---") })
    }

    @Test fun bareNumbersCountWhenTheyRunFromOne() {
        assertEquals(listOf("1", "2.", "III"), headings("1\n\nFirst.\n\n2.\n\nSecond.\n\nIII\n\nThird."))
    }

    @Test fun pageNumbersAreNotChapters() {
        assertEquals(emptyList<String>(), headings("Text one.\n\n47\n\nText two.\n\n48\n\nText three."))
    }

    @Test fun aNumberOutOfTurnIsSkippedWithoutLosingTheRest() {
        assertEquals(listOf("1", "2"), headings("1\n\nFirst.\n\n212\n\nStill first.\n\n2\n\nSecond."))
    }

    @Test fun aContentsListIsNotAStringOfChapters() {
        val contents = "Chapter 1\n\nChapter 2\n\nChapter 3"
        assertEquals(emptyList<String>(), headings("$contents\n\nA preface in prose follows."))
        // The first chapter's own heading usually comes straight after the list.
        assertEquals(
            listOf("Chapter 1", "Chapter 2"),
            headings("$contents\n\nChapter 1\n\nText.\n\nChapter 2\n\nMore.")
        )
    }

    @Test fun twoHeadingsInARowAreBothKept() {
        assertEquals(listOf("Part One", "Chapter 1"), headings("Part One\n\nChapter 1\n\nText."))
    }

    @Test fun aLineThatOnlyLooksLikeAHeadingIsLeftInItsParagraph() {
        val text = listOf(
            "He said that\nChapter 1\nwas the best.",          // inside a paragraph
            "# a comment\nsome_code()",                         // text straight after it
            "Part of the problem was money.",                   // no number
            "Section 8 housing was scarce.",                    // a sentence
            "Chapter 3 was where it all went wrong, he said.",  // a sentence
            "x".repeat(81) + "\n\n" + "Chapter " + "y".repeat(80)  // too long for a heading
        ).joinToString("\n\n")
        assertEquals(emptyList<String>(), headings(text))
    }

    @Test fun aFileWithoutHeadingsComesBackUntouched() {
        val block = PageBlock("Just a story.\n\nWith two paragraphs.", "pre")
        assertSame(block, textDocumentBlocks(block).single())
    }

    @Test fun narrationOfATextFileHasItsChaptersAsSections() {
        val text = "Chapter 1\n\nIt was dark. It rained.\n\nChapter 2\n\nThe sun came out."
        val n = buildNarration(listOf(PageBlock(text, "pre")))
        assertEquals(listOf("Chapter 1", "Chapter 2"), n.sectionTitles)
        assertEquals(listOf(0, 3), n.sectionStarts)
        assertEquals(listOf("Chapter 1", "It was dark.", "It rained.", "Chapter 2", "The sun came out."), n.display)
    }

    @Test fun aFetchedFileIsTreatedLikeOneShownInThePage() {
        val text = "Chapter 1\n\nText."
        assertEquals(
            buildNarration(listOf(PageBlock(text, "pre"))).sectionTitles,
            buildNarration(listOf(PageBlock(text, ""))).sectionTitles
        )
    }

    @Test fun aCodeListingOnAWebPageIsNotSearchedForHeadings() {
        val n = buildNarration(listOf(PageBlock("Some prose.", "p"), PageBlock("# Setup\n\nrun()", "pre")))
        assertEquals(emptyList<String>(), n.sectionTitles)
    }
}
