package fi.paso.pagevox

/**
 * Singleton holding the sentence list plus an estimated reading duration per
 * sentence. The duration estimates drive the silent ExoPlayer track so the
 * media-notification progress bar reflects how far through the page the TTS is.
 */
object PlaybackDataRepository {
    private val _sentences = mutableListOf<String>()
    // Per-sentence estimates at 1× speed; the published timeline below is these
    // divided by the current speech rate, rebuilt whenever either changes.
    private val _baseDurationsMs = mutableListOf<Long>()
    private val _sentenceStartsMs = mutableListOf<Long>()
    private var _totalDurationMs = 0L

    val sentences: List<String> get() = _sentences
    val totalDurationMs: Long get() = _totalDurationMs

    /**
     * BCP-47 language tag of the loaded page (e.g. "en", "fr-CA"), or null when
     * the page didn't declare one. The playback service uses this to pick a
     * matching TTS voice while still honoring the user's default voice.
     */
    @Volatile
    var language: String? = null
        private set

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

    fun setSentences(newSentences: List<String>, language: String? = null) {
        _sentences.clear()
        _sentences.addAll(newSentences)
        this.language = language
        _baseDurationsMs.clear()
        _sentences.mapTo(_baseDurationsMs) { estimateSentenceDurationMs(it) }
        rebuildTimeline()
    }

    fun clear() {
        _sentences.clear()
        _baseDurationsMs.clear()
        _sentenceStartsMs.clear()
        _totalDurationMs = 0L
        language = null
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

    fun getSentenceStartMs(index: Int): Long =
        if (index in _sentenceStartsMs.indices) _sentenceStartsMs[index] else 0L

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
