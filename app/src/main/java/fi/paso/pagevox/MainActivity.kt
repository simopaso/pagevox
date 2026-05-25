package fi.paso.pagevox

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import fi.paso.pagevox.ui.theme.PageVoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

private const val TAG = "MainActivity"
private val Context.dataStore by preferencesDataStore(name = "settings")

// Extracts innerText of semantic elements (falls back to body for .txt pages).
// Returns a JSON array string so it can be passed back via evaluateJavascript.
private val EXTRACT_TEXT_JS = """
(function() {
    var els = document.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,pre');
    var texts = [];
    if (els.length === 0) {
        var b = (document.body.innerText || '').trim();
        if (b) texts.push(b);
    } else {
        for (var i = 0; i < els.length; i++) {
            var t = (els[i].innerText || '').trim();
            if (t) texts.push(t);
        }
    }
    // Declared content language: <html lang> first, then a content-language meta.
    var lang = (document.documentElement.getAttribute('lang') || '').trim();
    if (!lang) {
        var m = document.querySelector('meta[http-equiv="content-language" i]');
        if (m && m.content) lang = m.content.trim().split(',')[0].trim();
    }
    return JSON.stringify({ lang: lang, texts: texts });
})();
""".trimIndent()

// Returns the text of the element at the tapped pixel coordinates.
// Uses document.elementFromPoint, which works regardless of click handlers
// and is reliable for plain text where the DOM click event often doesn't fire
// in Android WebView.
private fun tapDetectionJs(pxX: Float, pxY: Float) = """
(function() {
    var cx = $pxX / window.devicePixelRatio;
    var cy = $pxY / window.devicePixelRatio;
    var el = document.elementFromPoint(cx, cy);
    if (!el) return '';
    var blockTags = ['P','H1','H2','H3','H4','H5','H6','LI','PRE','BLOCKQUOTE','ARTICLE'];
    var node = el;
    for (var i = 0; i < 10 && node && node !== document.body; i++) {
        if (blockTags.indexOf(node.tagName) >= 0) {
            var text = (node.innerText || node.textContent || '').trim();
            if (text.length > 5) return text;
        }
        node = node.parentElement;
    }
    // Fallback: any ancestor with substantial text content.
    node = el;
    for (var j = 0; j < 5 && node && node !== document.body; j++) {
        var t = (node.innerText || node.textContent || '').trim();
        if (t.length > 20) return t;
        node = node.parentElement;
    }
    return '';
})();
""".trimIndent()

private fun extractTexts(webView: WebView?, onResult: (lang: String?, texts: List<String>) -> Unit) {
    webView?.evaluateJavascript(EXTRACT_TEXT_JS) { result ->
        try {
            val jsonStr = JSONTokener(result).nextValue() as String
            val obj = JSONObject(jsonStr)
            val lang = obj.optString("lang").ifBlank { null }
            val arr = obj.getJSONArray("texts")
            onResult(lang, (0 until arr.length()).map { arr.getString(it) })
        } catch (e: Exception) {
            Log.e(TAG, "Text extraction failed", e)
        }
    }
}

// --- Data & Settings ---
// WebView text zoom is a percentage (100 = normal). Bounded so the page stays
// usable at the extremes; each button press moves one step.
private const val DEFAULT_TEXT_ZOOM = 100
private const val MIN_TEXT_ZOOM = 50
private const val MAX_TEXT_ZOOM = 300
private const val TEXT_ZOOM_STEP = 10

data class UserPreferences(
    val lastUrl: String,
    val lastSentenceIndex: Int,
    val homeUrl: String,
    val forceDarkWeb: Boolean,
    val textZoom: Int
)

