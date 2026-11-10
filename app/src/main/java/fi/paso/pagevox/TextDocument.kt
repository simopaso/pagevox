package fi.paso.pagevox

// Chapter headings in plain text.
//
// A text file reaches the reader as one block: Chromium shows it as a single
// <pre>, and the service fetches the next file as a string. With no headings there
// were no sections, so a book in text files had no marks on the scrubber, no
// chapter skip, no contents and no chapter name on the lock screen, which web
// pages all get from their <h2>s. This finds the lines that are headings and
// splits the block around them, and everything downstream works as it does for a
// web page.
//
// A text file has no markup to go by, so the rules are strict rather than clever.
// A heading stands on a line of its own, with a blank line either side, and is one
// of: a Markdown heading, a chapter word with its number ("Chapter 3", "LUKU IV.",
// "Part Two: The Return"), a named part ("Prologue"), or a bare chapter number.
// Missing a heading costs nothing that wasn't missing before; inventing one puts a
// pause and a section break in the middle of a sentence.

private const val MAX_HEADING_CHARS = 80

// This many heading lines in a row, with nothing between them, is a table of
// contents rather than that many empty chapters.
private const val CONTENTS_RUN = 3

private const val CHAPTER_WORDS =
    "chapter|part|book|section|luku|osa|kirja|kapitel|del|bok|teil|buch|abschnitt|chapitre|partie|livre"
private const val SEPARATOR = """[.:–—-]"""

private val MARKDOWN_HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
private val UNDERLINE = Regex("""^(?:={3,}|-{3,})$""")

// "Chapter 3", "CHAPTER IV.", "Luku 12: Paluu", "Chapter 3 The Return"
private val NUMBERED_HEADING = Regex(
    """^(?:$CHAPTER_WORDS)\s+(?:\d{1,4}|[ivxlcdm]{1,8})(?![\p{L}\d])\s*$SEPARATOR?\s*(.*)$""",
    RegexOption.IGNORE_CASE
)

// "Chapter One", "Part Two: The Return". A title needs its separator here, or
// "Part of the story" would be one.
private val WORDED_HEADING = Regex(
    """^(?:$CHAPTER_WORDS)\s+[\p{L}-]{3,12}(?:\s*$SEPARATOR\s*(.*))?$""",
    RegexOption.IGNORE_CASE
)

// "3. luku", "2 Kapitel: Heimkehr"
private val NUMBER_FIRST_HEADING = Regex(
    """^\d{1,4}\.?\s+(?:luku|osa|kapitel|teil)(?:\s*$SEPARATOR\s*(.*))?$""",
    RegexOption.IGNORE_CASE
)

private val NAMED_PART = Regex(
    "^(?:prologue|epilogue|introduction|preface|foreword|afterword|interlude|" +
        "prologi|epilogi|johdanto|esipuhe|jälkisanat|" +
        "prolog|epilog|förord|efterord|inledning|vorwort|nachwort|einleitung|" +
        "épilogue|préface|avant-propos|postface)[.:]?$",
    RegexOption.IGNORE_CASE
)

// Roman numerals in capitals only: "I" is a chapter, "i" is a typo.
private val BARE_NUMBER = Regex("""^(\d{1,3}|[IVXLCDM]{1,7})\.?$""")

private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

private class TextHeading(
    val firstLine: Int,
    val lastLine: Int,
    val title: String,
    val level: Int,
    /** Set for a bare number, which only counts in sequence. */
    val number: Int? = null
)

/**
 * [block], the whole text of a plain-text document, as the blocks a web page
 * would have given: its headings as h1–h6 and the text between them. Returns the
 * block itself, untouched, when it has no headings, so a file without any is read
 * exactly as it was before.
 */
