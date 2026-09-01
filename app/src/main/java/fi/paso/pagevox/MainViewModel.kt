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
    // Sentence indices where a page section (h1–h6) starts, for the scrubber's
    // section ticks. Kept as UI state so recomposition picks it up; the service
    // reads the same data straight from PlaybackDataRepository.
    var sectionStarts by mutableStateOf<List<Int>>(emptyList())
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

    /** Pages started but not finished, newest first — the "Continue listening"
     *  shelf. Entries saved before the sentence count was recorded (position but
     *  no total) can't be shown as a percentage, so they're left out until the
     *  page is read again. */
    val continueListening: List<WebPage>
        get() = history.filter {
            it.sentenceCount > 0 && it.position > 0 && it.position < it.sentenceCount - 1
        }

    /** Heading of the section [index] falls in, for the scrubber readout. */
    fun sectionTitleAt(index: Int): String? = PlaybackDataRepository.sectionTitleAt(index)

    /** Estimated milliseconds of reading left from [index], at 1x. The caller
     *  divides by the speech rate — the estimates are rate-independent so they
     *  stay valid when the user changes speed mid-page. */
    fun remainingMsFrom(index: Int): Long = PlaybackDataRepository.baseRemainingMsFrom(index)

    private var initialIndex = 0

    // Set to the restored URL while a cold-start restore is in flight, cleared
    // the first time loadUrl() sees that page settle. The WebView's very first
    // onPageFinished callback for a restored page can report a materially
    // different URL — a canonical redirect, an added tracking/session query
    // param, an AMP variant — none of which loadUrl() can reliably recognize
    // as "still the same page" via URL comparison alone (normalizeUrlForCompare
    // only catches trivial variants like a trailing slash). Trusting the
    // already-correct initialIndex restored from prefs instead of re-deriving
    // it from a URL match sidesteps that guessing game entirely.
    private var pendingRestoreUrl: String? = null

    init {
        // If the service kept running while this ViewModel was recreated
        // (e.g. process-priority shuffling) the singleton repository still has
        // the sentence list, so the slider can render immediately instead of
        // appearing empty until the user presses play again.
        if (PlaybackDataRepository.sentences.isNotEmpty()) {
            sentences = PlaybackDataRepository.sentences.toList()
            sectionStarts = PlaybackDataRepository.sectionStarts.toList()
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
                pendingRestoreUrl = prefs.lastUrl
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

    /** The WebView reported the URL it settled on after loading — including
     *  whatever redirect the server issued along the way. Distinct from
     *  [loadUrl] (explicit navigation): the very first such report after a
     *  cold-start restore is the restored page settling, however much its
     *  final URL drifted from what was saved (canonical redirect, an added
     *  query param, an AMP variant, ...) — not a navigation away from it. Keep
     *  the initialIndex already restored from prefs instead of re-deriving it
     *  from a URL match a real redirect can easily defeat; see
     *  [pendingRestoreUrl]. */
    fun onPageUrlSettled(newUrl: String) {
        if (pendingRestoreUrl != null) {
            pendingRestoreUrl = null
            url = newUrl
            viewModelScope.launch { repo.updateLastUrl(newUrl) }
            return
        }
        loadUrl(newUrl)
    }

    fun loadUrl(newUrl: String) {
        // Any explicit navigation means we're no longer waiting to confirm the
        // cold-start restore, even if the WebView hasn't reported back yet.
        pendingRestoreUrl = null
        if (newUrl == url) return
        // A page that redirects to a normalized form of the same URL (trailing
        // slash, http→https, fragment change, host case) is NOT a navigation —
        // treat it as the same page so the saved reading position survives.
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
        sectionStarts = emptyList()
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

    /** Save the home page. An empty field means "no real home page set", which
     *  resolves to the bundled manual — resolved here and not only in
     *  [SettingsRepository], because this in-memory value is what the Home
     *  button reads for the rest of the session and loading "" would leave the
     *  screen with no WebView at all. */
    fun updateHomeUrl(newUrl: String) {
        val resolved = newUrl.trim().ifBlank { manualUrl() }
        homeUrl = resolved
        viewModelScope.launch { repo.updateHomeUrl(resolved) }
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
        sectionStarts = emptyList()
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

    fun onTextsExtracted(lang: String?, blocks: List<PageBlock>, onReadyToPlay: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            val result = withContext(Dispatchers.Default) {
                buildNarration(blocks, lang).takeIf { it.display.isNotEmpty() }
            }
            if (result != null) {
                sentences = result.display
                sectionStarts = result.sectionStarts
                // Seed the resume point from initialIndex (restored from disk on
                // a cold start, or the per-page saved position when revisiting).
                // Warm restarts don't re-extract, so this only runs when the
                // sentence set is genuinely (re)built.
                PlaybackDataRepository.setSentences(
                    result.display,
                    language = lang,
                    pageUrl = url,
                    startIndex = initialIndex,
                    spokenSentences = result.spoken,
                    sectionStarts = result.sectionStarts,
                    sectionTitles = result.sectionTitles
                )
                onReadyToPlay()
            }
            isLoading = false
        }
    }

    /** Reflect the service's current sentence index in the UI (highlight, slider,
     *  "Now Reading" bar). Persisting this to disk is the service's job, not the
     *  UI's — it happens via PlaybackService.persistPosition() regardless of
     *  whether a MediaController is connected, so a pause or sentence boundary
     *  is saved even while the app is backgrounded. Routing it through here too
     *  would only reach disk while the app is in the foreground, which is the
     *  exact gap that used to lose the position on a background/lock-screen
     *  pause followed by the app being closed. */
    fun updateHighlight(index: Int) {
        currentHighlightIndex = index
        initialIndex = index
        currentSentenceText = sentences.getOrElse(index) { "" }
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

    /** Where a plain Play/resume should start. The authoritative value is the
     *  playback singleton's currentIndex — it tracks the service's real position
     *  and, being process-scoped, survives the service instance being destroyed
     *  while paused (the exact case where the stale ViewModel initialIndex used
     *  to send resume back to sentence 0). [initialIndex] is only a fallback for
     *  the brief window before the sentence set has been (re)built. */
    fun getStartIndex(): Int =
        if (sentences.isNotEmpty()) PlaybackDataRepository.currentIndex else initialIndex

    /** Map the tapped text (from [tapDetectionJs], which begins at the exact
     *  tapped character and so is usually mid-sentence) to a sentence index.
     *  Returns -1 when nothing matches — the caller must NOT fall back to 0. */
    fun findSentenceIndex(clickedText: String): Int {
        if (sentences.isEmpty()) return -1

        // Collapse all whitespace (incl. non-breaking spaces) to single spaces.
        fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim()

        val clicked = normalize(clickedText)
        if (clicked.isEmpty()) return -1
        val normalizedSentences = sentences.map { normalize(it) }

        // Find the sentence the tap fell inside by locating the one that contains
        // a leading probe of the tapped text. A longer probe is more unique; a
        // shorter one still matches when the tap is near a sentence's end, where a
        // long probe would spill past the boundary into the next sentence. Taking
        // the first containing sentence (document order) keeps the earliest —
        // hence the correct — match when a short probe is ambiguous.
        for (probeLen in intArrayOf(48, 30, 18, 10)) {
            if (clicked.length < probeLen) continue
            val probe = clicked.substring(0, probeLen)
            val hit = normalizedSentences.indexOfFirst { it.contains(probe, ignoreCase = true) }
            if (hit >= 0) return hit
        }

        // Tap text shorter than the smallest probe (near end of document): match
        // on the whole thing, then on a sentence that starts with it.
        val whole = normalizedSentences.indexOfFirst { it.contains(clicked, ignoreCase = true) }
        if (whole >= 0) return whole
        return normalizedSentences.indexOfFirst { it.startsWith(clicked.take(18), ignoreCase = true) }
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
