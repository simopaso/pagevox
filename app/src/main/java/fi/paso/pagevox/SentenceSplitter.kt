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

// Text with no sentence ends in it (a code listing, a log, a table) comes out as
// one "sentence" of any length, and the engine refuses anything over
// TextToSpeech.getMaxSpeechInputLength() (4000): speak() fails, and reading stopped
// at that block for good. Well under the limit, since narration cleanup can
// lengthen a piece a little.
internal const val MAX_SENTENCE_CHARS = 2000

/** Append [from,toExclusive) to [out] with the outer whitespace shaved off,
 *  skipping it entirely when nothing but whitespace remains. Anything longer than
 *  [MAX_SENTENCE_CHARS] goes in as several pieces, cut between words. */
private fun addTrimmed(text: String, from: Int, toExclusive: Int, out: MutableList<IntRange>) {
    var s = from
    var e = toExclusive
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    while (e - s > MAX_SENTENCE_CHARS) {
        var cut = s + MAX_SENTENCE_CHARS
        while (cut > s && !text[cut].isWhitespace()) cut--
        if (cut == s) cut = s + MAX_SENTENCE_CHARS   // one unbroken run: cut it anyway
        var end = cut
        while (end > s && text[end - 1].isWhitespace()) end--
        out.add(s until end)
        s = cut
        while (s < e && text[s].isWhitespace()) s++
    }
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