internal fun textDocumentBlocks(block: PageBlock): List<PageBlock> {
    val lines = block.text.lines()
    val found = mutableListOf<TextHeading>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        val startsParagraph = i == 0 || lines[i - 1].isBlank()
        if (line.isEmpty() || line.length > MAX_HEADING_CHARS || !startsParagraph) {
            i++
            continue
        }
        val next = lines.getOrNull(i + 1)?.trim()
        // Markdown's other heading form: a title with a row of = or - under it.
        if (next != null && UNDERLINE.matches(next) && lines.getOrNull(i + 2).isNullOrBlank() &&
            !endsLikeASentence(line)
        ) {
            found += TextHeading(i, i + 1, line, if (next[0] == '=') 1 else 2)
            i += 2
            continue
        }
        if (next.isNullOrEmpty()) headingIn(line, i)?.let { found += it }
        i++
    }

    val headings = inSequence(withoutContentsLists(found, lines))
    if (headings.isEmpty()) return listOf(block)

    val blocks = mutableListOf<PageBlock>()
    var from = 0
    fun addText(until: Int) {
        val text = lines.subList(from, until).joinToString("\n")
        if (text.isNotBlank()) blocks += PageBlock(text, block.tag)
    }
    for (heading in headings) {
        addText(heading.firstLine)
        blocks += PageBlock(heading.title, "h${heading.level}")
        from = heading.lastLine + 1
    }
    addText(lines.size)
    return blocks
}

private fun headingIn(line: String, at: Int): TextHeading? {
    MARKDOWN_HEADING.matchEntire(line)?.let { m ->
        val title = m.groupValues[2]
        return if (title.isBlank()) null else TextHeading(at, at, title, m.groupValues[1].length)
    }
    for (pattern in listOf(NUMBERED_HEADING, WORDED_HEADING, NUMBER_FIRST_HEADING)) {
        val title = pattern.matchEntire(line)?.groupValues?.get(1) ?: continue
        // "Section 8 housing was scarce." is a sentence that happens to start
        // like a heading. A title in capitals may end how it likes.
        if (title.isNotBlank() && endsLikeASentence(title)) return null
        return TextHeading(at, at, line, 2)
    }
    if (NAMED_PART.matches(line)) return TextHeading(at, at, line, 2)
    BARE_NUMBER.matchEntire(line)?.let { m ->
        val number = numberValue(m.groupValues[1]) ?: return null
        return TextHeading(at, at, line, 2, number)
    }
    return null
}

private fun endsLikeASentence(text: String): Boolean =
    text.last() in ".!?,;" && text.any { it.isLowerCase() }

private fun numberValue(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    var total = 0
    for (i in text.indices) {
        val value = ROMAN[text[i]] ?: return null
        val following = text.getOrNull(i + 1)?.let { ROMAN[it] } ?: 0
        total += if (value < following) -value else value
    }
    return total
}

/**
 * [found] without the runs that are a table of contents. The last line of such a
 * run is kept when it repeats an earlier one: a contents list is usually followed
 * straight away by the first chapter's own heading.
 */
private fun withoutContentsLists(found: List<TextHeading>, lines: List<String>): List<TextHeading> {
    val kept = mutableListOf<TextHeading>()
    var run = mutableListOf<TextHeading>()
    fun endRun() {
        if (run.size < CONTENTS_RUN) {
            kept += run
        } else {
            val last = run.last()
            if (run.dropLast(1).any { it.title.equals(last.title, ignoreCase = true) }) kept += last
        }
        run = mutableListOf()
    }
    for (heading in found) {
        val previous = run.lastOrNull()
        if (previous != null &&
            (previous.lastLine + 1 until heading.firstLine).any { lines[it].isNotBlank() }
        ) endRun()
        run += heading
    }
    endRun()
    return kept
}

/**
 * A number alone on a line is a chapter only if it is the next one, counting from
 * 1. Scanned books have their page numbers standing alone too, and "47" in the
 * middle of a chapter is not a heading.
 */
private fun inSequence(headings: List<TextHeading>): List<TextHeading> {
    var expected = 1
    return headings.filter { heading ->
        when (heading.number) {
            null -> true
            expected -> { expected++; true }
            else -> false
        }
    }
}
