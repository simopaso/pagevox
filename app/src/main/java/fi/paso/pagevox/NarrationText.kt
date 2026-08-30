package fi.paso.pagevox

/**
 * Narration cleanup: what the TTS engine should *say* is not always what the
 * page *shows*. Left verbatim, a Wikipedia article is read as "…in 1965 bracket
 * three The next…", a bare URL is spelled out slash by slash, an ASCII rule
 * ("=====") becomes a row of equals signs, and "esim." is pronounced as a
 * nonsense word rather than "esimerkiksi".
 *
 * Cleaning the text is only half the problem. The follow-along highlight locates
 * the spoken sentence in the page DOM by substring match, and tap-to-seek maps
 * tapped DOM text back onto the sentence list — so a sentence that had "[3]"
 * removed from its *middle* is no longer a DOM substring, and a naive
 * clean-then-split would silently kill both features. That is why the cleanup
 * carries a character map back to the source: every cleaned character knows
 * which span of the original it came from, so a sentence found in the cleaned
 * text can be recovered verbatim from the original. The sentence list then keeps
 * two parallel forms — verbatim for locating and matching, cleaned for speaking.
 *
 * All of this is pure string work, which is why it lives here rather than in the
 * ViewModel: see NarrationTextTest.
 */

/**
 * Cleaned text plus, per cleaned character, the half-open span of the source
 * string it came from. Characters that survived unchanged map to themselves;
 * characters introduced by a replacement ("for example" for "e.g.") all map to
 * the whole span of the text they replaced, so a sentence boundary landing
 * inside a replacement still resolves to a sensible source range.
 */
internal class CleanedText(
    val text: String,
    private val sourceStarts: IntArray,
    private val sourceEnds: IntArray
) {
    internal fun startAt(index: Int): Int = sourceStarts[index]
    internal fun endAt(index: Int): Int = sourceEnds[index]

    /** Source offset the cleaned character at [index] begins at. */
    fun sourceStart(index: Int): Int =
        if (sourceStarts.isEmpty()) 0 else sourceStarts[index.coerceIn(0, sourceStarts.lastIndex)]

    /** Source offset (exclusive) the cleaned range ending at [endExclusive] ends at. */
    fun sourceEnd(endExclusive: Int): Int =
        if (sourceEnds.isEmpty()) 0 else sourceEnds[(endExclusive - 1).coerceIn(0, sourceEnds.lastIndex)]
}

private class Rule(val pattern: Regex, val replace: (MatchResult) -> String)

/**
 * Rewrite [raw] into the form the TTS engine should speak. [language] is the
 * page's declared BCP-47 tag; it only selects which abbreviation expansions
 * apply, so an unknown language simply gets fewer of them.
 */
internal fun cleanForNarration(raw: String, language: String? = null): CleanedText {
    var current = identity(raw)
    for (rule in BASE_RULES) current = applyRule(current, rule)
    for (rule in expansionRules(language)) current = applyRule(current, rule)
    current = applyRule(current, PUNCTUATION_TIDY)
    current = applyRule(current, WHITESPACE_COLLAPSE)
    return current
}

/**
 * The sentence set for a page: [display] is verbatim page text (what the UI
 * shows and what the highlight/tap matching locates in the DOM), [spoken] is the
 * cleaned text at the same indices, and [sectionStarts] holds the [display]
 * indices where an h1–h6 block begins, with its heading text in [sectionTitles].
 */
internal class PageNarration(
    val display: List<String>,
    val spoken: List<String>,
    val sectionStarts: List<Int>,
    val sectionTitles: List<String>
)

private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * Turn extracted page [blocks] into the sentence set the app reads. Each block is
 * cleaned for narration, split into sentences on the *cleaned* text — so a
 * citation marker wedged between "1965." and "The" no longer welds two sentences
 * together — and each resulting sentence is mapped back to the verbatim span it
 * came from.
 */
internal fun buildNarration(blocks: List<PageBlock>, language: String? = null): PageNarration {
    val display = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    val sectionStarts = mutableListOf<Int>()
    val sectionTitles = mutableListOf<String>()

    for (block in blocks) {
        // innerText keeps the newlines the browser inserts for <br> and inner
        // block boundaries. Collapse all whitespace runs to a single space first,
        // otherwise those embedded newlines survive inside a sentence and the TTS
        // engine pauses mid-sentence on them.
        val verbatim = block.text.replace(WHITESPACE_RUN, " ").trim()
        if (verbatim.isEmpty()) continue

        val cleaned = cleanForNarration(verbatim, language)
        val ranges = splitIntoSentenceRanges(cleaned.text)
        if (ranges.isEmpty()) continue

        // Recorded before the sentences are appended, so it points at the first
        // sentence of the heading itself.
        if (block.isHeading) {
            sectionStarts.add(display.size)
            sectionTitles.add(verbatim)
        }

        for (range in ranges) {
            val said = cleaned.text.substring(range.first, range.last + 1)
            val from = cleaned.sourceStart(range.first).coerceIn(0, verbatim.length)
            val to = cleaned.sourceEnd(range.last + 1).coerceIn(from, verbatim.length)
            val shown = verbatim.substring(from, to).trim()
            display.add(shown.ifBlank { said })
            spoken.add(said)
        }
    }
    return PageNarration(display, spoken, sectionStarts, sectionTitles)
}

private fun identity(raw: String) =
    CleanedText(raw, IntArray(raw.length) { it }, IntArray(raw.length) { it + 1 })

