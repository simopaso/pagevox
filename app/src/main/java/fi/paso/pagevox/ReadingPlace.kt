package fi.paso.pagevox

/**
 * A saved reading position. [index] alone is a sentence number, which only means
 * something for the exact sentence list it was counted in: an edited page, a site
 * redesign, a change to what the extractor skips or a flip of reader mode all
 * renumber the sentences, and the reader lands somewhere else. [anchor] is the
 * start of the sentence itself, which survives all of those. [sentenceCount] is the
 * size of the list [index] was counted in, for when the sentence has gone too.
 *
 * Entries saved before 2.26 have no anchor, and keep behaving as plain indices.
 */
internal data class SavedPlace(
    val index: Int,
    val anchor: String = "",
    val sentenceCount: Int = 0
)

/** Long enough to tell sentences apart, short enough that a position stored at
 *  every sentence boundary and a few hundred history entries stay small. */
internal const val ANCHOR_LENGTH = 60

private val ANCHOR_WHITESPACE = Regex("""\s+""")

internal fun anchorFor(sentence: String): String =
    sentence.replace(ANCHOR_WHITESPACE, " ").trim().take(ANCHOR_LENGTH)

/**
 * Where this place is in [sentences], which may not be the list it was saved from.
 * The nearest match wins, because a short sentence ("Yes.") can repeat throughout a
 * page and the saved index still says roughly where. If the sentence is gone, the
 * same fraction of the way through is the best guess there is; and without a count
 * to scale by, the index is all that's known. The result can be past the end of the
 * list: [resumeIndexFor] deals with that.
 */
internal fun SavedPlace.locateIn(sentences: List<String>): Int {
    if (anchor.isBlank()) return index
    fun matches(i: Int) = anchorFor(sentences[i]) == anchor
    if (index in sentences.indices && matches(index)) return index
    val nearest = sentences.indices.filter(::matches).minByOrNull { kotlin.math.abs(it - index) }
    if (nearest != null) return nearest
    if (sentenceCount <= 0 || sentenceCount == sentences.size) return index
    return (index.toLong() * sentences.size / sentenceCount).toInt()
}

/** A break shorter than this isn't long enough to lose the thread. */
internal const val SHORT_BREAK_MS = 5 * 60_000L
internal const val LONG_BREAK_MS = 60 * 60_000L
private const val SENTENCES_BACK_AFTER_SHORT_BREAK = 2
private const val SENTENCES_BACK_AFTER_LONG_BREAK = 3

/**
 * Where to pick up after [breakMs] away from sentence [index], as audiobook players
 * do: a couple of sentences earlier, so the listener finds the thread again before
 * the story moves on. Stays inside the current section, since the heading is already
 * a clean place to start. A negative break is the clock having been changed.
 */
internal fun rewindAfterBreak(index: Int, breakMs: Long, sectionStarts: List<Int>): Int {
    val back = when {
        breakMs >= LONG_BREAK_MS -> SENTENCES_BACK_AFTER_LONG_BREAK
        breakMs >= SHORT_BREAK_MS -> SENTENCES_BACK_AFTER_SHORT_BREAK
        else -> return index
    }
    val sectionStart = sectionStarts.lastOrNull { it <= index } ?: 0
    return (index - back).coerceAtLeast(sectionStart)
}
