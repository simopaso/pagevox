package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narration cleanup has one invariant that matters more than any individual
 * rewrite rule: the *displayed* sentence must stay a verbatim slice of the page
 * text, because that is what the follow-along highlight looks for in the DOM and
 * what tap-to-seek matches against. Several tests below assert both halves — the
 * cleaned text that gets spoken and the untouched text that gets shown.
 */
class NarrationTextTest {

    private fun narrate(text: String, tag: String = "p", lang: String? = "en") =
        buildNarration(listOf(PageBlock(text, tag)), lang)

    @Test
    fun stripsCitationMarkersFromSpeechButNotFromDisplay() {
        val n = narrate("Kotlin[1] runs on the JVM.[2] It is concise.")
        assertEquals(listOf("Kotlin runs on the JVM.", "It is concise."), n.spoken)
        assertEquals(listOf("Kotlin[1] runs on the JVM.", "It is concise."), n.display)
    }

    @Test
    fun citationMarkerNoLongerWeldsTwoSentencesTogether() {
        // "JVM.[2] It" has no whitespace after the period, so splitting the raw
        // text would produce one run-on sentence. Splitting the cleaned text
        // finds the boundary — and the sentence count is what the scrubber and
        // the resume position are expressed in.
        assertEquals(1, splitIntoSentences("Kotlin runs on the JVM.[2] It is concise.").size)
        assertEquals(2, narrate("Kotlin runs on the JVM.[2] It is concise.").display.size)
    }

    @Test
    fun stripsEditorialMarkers() {
        assertEquals(
            listOf("The claim is disputed."),
            narrate("The claim[citation needed] is disputed.[note 12]").spoken
        )
    }

    @Test
    fun leavesBracketedLettersAlone() {
        // Single bracketed letters are ordinary content in code and maths, and
        // <pre> blocks come through this same path.
        assertEquals(listOf("The value of a[i] is read."), narrate("The value of a[i] is read.").spoken)
    }

    @Test
    fun speaksTheHostInsteadOfAWholeUrl() {
        val n = narrate("See https://en.wikipedia.org/wiki/Kotlin_(programming_language) for more.")
        assertEquals(listOf("See en.wikipedia.org for more."), n.spoken)
        assertEquals(
            listOf("See https://en.wikipedia.org/wiki/Kotlin_(programming_language) for more."),
            n.display
        )
    }

    @Test
    fun aUrlDoesNotSwallowTheFullStopThatEndsItsSentence() {
        val n = narrate("See https://x.com/y. Next one.")
        assertEquals(listOf("See x.com.", "Next one."), n.spoken)
        assertEquals(listOf("See https://x.com/y.", "Next one."), n.display)
    }

    @Test
    fun dropsWwwAndSchemelessUrls() {
        assertEquals(listOf("Visit example.com today."), narrate("Visit www.example.com/x?y=1 today.").spoken)
        assertEquals(listOf("Visit example.com today."), narrate("Visit https://www.example.com today.").spoken)
    }

    @Test
    fun collapsesDecorativePunctuationRuns() {
        assertEquals(listOf("Heading"), narrate("=== Heading ===").spoken)
        assertEquals(listOf("Above Below"), narrate("Above ===== Below").spoken)
    }

    @Test
    fun dropsALeadingBulletMarker() {
        assertEquals(listOf("First item."), narrate("• First item.", tag = "li").spoken)
    }

    @Test
    fun expandsUnambiguousEnglishAbbreviations() {
        assertEquals(listOf("Fruit, for example apples, is healthy."),
            narrate("Fruit, e.g. apples, is healthy.").spoken)
        assertEquals(listOf("That is, versus the rest."), narrate("I.e., vs. the rest.").spoken)
    }

    @Test
    fun expandsFinnishAbbreviationsWhenThePageIsFinnish() {
        assertEquals(
            listOf("Marjat, esimerkiksi mustikat, ovat terveellisiä."),
            narrate("Marjat, esim. mustikat, ovat terveellisiä.", lang = "fi").spoken
        )
        assertEquals(listOf("Omenat ja niin edelleen"), narrate("Omenat jne.", lang = "fi").spoken)
    }

    @Test
    fun leavesAbbreviationsAloneInOtherLanguages() {
        assertEquals(listOf("Obst, e.g. Äpfel."), narrate("Obst, e.g. Äpfel.", lang = "de-DE").spoken)
    }

    @Test
    fun keepsASentenceInitialCapitalWhenExpanding() {
        // Lowercasing the first word would make the *previous* period look like a
        // non-boundary to the splitter ("sentences don't start lowercase").
        assertEquals(listOf("Take fruit.", "For example apples."), narrate("Take fruit. E.g. apples.").spoken)
    }

    @Test
    fun displayTextAlwaysStaysASliceOfThePage() {
        // The invariant the follow-along highlight depends on.
        val raw = "  Kotlin[1] targets   the JVM, e.g. Android.[2]\nSee https://kotlinlang.org for docs.  "
        val normalized = raw.replace(Regex("""\s+"""), " ").trim()
        val n = narrate(raw)
        assertTrue(n.display.isNotEmpty())
        n.display.forEach { assertTrue("'$it' is not page text", normalized.contains(it)) }
    }

    @Test
    fun headingBlocksBecomeSections() {
        val n = buildNarration(
            listOf(
                PageBlock("Intro text here.", "p"),
                PageBlock("History", "h2"),
                PageBlock("It began. It ended.", "p"),
                PageBlock("Usage", "h3"),
                PageBlock("Install it.", "p")
            ),
            "en"
        )
        assertEquals(
            listOf("Intro text here.", "History", "It began.", "It ended.", "Usage", "Install it."),
            n.display
        )
        assertEquals(listOf(1, 4), n.sectionStarts)
        assertEquals(listOf("History", "Usage"), n.sectionTitles)
    }

    @Test
    fun blocksThatCleanAwayToNothingAreDropped() {
        val n = buildNarration(listOf(PageBlock("[1]", "p"), PageBlock("Real text.", "p")), "en")
        assertEquals(listOf("Real text."), n.display)
        assertEquals(emptyList<Int>(), n.sectionStarts)
    }

    @Test
    fun spokenAndDisplayStayIndexAligned() {
        val n = buildNarration(
            listOf(PageBlock("One[1]. Two.", "p"), PageBlock("Three", "h2"), PageBlock("Four.", "p")),
            "en"
        )
        assertEquals(n.display.size, n.spoken.size)
    }
}
