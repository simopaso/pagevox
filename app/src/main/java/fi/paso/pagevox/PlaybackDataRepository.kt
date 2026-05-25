package fi.paso.pagevox

/**
 * A singleton repository to hold the shared playback data (sentences).
 * This decouples data storage from the Android Service and Activity lifecycle,
 * and prevents TransactionTooLargeException by avoiding Intent Bundles for large data.
 */
object PlaybackDataRepository {
    private val _sentences = mutableListOf<String>()
    
    val sentences: List<String>
        get() = _sentences

    fun setSentences(newSentences: List<String>) {
        _sentences.clear()
        _sentences.addAll(newSentences)
    }

    fun clear() {
        _sentences.clear()
    }
    
    fun getSentence(index: Int): String? {
        return if (index in _sentences.indices) _sentences[index] else null
    }
}