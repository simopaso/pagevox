package fi.paso.pagevox

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import fi.paso.pagevox.ui.theme.PageVoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.*

private const val TAG = "MainActivity"
// --- Data Persistence (Jetpack DataStore) ---
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class UserPreferences(
    val lastUrl: String,
    val lastSentenceIndex: Int,
    val homeUrl: String
)

class SettingsRepository(private val context: Context) {
    private object PreferencesKeys {
        val LAST_URL = stringPreferencesKey("last_url")
        val LAST_SENTENCE_INDEX = intPreferencesKey("last_sentence_index")
        val HOME_URL = stringPreferencesKey("home_url")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e("SettingsRepository", "Error reading preferences.", exception)
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val lastUrl = preferences[PreferencesKeys.LAST_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"
            val lastSentenceIndex = preferences[PreferencesKeys.LAST_SENTENCE_INDEX] ?: 0
            val homeUrl = preferences[PreferencesKeys.HOME_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"
            UserPreferences(lastUrl, lastSentenceIndex, homeUrl)
        }

    suspend fun updateLastUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_URL] = url
        }
    }

    suspend fun updateLastSentenceIndex(index: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SENTENCE_INDEX] = index
        }
    }
    suspend fun updateHomeUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HOME_URL] = url
        }
    }
}