private fun applyRule(src: CleanedText, rule: Rule): CleanedText {
    val matches = rule.pattern.findAll(src.text).toList()
    if (matches.isEmpty()) return src   // by far the common case — don't rebuild

    val out = StringBuilder(src.text.length)
    val starts = ArrayList<Int>(src.text.length)
    val ends = ArrayList<Int>(src.text.length)
    var cursor = 0

    fun copyVerbatim(from: Int, toExclusive: Int) {
        for (i in from until toExclusive) {
            out.append(src.text[i])
            starts.add(src.startAt(i))
            ends.add(src.endAt(i))
        }
    }

    for (match in matches) {
        val from = match.range.first
        val toExclusive = match.range.last + 1
        // A zero-length match can't be replaced meaningfully, and skipping it
        // keeps the cursor monotonic.
        if (toExclusive <= from || from < cursor) continue
        copyVerbatim(cursor, from)
        val replacement = rule.replace(match)
        val spanStart = src.startAt(from)
        val spanEnd = src.endAt(toExclusive - 1)
        for (c in replacement) {
            out.append(c)
            starts.add(spanStart)
            ends.add(spanEnd)
        }
        cursor = toExclusive
    }
    copyVerbatim(cursor, src.text.length)

    return CleanedText(out.toString(), starts.toIntArray(), ends.toIntArray())
}

private val BASE_RULES = listOf(
    // Reference and editorial markers: "[3]", "[12a]", "[citation needed]",
    // "[note 4]", "[sic]". Deliberately does NOT strip a bare "[x]" — a single
    // bracketed letter is ordinary content in code and maths, and this text also
    // comes from <pre> blocks.
    Rule(
        Regex(
            """\[\s*(?:\d{1,3}[a-z]?|citation needed|note\s+\d{1,3}|sic|\.\.\.|…)\s*]""",
            RegexOption.IGNORE_CASE
        )
    ) { "" },
    // A bare URL read aloud is a minute of slashes and letters. Say the host and
    // drop the path; "www." adds nothing when spoken. The path is matched as
    // "anything ending in a non-sentence-punctuation character" rather than a
    // plain \S*, which would swallow the period that ends the sentence the URL
    // sits in — welding it onto the next one. A trailing bracket is left inside
    // the match on purpose: Wikipedia URLs end in ")" often enough, and an
    // unbalanced bracket is silent while a missing full stop is not.
    Rule(Regex("""\bhttps?://([^\s/?#]+)(?:\S*[^\s.,;:!?])?""", RegexOption.IGNORE_CASE)) { m ->
        m.groupValues[1].removePrefix("www.")
    },
    Rule(Regex("""\bwww\.([^\s/?#]+)(?:\S*[^\s.,;:!?])?""", RegexOption.IGNORE_CASE)) { m ->
        m.groupValues[1]
    },
    // Horizontal rules, markdown fences and table borders: "=====", "-----",
    // "|||", "###". Three or more of the same symbol is decoration, never a word.
    Rule(Regex("""([-=_*~#>|+])\1{2,}""")) { " " },
    // A leading bullet or quote marker is layout, not something to pronounce.
    Rule(Regex("""^\s*[>•·▪◦*\-–—]+\s+""")) { "" }
)

// Removing a marker often strands a space before punctuation ("Kotlin [1], a…"
// → "Kotlin , a…"), which most engines read as an extra pause.
private val PUNCTUATION_TIDY = Rule(Regex("""\s+([,.;:!?)\]])""")) { m -> m.groupValues[1] }

private val WHITESPACE_COLLAPSE = Rule(Regex("""\s{2,}""")) { " " }

/**
 * Abbreviations worth expanding: ones an engine reliably mangles and that have
 * exactly one reading. Deliberately conservative — "St." (Saint/Street) and
 * Finnish "mm." (also millimetres) are ambiguous and stay as they are. Expanding
 * also removes the trailing period, which incidentally makes these easier for
 * [splitIntoSentenceRanges] rather than harder.
 */
private val EXPANSIONS_EN = expansions(
    "e.g." to "for example",
    "i.e." to "that is",
    "etc." to "et cetera",
    "vs." to "versus",
    "approx." to "approximately"
)

private val EXPANSIONS_FI = expansions(
    "esim." to "esimerkiksi",
    "jne." to "ja niin edelleen",
    "yms." to "ynnä muuta sellaista",
    "vrt." to "vertaa"
)

private fun expansions(vararg pairs: Pair<String, String>): List<Rule> = pairs.map { (abbr, full) ->
    // (?!\w) keeps "etc." from matching inside a longer token.
    Rule(Regex("""\b${Regex.escape(abbr)}(?!\w)""", RegexOption.IGNORE_CASE)) { m ->
        matchCase(m.value, full)
    }
}

/** Keep a sentence-initial capital, so the expansion doesn't make the next
 *  sentence start lowercase (which the splitter reads as "not a boundary"). */
private fun matchCase(original: String, replacement: String): String =
    if (original.firstOrNull()?.isUpperCase() == true) {
        replacement.replaceFirstChar { it.uppercase() }
    } else {
        replacement
    }

private fun expansionRules(language: String?): List<Rule> =
    when (language?.substringBefore('-')?.lowercase()?.ifBlank { null }) {
        "fi" -> EXPANSIONS_FI
        // A page that declares no language is overwhelmingly English in
        // practice, and these expansions are English text either way.
        "en", null -> EXPANSIONS_EN
        else -> emptyList()
    }
