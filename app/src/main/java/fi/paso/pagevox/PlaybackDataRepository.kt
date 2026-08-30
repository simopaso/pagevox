package fi.paso.pagevox

/**
 * Singleton holding the sentence list plus an estimated reading duration per
 * sentence. The duration estimates drive the silent ExoPlayer track so the
 * media-notification progress bar reflects how far through the page the TTS is.
 */
object PlaybackDataRepository {
    private val _sentences = mutableListOf<String>()
    // What the engine actually says for each sentence: the same text with
    // citation markers, bare URLs and unpronounceable abbreviations cleaned up
    // (see cleanForNarration). Kept parallel to, and never a replacement for,
    // [_sentences] — the verbatim text is what locates a sentence in the page
    // DOM for the follow-along highlight and tap-to-seek.
    private val _spokenSentences = mutableListOf<String>()
    // Per-sentence estimates at 1× speed; the published timeline below is these
    // divided by the current speech rate, rebuilt whenever either changes.
    private val _baseDurationsMs = mutableListOf<Long>()
    private val _sentenceStartsMs = mutableListOf<Long>()
    private var _totalDurationMs = 0L
    // Sentence indices that open a section (an h1–h6 block on the page),
    // ascending, with the heading text at the same position in _sectionTitles.
    private val _sectionStarts = mutableListOf<Int>()
    private val _sectionTitles = mutableListOf<String>()

    val sentences: List<String> get() = _sentences
    val totalDurationMs: Long get() = _totalDurationMs

    /** Sentence indices where a page section (heading) begins, ascending. */
    val sectionStarts: List<Int> get() = _sectionStarts

    /**
     * BCP-47 language tag of the loaded page (e.g. "en", "fr-CA"), or null when
     * the page didn't declare one. The playback service uses this to pick a
     * matching TTS voice while still honoring the user's default voice.
     */
    @Volatile
    var language: String? = null
        private set

    /**
     * URL of the page the current [sentences] were extracted from. The service
     * persists the reading position against this URL directly (see
     * PlaybackService.persistPosition), so a pause or sentence boundary reaches
     * disk even when no Activity/MediaController is connected — e.g. the user
     * paused from the lock-screen notification while the app was backgrounded.
     */
    @Volatile
    var pageUrl: String? = null
        private set

    /**
     * The sentence index currently being read, published by the playback service
     * as it speaks. This is the authoritative resume point *within the process*:
     * unlike the service's own currentSentenceIndex it survives the service being
     * destroyed (a paused MediaSessionService is torn down after a few minutes,
     * resetting that instance field to 0), and unlike the ViewModel's initialIndex
     * it stays correct while the app is backgrounded (the ViewModel only advances
     * that when a MediaController is connected, i.e. in the foreground). The
     * ViewModel reads this when deciding where a plain Play/resume starts. On a
     * genuinely fresh process this object is recreated at 0 and the position is
     * seeded from disk instead — see [setSentences]'s startIndex and
     * MainViewModel.init.
     */
    @Volatile
    var currentIndex: Int = 0

    /**
     * TTS speech-rate multiplier (1.0 = normal). Set from the UI layer and read
     * by the playback service before each utterance, so rate changes take effect
     * without re-plumbing through the media session. Setting it also rescales
     * the estimated timeline so the notification progress bar stays honest at
     * non-1× speeds.
     */
    @Volatile
    var speechRate: Float = 1.0f
        set(value) {
            field = value
            rebuildTimeline()
        }

    /**
     * Name of the TTS voice the user picked in-app, or null/blank to follow the
     * system default (and the content-aware language logic). Read by the playback
     * service when configuring the engine; a user choice overrides auto-switching.
     * Deliberately not cleared by [clear] — it's a persistent user preference, not
     * page-scoped state.
     */
    @Volatile
    var selectedVoiceName: String? = null