/** A visited or bookmarked page. [title] falls back to the URL when unknown. */
data class WebPage(val url: String, val title: String)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val LAST_URL = stringPreferencesKey("last_url")
        val LAST_SENTENCE_INDEX = intPreferencesKey("last_sentence_index")
        val HOME_URL = stringPreferencesKey("home_url")
        val HISTORY = stringPreferencesKey("history")
        val BOOKMARKS = stringPreferencesKey("bookmarks")
        val FORCE_DARK_WEB = booleanPreferencesKey("force_dark_web")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
    }

    val prefsFlow = context.dataStore.data.map { prefs ->
        UserPreferences(
            prefs[Keys.LAST_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
            prefs[Keys.LAST_SENTENCE_INDEX] ?: 0,
            prefs[Keys.HOME_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
            prefs[Keys.FORCE_DARK_WEB] ?: false,
            prefs[Keys.TEXT_ZOOM] ?: DEFAULT_TEXT_ZOOM
        )
    }

    // Most-recent-first list of visited pages, used for the History view and
    // address-bar autocomplete. Stored as a JSON array of {url, title}.
    val historyFlow = context.dataStore.data.map { decodePages(it[Keys.HISTORY]) }

    // User-saved pages, newest first.
    val bookmarksFlow = context.dataStore.data.map { decodePages(it[Keys.BOOKMARKS]) }

    suspend fun updateLastUrl(url: String) = context.dataStore.edit { it[Keys.LAST_URL] = url }
    suspend fun updateLastIndex(index: Int) = context.dataStore.edit { it[Keys.LAST_SENTENCE_INDEX] = index }
    suspend fun updateHomeUrl(url: String) = context.dataStore.edit { it[Keys.HOME_URL] = url }
    suspend fun updateForceDarkWeb(enabled: Boolean) = context.dataStore.edit { it[Keys.FORCE_DARK_WEB] = enabled }
    suspend fun updateTextZoom(zoom: Int) = context.dataStore.edit { it[Keys.TEXT_ZOOM] = zoom }

    suspend fun addHistory(page: WebPage) = context.dataStore.edit { prefs ->
        val previous = decodePages(prefs[Keys.HISTORY]).filter { it.url != page.url }
        prefs[Keys.HISTORY] = encodePages((listOf(page) + previous).take(HISTORY_LIMIT))
    }

    /** Patch the title of the most recent history entry for [url], once known. */
    suspend fun updatePageTitle(url: String, title: String) = context.dataStore.edit { prefs ->
        val current = decodePages(prefs[Keys.HISTORY])
        if (current.firstOrNull()?.url == url && current.first().title != title) {
            prefs[Keys.HISTORY] = encodePages(listOf(WebPage(url, title)) + current.drop(1))
        }
    }

    suspend fun clearHistory() = context.dataStore.edit { it.remove(Keys.HISTORY) }

    suspend fun addBookmark(page: WebPage) = context.dataStore.edit { prefs ->
        val previous = decodePages(prefs[Keys.BOOKMARKS]).filter { it.url != page.url }
        prefs[Keys.BOOKMARKS] = encodePages(listOf(page) + previous)
    }

    suspend fun removeBookmark(url: String) = context.dataStore.edit { prefs ->
        prefs[Keys.BOOKMARKS] = encodePages(decodePages(prefs[Keys.BOOKMARKS]).filter { it.url != url })
    }

    private companion object {
        const val HISTORY_LIMIT = 100

        fun encodePages(pages: List<WebPage>): String {
            val arr = JSONArray()
            pages.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
            return arr.toString()
        }

        fun decodePages(raw: String?): List<WebPage> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val url = o.getString("url")
                    WebPage(url, o.optString("title", url).ifBlank { url })
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * Turns whatever the user typed in the address bar into a URL, Chrome-style:
 *  - keeps anything that already has a scheme as-is,
 *  - prepends https:// to things that look like a bare host (a dot, no spaces),
 *  - otherwise treats the text as a web search.
 */
fun resolveAddressBarInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""

    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) return trimmed

    val looksLikeHost = !trimmed.contains(' ') &&
        trimmed.contains('.') &&
        !trimmed.startsWith('.') &&
        !trimmed.endsWith('.')
    if (looksLikeHost) return "https://$trimmed"

    val query = java.net.URLEncoder.encode(trimmed, "UTF-8")
    return "https://www.google.com/search?q=$query"
}

// --- ViewModel ---
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
            url = prefs.lastUrl
            homeUrl = prefs.homeUrl
            forceDarkWeb = prefs.forceDarkWeb
            textZoom = prefs.textZoom
            initialIndex = prefs.lastSentenceIndex
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

    fun loadUrl(newUrl: String) {
        if (newUrl != url) {
            url = newUrl
            currentPageTitle = ""
            currentHighlightIndex = -1
            currentSentenceText = ""
            initialIndex = 0
            sentences = emptyList()
            viewModelScope.launch {
                repo.updateLastUrl(newUrl)
                repo.updateLastIndex(0)
                repo.addHistory(WebPage(newUrl, newUrl))
            }
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

    fun onTextsExtracted(lang: String?, elementTexts: List<String>, onReadyToPlay: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            val result = withContext(Dispatchers.Default) {
                val extracted = mutableListOf<String>()
                elementTexts.forEach { text ->
                    text.split(Regex("(?<=[.!?])\\s+")).forEach { sentence ->
                        if (sentence.isNotBlank()) extracted.add(sentence.trim())
                    }
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
        viewModelScope.launch { repo.updateLastIndex(index) }
    }

    fun getStartIndex(): Int = initialIndex

    fun findSentenceIndex(clickedText: String): Int {
        if (sentences.isEmpty()) return -1

        // Collapse all whitespace (incl. non-breaking spaces) to single spaces.
        fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim()

        val normalizedClicked = normalize(clickedText)
        if (normalizedClicked.isEmpty()) return -1

        val firstSentence = clickedText.trim()
            .split(Regex("(?<=[.!?])\\s+"))
            .firstOrNull { it.isNotBlank() }
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

// --- Activity ---
class MainActivity : ComponentActivity() {
    private var mediaController: MediaController? by mutableStateOf(null)

    // ViewModel at Activity level so onStart() can reference it inside the
    // MediaController.Listener — setListener() must be called at build time
    // and addListener() does NOT dispatch MediaController.Listener callbacks
    // such as onCustomCommand.
    private val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelFactory(SettingsRepository(applicationContext))
        )[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            PageVoxTheme {
                MainScreen(mainViewModel, mediaController)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startForegroundService(Intent(this, PlaybackService::class.java))

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(
                    controller: MediaController,
                    command: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (command.customAction == "updateIndex") {
                        mainViewModel.updateHighlight(args.getInt("index"))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .buildAsync()
        future.addListener({
            try {
                mediaController = future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Controller connection failed", e)
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        mediaController?.release()
        mediaController = null
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, controller: MediaController?) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    // Stop the service when the user navigates to a different page. We have to
    // distinguish a real navigation from the initial url-load (where the VM
    // transitions from "" to the saved lastUrl) — otherwise every fresh activity
    // would kill the TTS that's already playing in the background service.
    // `remember` (not rememberSaveable) resets to null on every new activity, so
    // the first non-empty url we observe after creation is treated as the
    // baseline rather than a navigation.
    val latestController = rememberUpdatedState(controller)
    var lastSeenUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.url) {
        val curr = viewModel.url
        if (curr.isEmpty()) return@LaunchedEffect
        val prev = lastSeenUrl
        lastSeenUrl = curr
        if (prev != null && prev != curr) {
            latestController.value?.sendCustomCommand(
                SessionCommand("stopPlayback", Bundle.EMPTY), Bundle.EMPTY
            )
        }
    }

    // Sync play/pause state from the controller (onCustomCommand is handled in
    // MainActivity.onStart via MediaController.Builder.setListener — addListener
    // only covers Player.Listener events, not MediaController.Listener callbacks).
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        controller?.addListener(listener)
        if (controller != null) isPlaying = controller.isPlaying
        onDispose { controller?.removeListener(listener) }
    }

    // Playback Logic
    fun togglePlay() {
        if (controller == null) return
        if (isPlaying) {
            controller.pause()
        } else {
            // If already prepared/playing silent audio, just play. 
            // If we need to load new content (e.g. fresh start), send command.
            // Simple heuristic: if we have sentences and player is IDLE, we start fresh.
            if (controller.playbackState == Player.STATE_IDLE && viewModel.sentences.isNotEmpty()) {
                val args = Bundle().apply { putInt("startIndex", viewModel.getStartIndex()) }
                controller.sendCustomCommand(SessionCommand("playSentences", Bundle.EMPTY), args)
            } else {
                controller.play()
            }
        }
    }

    Scaffold(
        topBar = {
            AddressBar(
                url = viewModel.url,
                history = viewModel.history,
                isBookmarked = viewModel.isCurrentBookmarked,
                onGo = { viewModel.submitAddressBarInput(it) },
                onToggleBookmark = { viewModel.toggleBookmark() },
                onOpenLibrary = { showLibrary = true },
                onHome = { viewModel.loadUrl(viewModel.homeUrl) },
                onBack = { webView?.goBack() },
                onForward = { webView?.goForward() }
            )
        },
        bottomBar = {
            Column {
                if (viewModel.currentSentenceText.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = viewModel.currentSentenceText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                BottomBar(isPlaying,
                    onPlayPause = {
                        if (viewModel.sentences.isEmpty()) {
                            extractTexts(webView) { lang, texts ->
                                viewModel.onTextsExtracted(lang, texts) { togglePlay() }
                            }
                        } else {
                            togglePlay()
                        }
                    },
                    onSettings = { showSettings = true },
                    onTextSmaller = { viewModel.decreaseTextSize() },
                    onTextLarger = { viewModel.increaseTextSize() },
                    onTtsSettings = {
                        try {
                            val intent = Intent("com.android.settings.TTS_SETTINGS")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            webView?.context?.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (viewModel.url.isNotEmpty()) {
                        WebViewContainer(
                            url = viewModel.url,
                            currentSentence = viewModel.currentSentenceText,
                            onWebViewReady = { webView = it },
                            onUrlChange = { viewModel.loadUrl(it) },
                            onTitleChange = { pageUrl, title -> viewModel.onPageTitle(pageUrl, title) },
                            onCanGoBackChanged = { canGoBack = it },
                            onTextTapped = { clickedText ->
                                val seekAndPlay = {
                                    val idx = viewModel.findSentenceIndex(clickedText).coerceAtLeast(0)
                                    val args = Bundle().apply { putInt("startIndex", idx) }
                                    controller?.sendCustomCommand(
                                        SessionCommand("playSentences", Bundle.EMPTY), args
                                    )
                                }
                                if (viewModel.sentences.isEmpty()) {
                                    extractTexts(webView) { lang, texts ->
                                        viewModel.onTextsExtracted(lang, texts) { seekAndPlay() }
                                    }
                                } else {
                                    seekAndPlay()
                                }
                            },
                            forceDark = viewModel.forceDarkWeb,
                            textZoom = viewModel.textZoom
                        )
                    }
                }
                ReadingPositionSlider(
                    totalSentences = viewModel.sentences.size,
                    currentIndex = viewModel.currentHighlightIndex,
                    onSeekChange = { idx ->
                        // Live scroll the WebView to the sentence under the thumb.
                        val text = viewModel.sentences.getOrNull(idx) ?: return@ReadingPositionSlider
                        val escaped = JSONObject.quote(text)
                        webView?.evaluateJavascript(
                            "(function(){ window.find($escaped, false, false, true); })();",
                            null
                        )
                    },
                    onSeekFinished = { idx ->
                        // Commit: start playback from this position (TTS will
                        // broadcast updateIndex which drives the indicator).
                        val args = Bundle().apply { putInt("startIndex", idx) }
                        controller?.sendCustomCommand(
                            SessionCommand("playSentences", Bundle.EMPTY), args
                        )
                    },
                    modifier = Modifier.width(20.dp).fillMaxHeight()
                )
            }
            if (viewModel.isLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    
    if (showSettings) {
        SettingsDialog(
            currentHomeUrl = viewModel.homeUrl,
            forceDarkWeb = viewModel.forceDarkWeb,
            onToggleForceDarkWeb = { viewModel.updateForceDarkWeb(it) },
            onDismiss = { showSettings = false },
            onSave = { newUrl: String ->
                viewModel.updateHomeUrl(newUrl)
                showSettings = false
            }
        )
    }

    if (showLibrary) {
        LibrarySheet(
            bookmarks = viewModel.bookmarks,
            history = viewModel.history,
            onOpen = { pageUrl ->
                showLibrary = false
                viewModel.loadUrl(pageUrl)
            },
            onRemoveBookmark = { viewModel.removeBookmark(it) },
            onClearHistory = { viewModel.clearHistory() },
            onDismiss = { showLibrary = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBar(
    url: String,
    history: List<WebPage>,
    isBookmarked: Boolean,
    onGo: (String) -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenLibrary: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    val text = field.text
    var expanded by remember { mutableStateOf(false) }

    // Match history (url or title) against what's typed. Don't suggest when the
    // field still shows the current page's URL untouched (nothing useful to offer).
    val suggestions = remember(text, history) {
        if (text.isBlank() || text == url) emptyList()
        else history.filter {
            (it.url.contains(text, ignoreCase = true) || it.title.contains(text, ignoreCase = true)) &&
                it.url != text
        }.take(6)
    }
    val showMenu = expanded && suggestions.isNotEmpty()

    fun submit(value: String) {
        expanded = false
        onGo(value)
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton(onForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            ExposedDropdownMenuBox(
                expanded = showMenu,
                onExpandedChange = { },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        expanded = it.text.isNotBlank()
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            // When the bar gains focus, select the whole URL so typing
                            // instantly replaces it (Chrome-like behavior).
                            if (focus.isFocused) {
                                field = field.copy(selection = TextRange(0, field.text.length))
                            }
                        },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = onToggleBookmark) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (isBookmarked) "Remove bookmark" else "Add bookmark"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit(text) })
                )
                ExposedDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { expanded = false }
                ) {
                    suggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    if (suggestion.title.isNotBlank() && suggestion.title != suggestion.url) {
                                        Text(
                                            text = suggestion.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Text(
                                        text = suggestion.url,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            onClick = {
                                field = TextFieldValue(suggestion.url, TextRange(suggestion.url.length))
                                submit(suggestion.url)
                            }
                        )
                    }
                }
            }
            IconButton(onOpenLibrary) { Icon(Icons.Default.Bookmarks, "Bookmarks & history") }
            IconButton(onHome) { Icon(Icons.Default.Home, "Home") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    bookmarks: List<WebPage>,
    history: List<WebPage>,
    onOpen: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("Bookmarks") },
                icon = { Icon(Icons.Default.Bookmark, contentDescription = null) })
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("History") },
                icon = { Icon(Icons.Default.History, contentDescription = null) })
        }

        val entries = if (tab == 0) bookmarks else history
        val isBookmarksTab = tab == 0

        if (!isBookmarksTab && history.isNotEmpty()) {
            TextButton(
                onClick = onClearHistory,
                modifier = Modifier.align(Alignment.End).padding(end = 8.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Clear history")
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = if (isBookmarksTab) "No bookmarks yet" else "No history yet",
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(entries, key = { it.url }) { page ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = page.title.ifBlank { page.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                text = page.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = if (isBookmarksTab) {
                            {
                                IconButton(onClick = { onRemoveBookmark(page.url) }) {
                                    Icon(Icons.Default.Delete, "Remove bookmark")
                                }
                            }
                        } else null,
                        modifier = Modifier.clickable { onOpen(page.url) }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun BottomBar(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSettings: () -> Unit,
    onTextSmaller: () -> Unit,
    onTextLarger: () -> Unit,
    onTtsSettings: () -> Unit
) {
    BottomAppBar {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
            IconButton(onTextSmaller) { Icon(Icons.Default.TextDecrease, "Decrease text size") }
            FloatingActionButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
            }
            IconButton(onTextLarger) { Icon(Icons.Default.TextIncrease, "Increase text size") }
            IconButton(onTtsSettings) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "TTS Settings") }
        }
    }
}

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
@Composable
fun WebViewContainer(
    url: String,
    currentSentence: String,
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String, String) -> Unit,
    onCanGoBackChanged: (Boolean) -> Unit,
    onTextTapped: (String) -> Unit,
    forceDark: Boolean,
    textZoom: Int
) {
    val context = LocalContext.current
    val latestOnUrlChange = rememberUpdatedState(onUrlChange)
    val latestOnTitleChange = rememberUpdatedState(onTitleChange)
    val latestOnCanGoBackChanged = rememberUpdatedState(onCanGoBackChanged)
    val latestOnTextTapped = rememberUpdatedState(onTextTapped)

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("WebViewConsole", "[${msg.messageLevel()}] ${msg.message()}")
                    return true
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    val pageUrl = view?.url
                    if (pageUrl != null && !pageUrl.startsWith("data:") && !title.isNullOrBlank()) {
                        latestOnTitleChange.value(pageUrl, title)
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    latestOnCanGoBackChanged.value(view?.canGoBack() == true)
                    if (url != null && !url.startsWith("data:")) {
                        latestOnUrlChange.value(url)
                    }
                }
            }

            // Tap-to-seek: GestureDetector observes raw MotionEvents (returning
            // false so WebView still handles scroll/text-selection normally).
            // On a single tap we query the DOM via document.elementFromPoint —
            // this works for plain text where the DOM click event often does
            // not fire in Android WebView.
            val webViewRef = this
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    webViewRef.evaluateJavascript(tapDetectionJs(e.x, e.y)) { result ->
                        try {
                            val text = JSONTokener(result).nextValue() as? String
                            if (!text.isNullOrEmpty()) {
                                Log.d(TAG, "Tap detected, ${text.length} chars")
                                latestOnTextTapped.value(text)
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "Tap JS parse failed: $result", ex)
                        }
                    }
                    return false
                }
            })
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }

            onWebViewReady(this)
        }
    }

    LaunchedEffect(url) {
        if (url.isNotEmpty() && !url.startsWith("data:") && url != webView.url) {
            webView.loadUrl(url)
        }
    }

    LaunchedEffect(currentSentence) {
        if (currentSentence.isNotEmpty()) {
            val escaped = JSONObject.quote(currentSentence)
            webView.evaluateJavascript(
                "(function(){ window.find($escaped, false, false, true); })();",
                null
            )
        }
    }

    // Reflect the user's text-size choice; WebView re-lays-out the page live.
    LaunchedEffect(textZoom) {
        webView.settings.textZoom = textZoom
    }

    // Native dark rendering: WebView algorithmically darkens content that has no
    // dark styles of its own (and lets sites that do support dark show theirs).
    // Unlike CSS injection this also covers non-HTML documents such as plain-text
    // files. It activates when the app is in dark theme; the toggle gates it.
    LaunchedEffect(forceDark) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, forceDark)
        }
    }

    AndroidView({ webView }, Modifier.fillMaxSize())
}

@Composable
fun ReadingPositionSlider(
    totalSentences: Int,
    currentIndex: Int,
    onSeekChange: (Int) -> Unit,    // fires during drag (continuous preview)
    onSeekFinished: (Int) -> Unit,  // fires on tap or drag release (commit)
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (totalSentences <= 0) return@BoxWithConstraints

        var dragging by remember { mutableStateOf(false) }
        var localIndex by remember { mutableIntStateOf(currentIndex.coerceAtLeast(0)) }

        // Track TTS-driven index updates only when the user isn't actively
        // dragging — otherwise progressions would yank the thumb away.
        LaunchedEffect(currentIndex) {
            if (!dragging) localIndex = currentIndex.coerceAtLeast(0)
        }

        val maxIdx = (totalSentences - 1).coerceAtLeast(0)
        val safeIndex = localIndex.coerceIn(0, maxIdx)
        val fraction = if (maxIdx == 0) 0f else safeIndex.toFloat() / maxIdx

        val barHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        fun yToIndex(y: Float): Int {
            if (maxIdx == 0) return 0
            val f = (y / barHeightPx).coerceIn(0f, 1f)
            return (f * maxIdx).roundToInt()
        }

        // Filled track from top to thumb.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceAtLeast(0.001f))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        )

        // Thumb marker.
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = (maxHeight * fraction) - 3.dp)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary)
        )

        // Position label, rotated to run along the narrow bar: "current / total".
        val label = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append("${safeIndex + 1}")
            }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(" / $totalSentences")
            }
        }
        Text(
            text = label,
            modifier = Modifier.align(Alignment.Center).verticalText(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )

        // Touch overlay: tap to jump, drag to scrub.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(totalSentences) {
                    detectTapGestures { offset ->
                        val idx = yToIndex(offset.y)
                        localIndex = idx
                        onSeekFinished(idx)
                    }
                }
                .pointerInput(totalSentences) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val idx = yToIndex(offset.y)
                            localIndex = idx
                            onSeekChange(idx)
                        },
                        onDragEnd = {
                            dragging = false
                            onSeekFinished(localIndex)
                        },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            val idx = yToIndex(change.position.y)
                            if (idx != localIndex) {
                                localIndex = idx
                                onSeekChange(idx)
                            }
                        }
                    )
                }
        )
    }
}

/**
 * Lays a composable out rotated 90° counter-clockwise. The content is measured
 * against the parent's *height* (so text won't wrap in a narrow column) and the
 * node reports swapped dimensions, then graphicsLayer performs the rotation. The
 * result reads bottom-to-top and occupies only the content's line height in width.
 */
private fun Modifier.verticalText(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
        )
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2)
            )
        }
    }
    .graphicsLayer(rotationZ = -90f)

@Composable
fun SettingsDialog(
    currentHomeUrl: String,
    forceDarkWeb: Boolean,
    onToggleForceDarkWeb: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(currentHomeUrl) { mutableStateOf(currentHomeUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Home Page URL:")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleForceDarkWeb(!forceDarkWeb) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dark mode for web pages")
                        Text(
                            "Force a dark appearance on websites and files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = forceDarkWeb, onCheckedChange = onToggleForceDarkWeb)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}