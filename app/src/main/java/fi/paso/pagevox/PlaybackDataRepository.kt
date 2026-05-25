package fi.paso.pagevox

/**
 * Singleton holding the sentence list plus an estimated reading duration per
 * sentence. The duration estimates drive the silent ExoPlayer track so the
 * media-notification progress bar reflects how far through the page the TTS is.
 */
object PlaybackDataRepository {
    private val _sentences = mutableListOf<String>()
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

    fun setSentences(newSentences: List<String>, language: String? = null) {
        _sentences.clear()
        _sentences.addAll(newSentences)
        this.language = language
        _sentenceStartsMs.clear()
        var cumulative = 0L
        for (sentence in _sentences) {
            _sentenceStartsMs.add(cumulative)
            cumulative += estimateSentenceDurationMs(sentence)
        }
        _totalDurationMs = cumulative
    }

    fun clear() {
        _sentences.clear()
        _sentenceStartsMs.clear()
        _totalDurationMs = 0L
        language = null
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