// --- Main Activity ---
class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? by mutableStateOf(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val sessionToken = SessionToken(this@MainActivity, ComponentName(this@MainActivity, PlaybackService::class.java))
            val controllerFuture = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()
            controllerFuture.addListener(
                {
                    mediaController = controllerFuture.get()
                    Log.d(TAG, "MediaController connected: ${mediaController != null}")
                },
                MoreExecutors.directExecutor()
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            mediaController = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()

        setContent {
            PageVoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val settingsRepository = SettingsRepository(applicationContext)
                    PageVoxApp(
                        viewModel = viewModel(
                            factory = MainViewModelFactory(settingsRepository)
                        ),
                        mediaController = mediaController
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        Intent(this, PlaybackService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, connection, BIND_AUTO_CREATE)
            Log.d(TAG, "Service bound")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        mediaController?.release()
        mediaController = null
        unbindService(connection)
        Log.d(TAG, "Service unbound")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

// --- ViewModel ---
class MainViewModelFactory(
    val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainViewModel(
    val settingsRepository: SettingsRepository
) : ViewModel() {
    var sentences by mutableStateOf<List<String>>(emptyList())
        private set

    val url = mutableStateOf("")
    val homeUrl = mutableStateOf("")

    val isLoading = mutableStateOf(false)
    var modifiedHtmlForWebView by mutableStateOf<String?>(null)
    val parsingTrigger = mutableStateOf(0)

    var initialSentenceIndex by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            val prefs = settingsRepository.userPreferencesFlow.first()
            url.value = prefs.lastUrl
            homeUrl.value = prefs.homeUrl
            initialSentenceIndex = prefs.lastSentenceIndex
        }
    }

    fun onUrlChanged(newUrl: String) {
        url.value = newUrl
    }

    fun onUrlChangedFromWebView(newUrl: String, stopPlayback: () -> Unit) {
        if (url.value != newUrl) {
            url.value = newUrl
            stopPlayback()
            viewModelScope.launch {
                settingsRepository.updateLastUrl(newUrl)
                settingsRepository.updateLastSentenceIndex(0)
                initialSentenceIndex = 0
            }
            modifiedHtmlForWebView = null
            sentences = emptyList() // Reset sentences on new page
        }
    }

    fun loadNewUrl(stopPlayback: () -> Unit) {
        stopPlayback()
        viewModelScope.launch {
            settingsRepository.updateLastUrl(url.value)
            settingsRepository.updateLastSentenceIndex(0)
            initialSentenceIndex = 0
        }
        sentences = emptyList() // Reset sentences on new page
    }
    fun updateHomeUrl(newHomeUrl: String) {
        viewModelScope.launch {
            settingsRepository.updateHomeUrl(newHomeUrl)
            homeUrl.value = newHomeUrl
        }
    }

    fun triggerParsing() {
        parsingTrigger.value++
    }

    fun parseAndPrepare(rawContent: String, baseUrl: String, onFinished: () -> Unit) {
        viewModelScope.launch {
            isLoading.value = true
            modifiedHtmlForWebView = null

            val (htmlResult, parsedSentences) = withContext(Dispatchers.Default) {
                val document: Document = Jsoup.parse(rawContent, baseUrl)
                var contentElements = document.select("p, h1, h2, h3, h4, h5, h6")
                if (contentElements.isEmpty()) contentElements = document.select("pre")

                val plainSentences = mutableListOf<String>()
                var sentenceCounter = 0
                if (contentElements.isNotEmpty()) {
                    document.head().append(
                        """<style>
                        .pagevox-highlight { background-color: #FFDE03 !important; color: black !important; transition: background-color 0.3s ease-in-out; }
                        body { font-family: sans-serif; line-height: 1.6; padding: 1em; }
                        pre { white-space: pre-wrap; word-wrap: break-word; }
                       </style>""".trimIndent()
                    )
                    contentElements.forEach { element ->
                        val elementText = element.text()
                        val elementSentences = elementText.split(Regex("(?<=[.!?])\\s*")).filter { it.isNotBlank() }
                        if (elementSentences.isNotEmpty()) {
                            plainSentences.addAll(elementSentences)
                            val newHtml = elementSentences.joinToString(" ") { sentence ->
                                "<span id=\"pagevox-sentence-${sentenceCounter++}\">${sentence}</span>"
                            }
                            element.html(newHtml)
                        }
                    }
                }

                if (plainSentences.isNotEmpty()) {
                    Pair(document.outerHtml(), plainSentences.toList())
                } else {
                    Pair<String?, List<String>>(null, emptyList())
                }
            }

            if (htmlResult != null && parsedSentences.isNotEmpty()) {
                // update state on main thread
                modifiedHtmlForWebView = htmlResult
                sentences = parsedSentences
                onFinished()
            } else {
                Log.w("MainViewModel", "No text could be extracted from the page.")
            }
            isLoading.value = false
        }
    }
}

// --- Composables ---

@Composable
fun PageVoxApp(viewModel: MainViewModel, mediaController: MediaController?) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    val urlState = viewModel.url.value
    val homeUrlState = viewModel.homeUrl.value

    DisposableEffect(mediaController) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(currentlyPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $currentlyPlaying")
                isPlaying = currentlyPlaying
            }
        }
        mediaController?.addListener(listener)
        onDispose {
            mediaController?.removeListener(listener)
        }
    }


    val play: () -> Unit = {
        Log.d(TAG, "Play function called")
        if (mediaController != null && mediaController?.isConnected == true) {
            if (mediaController?.isPlaying == true) {
                mediaController?.pause()
            } else if (viewModel.sentences.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putStringArrayList("sentences", ArrayList(viewModel.sentences))
                    putInt("startIndex", viewModel.initialSentenceIndex)
                }
                mediaController?.sendCustomCommand(SessionCommand("playSentences", bundle), bundle)
            } else {
                viewModel.triggerParsing()
            }
        } else {
            Log.w(TAG, "MediaController not ready, aborting playback")
            Toast.makeText(context, "Playback service not ready, please wait.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    val stop: () -> Unit = {
        mediaController?.stop()
    }


    if (urlState.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Scaffold(
            topBar = {
                AddressBar(
                    url = urlState,
                    onUrlChanged = viewModel::onUrlChanged,
                    onGo = {
                        viewModel.loadNewUrl { stop() }
                    },
                    onBack = { webView?.goBack() },
                    onForward = { webView?.goForward() },
                    onHome = {
                        viewModel.onUrlChanged(homeUrlState)
                    }
                )
            },
            bottomBar = {
                PlayerControls(
                    isPlaying = isPlaying,
                    isPlaybackReady = mediaController != null,
                    onPlay = play,
                    onPause = { mediaController?.pause() },
                    onStop = stop,
                    onSettingsClick = { showSettings = true },
                    onTtsSettingsClick = {
                        try {
                            val intent = Intent("com.android.settings.TTS_SETTINGS")
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.e("PageVoxApp", "TTS Settings screen not found", e)
                            try {
                                val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(accessibilityIntent)
                            } catch (e2: ActivityNotFoundException) {
                                Log.e("PageVoxApp", "Accessibility Settings screen also not found", e2)
                                Toast.makeText(context, "Could not open any system settings.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                WebContent(
                    url = urlState,
                    onWebViewReady = { webView = it },
                    parsingTrigger = viewModel.parsingTrigger.value,
                    modifiedHtml = viewModel.modifiedHtmlForWebView,
                    highlightIndex = -1,
                    onHtmlReadyForParsing = { html, baseUrl ->
                        viewModel.parseAndPrepare(html, baseUrl) { play() }
                    },
                    onUrlChangedFromWebView = { newUrl ->
                        viewModel.onUrlChangedFromWebView(newUrl) { stop() }
                    }
                )
                if (viewModel.isLoading.value) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            currentHomeUrl = homeUrlState,
            onDismiss = { showSettings = false },
            onSave = { newHomeUrl: String ->
                viewModel.updateHomeUrl(newHomeUrl)
                showSettings = false
            }
        )
    }
}

@Composable
fun AddressBar(
    url: String,
    onUrlChanged: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var text by remember(url) { mutableStateOf(url) }

    Surface(shadowElevation = 4.dp, modifier = Modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = onForward) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
            }
            IconButton(onClick = onHome) {
                Icon(imageVector = Icons.Filled.Home, contentDescription = "Home")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Enter URL") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    onUrlChanged(text)
                    onGo()
                    focusManager.clearFocus()
                })
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = {
                onUrlChanged(text)
                onGo()
                focusManager.clearFocus()
            }) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go")
            }
        }
    }
}

