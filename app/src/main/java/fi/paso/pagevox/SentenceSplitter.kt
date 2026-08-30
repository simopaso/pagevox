package fi.paso.pagevox

// Candidate boundaries: whitespace following ./!/?. Each candidate is then
// vetted against the abbreviation/initial/lowercase heuristics below, so
// "Dr. Smith" or "e.g. this" no longer produce an audible mid-thought pause.
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")

// Words that end with a period without ending a sentence. Compared against the
// pre-boundary token with surrounding punctuation stripped and lowercased, so
// "e.g." arrives here as "e.g" and "Dr." as "dr". English plus the common
// Finnish set (esim. = for example, jne. = etc., vrt. = compare, …).
private val ABBREVIATIONS = setOf(
    // titles
    "mr", "mrs", "ms", "dr", "prof", "rev", "gen", "sen", "rep",
    "capt", "col", "sgt", "lt", "sr", "jr", "st",
    // latin / scholarly
    "etc", "e.g", "i.e", "cf", "ca", "al", "vs",
    // common truncations
    "approx", "dept", "est", "fig", "vol", "pp", "inc", "ltd", "co", "corp",
    "a.m", "p.m", "u.s", "u.k",
    // Finnish
    "esim", "jne", "ym", "yms", "ks", "vrt", "ns", "mm", "nro"
)

/**
 * Splits [text] into sentences on ./!/? followed by whitespace, skipping
 * boundaries that are almost certainly not sentence ends:
 *  - the preceding word is a known abbreviation ("Dr.", "e.g.", "esim."),
 *  - the preceding word is a single-letter initial ("J. R. R. Tolkien"),
 *  - the next character is lowercase (sentences don't start lowercase).
 * Pieces are trimmed; blank pieces are dropped.
 */
internal fun splitIntoSentences(text: String): List<String> =
    splitIntoSentenceRanges(text).map { text.substring(it.first, it.last + 1) }

/**
 * As [splitIntoSentences], but returns the (trimmed, non-blank) character ranges
 * of the sentences instead of the strings. Callers that speak a cleaned form of
 * the text while displaying the verbatim form need the offsets to map one back
 * onto the other — see [cleanForNarration].
 */
internal fun splitIntoSentenceRanges(text: String): List<IntRange> {
    if (text.isBlank()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = 0
    for (match in SENTENCE_BOUNDARY.findAll(text)) {
        val before = text.substring(start, match.range.first)
        val next = text.getOrNull(match.range.last + 1)
        if (!isSentenceBoundary(before, next)) continue
        addTrimmed(text, start, match.range.first, ranges)
        start = match.range.last + 1
    }
    addTrimmed(text, start, text.length, ranges)
    return ranges
}

/** Append [from,toExclusive) to [out] with the outer whitespace shaved off,
 *  skipping it entirely when nothing but whitespace remains. */
private fun addTrimmed(text: String, from: Int, toExclusive: Int, out: MutableList<IntRange>) {
    var s = from
    var e = toExclusive
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    if (e > s) out.add(s until e)
}

private fun isSentenceBoundary(before: String, next: Char?): Boolean {
    if (next != null && next.isLowerCase()) return false
    val token = before.takeLastWhile { !it.isWhitespace() }
    val word = token.trim { !it.isLetterOrDigit() }.lowercase()
    if (word.length == 1 && word[0].isLetter()) return false  // an initial
    // "No." abbreviates only before a number ("No. 5"); elsewhere it is a
    // legitimate sentence end ("The answer is no.").
    if (word == "no") return next?.isDigit() != true
    return word !in ABBREVIATIONS
}