    fun setSentences(
        newSentences: List<String>,
        language: String? = null,
        pageUrl: String? = null,
        startIndex: Int = 0,
        spokenSentences: List<String> = emptyList(),
        sectionStarts: List<Int> = emptyList(),
        sectionTitles: List<String> = emptyList()
    ) {
        _sentences.clear()
        _sentences.addAll(newSentences)
        _spokenSentences.clear()
        // Only trust a spoken list that lines up one-to-one; anything else would
        // silently speak the wrong sentence, so fall back to verbatim text.
        if (spokenSentences.size == newSentences.size) _spokenSentences.addAll(spokenSentences)
        _sectionStarts.clear()
        _sectionTitles.clear()
        // Sorted, because everything below reads this as an ordered list —
        // isSectionStart binary-searches it, and next/previous take the first
        // and last entry past a given index.
        sectionStarts.mapIndexedNotNull { i, start ->
            if (start in newSentences.indices) start to sectionTitles.getOrElse(i) { "" } else null
        }.sortedBy { it.first }.forEach { (start, title) ->
            _sectionStarts.add(start)
            _sectionTitles.add(title)
        }
        this.language = language
        this.pageUrl = pageUrl
        // Seed the resume point for this freshly-loaded page. On a cold start the
        // caller passes the position restored from disk; on a new page it's 0.
        currentIndex = startIndex.coerceIn(0, (newSentences.size - 1).coerceAtLeast(0))
        _baseDurationsMs.clear()
        // Estimate from what is spoken, not what is shown — a paragraph full of
        // stripped citation markers takes measurably less time to read aloud.
        _sentences.indices.mapTo(_baseDurationsMs) { estimateSentenceDurationMs(spokenOrVerbatim(it)) }
        rebuildTimeline()
    }

    fun clear() {
        _sentences.clear()
        _spokenSentences.clear()
        _baseDurationsMs.clear()
        _sentenceStartsMs.clear()
        _sectionStarts.clear()
        _sectionTitles.clear()
        _totalDurationMs = 0L
        language = null
        pageUrl = null
        currentIndex = 0
    }

    private fun rebuildTimeline() {
        _sentenceStartsMs.clear()
        val rate = speechRate.coerceAtLeast(0.1f)
        var cumulative = 0L
        for (base in _baseDurationsMs) {
            _sentenceStartsMs.add(cumulative)
            cumulative += (base / rate).toLong()
        }
        _totalDurationMs = cumulative
    }

    fun getSentence(index: Int): String? =
        if (index in _sentences.indices) _sentences[index] else null

    /** The text the TTS engine should speak for [index] — the cleaned form when
     *  one was supplied, otherwise the verbatim sentence. */
    fun getSpokenSentence(index: Int): String? =
        if (index in _sentences.indices) spokenOrVerbatim(index) else null

    private fun spokenOrVerbatim(index: Int): String =
        _spokenSentences.getOrNull(index)?.takeIf { it.isNotBlank() } ?: _sentences[index]

    fun getSentenceStartMs(index: Int): Long =
        if (index in _sentenceStartsMs.indices) _sentenceStartsMs[index] else 0L

    /** True when [index] is the first sentence of a page section (a heading). */
    fun isSectionStart(index: Int): Boolean = _sectionStarts.binarySearch(index) >= 0

    /** First section starting after [from], or null at the last section. */
    fun nextSectionStart(from: Int): Int? = _sectionStarts.firstOrNull { it > from }

    /** Last section starting before [from], or null when already in the first. */
    fun previousSectionStart(from: Int): Int? = _sectionStarts.lastOrNull { it < from }

    /** Heading text of the section [index] falls in, or null before the first
     *  heading (page intros routinely start with body text). */
    fun sectionTitleAt(index: Int): String? {
        val at = _sectionStarts.indexOfLast { it <= index }
        return if (at >= 0) _sectionTitles.getOrNull(at)?.takeIf { it.isNotBlank() } else null
    }

    /** Estimated 1× reading time from [index] to the end of the page. Persisted
     *  with the reading position so the library can show "11 min left" for a
     *  page that isn't currently loaded; divide by the speech rate to display. */
    fun baseRemainingMsFrom(index: Int): Long {
        if (index >= _baseDurationsMs.size) return 0L
        var sum = 0L
        for (i in index.coerceAtLeast(0) until _baseDurationsMs.size) sum += _baseDurationsMs[i]
        return sum
    }

    /** Sentence index whose predicted span contains [positionMs]. */
    fun indexAtPositionMs(positionMs: Long): Int {
        if (_sentenceStartsMs.isEmpty()) return 0
        if (positionMs <= 0L) return 0
        val raw = _sentenceStartsMs.binarySearch(positionMs)
        return if (raw >= 0) raw
        else (-raw - 2).coerceAtLeast(0).coerceAtMost(_sentenceStartsMs.lastIndex)
    }

    /** ~150 wpm avg TTS pace (~400ms/word) plus a small inter-sentence pause. */
    private fun estimateSentenceDurationMs(sentence: String): Long {
        val words = sentence.split(Regex("\\s+")).count { it.isNotBlank() }
        return maxOf(500L, words * 400L + 250L)
    }
}