@Composable
fun WebContent(
    url: String,
    onWebViewReady: (WebView) -> Unit,
    parsingTrigger: Int,
    modifiedHtml: String?,
    highlightIndex: Int,
    onHtmlReadyForParsing: (String, String) -> Unit,
    onUrlChangedFromWebView: (String) -> Unit
) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { onUrlChangedFromWebView(it) }
                }
            }
        }
    }

    LaunchedEffect(url, webView) {
        onWebViewReady(webView)
        webView.loadUrl(url)
    }

    LaunchedEffect(parsingTrigger) {
        if (parsingTrigger > 0) {
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                val unescapedHtml = html.trim().removeSurrounding("\"")
                    .replace("\\u003C", "<").replace("\\n", "\n")
                    .replace("\\t", "\t").replace("\\\"", "\"")
                onHtmlReadyForParsing(unescapedHtml, url)
            }
        }
    }

    LaunchedEffect(modifiedHtml) {
        if (modifiedHtml != null) {
            webView.loadDataWithBaseURL(url, modifiedHtml, "text/html", "UTF-8", url)
        }
    }

    LaunchedEffect(highlightIndex) {
        val script = """
        (function() {
            var oldElement = document.querySelector('.pagevox-highlight');
            if (oldElement) { oldElement.classList.remove('pagevox-highlight'); }
            if ($highlightIndex >= 0) {
                var newElement = document.getElementById('pagevox-sentence-' + $highlightIndex);
                if (newElement) {
                    newElement.classList.add('pagevox-highlight');
                    newElement.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
                }
            }
        })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isPlaybackReady: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSettingsClick: () -> Unit,
    onTtsSettingsClick: () -> Unit
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPlaybackReady) {
                IconButton(
                    onClick = { if (isPlaying) onPause() else onPlay() },
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            }

            IconButton(
                onClick = onStop,
                enabled = isPlaybackReady && isPlaying
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Home Page Settings"
                )
            }

            IconButton(onClick = onTtsSettingsClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Text-to-Speech Settings"
                )
            }
        }
    }
}
