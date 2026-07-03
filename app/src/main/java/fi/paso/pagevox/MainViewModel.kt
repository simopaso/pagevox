package fi.paso.pagevox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val repo: SettingsRepository) : ViewModel() {
    var url by mutableStateOf("")
    var homeUrl by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var currentSentenceText by mutableStateOf("")   // drives the "Now Reading" bar
    var currentHighlightIndex by mutableIntStateOf(-1)
    var sentences = emptyList<String>()
    var history by mutableStateOf<List<WebPage>>(emptyList())
    var bookmarks by mutableStateOf<List<WebPage>>(emptyList())
    var currentPageTitle by mutableStateOf("")
    var forceDarkWeb by mutableStateOf(false)
    var textZoom by mutableIntStateOf(DEFAULT_TEXT_ZOOM)
    var speechRate by mutableFloatStateOf(DEFAULT_SPEECH_RATE)
    var readerMode by mutableStateOf(false)
    var followAlong by mutableStateOf(true)
    var selectedVoice by mutableStateOf("")   // "" = system default voice

    val isCurrentBookmarked: Boolean get() = bookmarks.any { it.url == url }

    private var initialIndex = 0

    init {
        // If the service kept running while this ViewModel was recreated
        // (e.g. process-priority shuffling) the singleton repository still has
        // the sentence list, so the slider can render immediately instead of
        // appearing empty until the user presses play again.
        if (PlaybackDataRepository.sentences.isNotEmpty()) {
            sentences = PlaybackDataRepository.sentences.toList()
        }
        viewModelScope.launch {
            val prefs = repo.prefsFlow.first()
            homeUrl = prefs.homeUrl
            forceDarkWeb = prefs.forceDarkWeb
            textZoom = prefs.textZoom
            speechRate = prefs.speechRate
            PlaybackDataRepository.speechRate = prefs.speechRate
            readerMode = prefs.readerMode
            followAlong = prefs.followAlong
            selectedVoice = prefs.selectedVoice
            PlaybackDataRepository.selectedVoiceName = prefs.selectedVoice.ifBlank { null }
            // Only restore the saved page if a share/VIEW intent hasn't already
            // set a URL (loadUrl runs synchronously in onCreate, before this).
            if (url.isEmpty()) {
                url = prefs.lastUrl
                initialIndex = prefs.lastSentenceIndex
            }
            // Place the indicator at the saved position so it's visible before
            // the first updateIndex broadcast arrives from the service.
            if (currentHighlightIndex < 0 && sentences.isNotEmpty()) {
                val idx = initialIndex.coerceIn(0, sentences.lastIndex)
                currentHighlightIndex = idx
                currentSentenceText = sentences.getOrElse(idx) { "" }
            }
        }
        viewModelScope.launch { repo.historyFlow.collect { history = it } }
        viewModelScope.launch { repo.bookmarksFlow.collect { bookmarks = it } }
    }

    /** Address-bar submit: interpret the text as URL or search, then load it. */
    fun submitAddressBarInput(input: String) {
        val resolved = resolveAddressBarInput(input)
        if (resolved.isNotEmpty()) loadUrl(resolved)
    }

    /** Handle a URL or text shared into the app (ACTION_SEND / ACTION_VIEW).
     *  Opens the first URL found in the payload, otherwise treats it as a query. */
    fun loadSharedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val url = Regex("""https?://\S+""").find(trimmed)?.value
        if (url != null) loadUrl(url) else submitAddressBarInput(trimmed)
    }

    fun loadUrl(newUrl: String) {
        if (newUrl == url) return
        // A page that redirects to a normalized form of the same URL (trailing
        // slash, http→https, fragment change, host case) is NOT a navigation —
        // treat it as the same page so the saved reading position survives.
        // Without this, reopening the app after the process was killed while
        // paused would lose position whenever the page issues such a redirect
        // (very common: e.g. /article → /article/).
        if (url.isNotEmpty() && normalizeUrlForCompare(newUrl) == normalizeUrlForCompare(url)) {
            url = newUrl
            viewModelScope.launch { repo.updateLastUrl(newUrl) }
            return
        }
        url = newUrl
        currentPageTitle = ""
        currentHighlightIndex = -1
        currentSentenceText = ""
        // Revisited pages resume from their saved per-page position (kept on
        // the history entry); genuinely new pages start at the top.
        val savedPosition = history.firstOrNull {
            normalizeUrlForCompare(it.url) == normalizeUrlForCompare(newUrl)
        }?.position ?: 0
        initialIndex = savedPosition
        sentences = emptyList()
        viewModelScope.launch {
            repo.updateLastUrl(newUrl)
            repo.updateLastIndex(savedPosition)
            repo.addHistory(WebPage(newUrl, newUrl))
        }
    }

    /** WebView reported a page title; remember it and backfill the history row. */
    fun onPageTitle(pageUrl: String, title: String) {
        if (title.isBlank()) return
        if (pageUrl == url) currentPageTitle = title
        viewModelScope.launch { repo.updatePageTitle(pageUrl, title) }
    }

    fun toggleBookmark() {
        if (url.isBlank()) return
        viewModelScope.launch {
            if (isCurrentBookmarked) repo.removeBookmark(url)
            else repo.addBookmark(WebPage(url, currentPageTitle.ifBlank { url }))
        }
    }

    fun removeBookmark(url: String) = viewModelScope.launch { repo.removeBookmark(url) }

    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }

    fun updateHomeUrl(newUrl: String) {
        homeUrl = newUrl
        viewModelScope.launch { repo.updateHomeUrl(newUrl) }
    }

    fun updateForceDarkWeb(enabled: Boolean) {
        forceDarkWeb = enabled
        viewModelScope.launch { repo.updateForceDarkWeb(enabled) }
    }

    fun increaseTextSize() = applyTextZoom(textZoom + TEXT_ZOOM_STEP)
    fun decreaseTextSize() = applyTextZoom(textZoom - TEXT_ZOOM_STEP)

    private fun applyTextZoom(zoom: Int) {
        val clamped = zoom.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        if (clamped == textZoom) return
        textZoom = clamped
        viewModelScope.launch { repo.updateTextZoom(clamped) }
    }

    fun applySpeechRate(rate: Float) {
        if (rate == speechRate) return
        speechRate = rate
        // The playback service reads this before each utterance, so a change
        // takes effect on the next sentence without a media-session round trip.
        PlaybackDataRepository.speechRate = rate
        viewModelScope.launch { repo.updateSpeechRate(rate) }
    }

    /** Toggle reader mode. Clears extracted text so the next play re-reads the
     *  distilled content; the visual de-clutter is applied live by the WebView. */
    fun toggleReaderMode() {
        readerMode = !readerMode
        sentences = emptyList()
        currentHighlightIndex = -1
        currentSentenceText = ""
        PlaybackDataRepository.clear()
        viewModelScope.launch { repo.updateReaderMode(readerMode) }
    }

    /** Toggle karaoke-style follow-along (auto-scroll + sentence highlight). */
    fun toggleFollowAlong() {
        followAlong = !followAlong
        viewModelScope.launch { repo.updateFollowAlong(followAlong) }
    }

    /** Pick an in-app TTS voice by name ("" = system default). Takes effect at
     *  the next sentence boundary via PlaybackDataRepository. */
    fun updateSelectedVoice(name: String) {
        selectedVoice = name
        PlaybackDataRepository.selectedVoiceName = name.ifBlank { null }
        viewModelScope.launch { repo.updateSelectedVoice(name) }
    }

    fun onTextsExtracted(lang: String?, elementTexts: List<String>, onReadyToPlay: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            val result = withContext(Dispatchers.Default) {
                val extracted = mutableListOf<String>()
                elementTexts.forEach { text ->
                    // innerText keeps the newlines the browser inserts for <br>
                    // and inner block boundaries. Collapse all whitespace runs to a
                    // single space first, otherwise those embedded newlines survive
                    // inside a sentence and the TTS engine pauses mid-sentence on them.
                    val normalized = text.replace(Regex("\\s+"), " ").trim()
                    extracted.addAll(splitIntoSentences(normalized))
                }
                if (extracted.isNotEmpty()) extracted else null
            }
            if (result != null) {
                sentences = result
                PlaybackDataRepository.setSentences(sentences, lang)
                onReadyToPlay()
            }
            isLoading = false
        }
    }

    fun updateHighlight(index: Int) {
        currentHighlightIndex = index
        initialIndex = index
        currentSentenceText = sentences.getOrElse(index) { "" }
        viewModelScope.launch { repo.updateReadingPosition(url, index) }
    }

    /** Service finished reading the last sentence. Clear the visual highlight
     *  and reset the "where to start next" pointer to the beginning, but DON'T
     *  touch the persisted lastSentenceIndex — leave it at the final sentence
     *  so a relaunch resumes the saved position instead of silently jumping to
     *  0 if the page happened to finish in the background. */
    fun onPlaybackEnded() {
        currentHighlightIndex = -1
        currentSentenceText = ""
        initialIndex = 0
    }

    fun getStartIndex(): Int = initialIndex

    fun findSentenceIndex(clickedText: String): Int {
        if (sentences.isEmpty()) return -1

        // Collapse all whitespace (incl. non-breaking spaces) to single spaces.
        fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim()

        val normalizedClicked = normalize(clickedText)
        if (normalizedClicked.isEmpty()) return -1

        // Must use the same splitter as extraction, or tap-to-sentence matching
        // disagrees about where sentences begin.
        val firstSentence = splitIntoSentences(clickedText.trim())
            .firstOrNull()
            ?.let { normalize(it) }
            ?: normalizedClicked

        // 1. Exact match on the first sentence of the tapped element.
        val exact = sentences.indexOfFirst { normalize(it).equals(firstSentence, ignoreCase = true) }
        if (exact >= 0) return exact

        // 2. Tapped paragraph starts with one of our extracted sentences.
        val startsWith = sentences.indexOfFirst { s ->
            val n = normalize(s)
            n.length >= 10 && normalizedClicked.startsWith(n, ignoreCase = true)
        }
        if (startsWith >= 0) return startsWith

        // 3. First-sentence prefix match for minor differences.
        val prefix = firstSentence.take(40)
        if (prefix.length >= 10) {
            val prefixMatch = sentences.indexOfFirst {
                normalize(it).startsWith(prefix, ignoreCase = true)
            }
            if (prefixMatch >= 0) return prefixMatch
        }

        // 4. Last resort: tapped text contains one of our sentences as a substring.
        return sentences.indexOfFirst { s ->
            val n = normalize(s)
            n.length >= 15 && normalizedClicked.contains(n, ignoreCase = true)
        }
    }
}

class ViewModelFactory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
