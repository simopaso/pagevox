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

// A constant for logging purposes, making it easy to filter logs for this activity.
private const val TAG = "MainActivity"

// --- Data Persistence (Jetpack DataStore) ---
// Extension property to provide a singleton instance of DataStore for the application context.
// This is used to store user preferences persistently.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * A data class representing the user's preferences.
 * This makes it easy to pass user settings around the app.
 *
 * @property lastUrl The last URL the user was visiting.
 * @property lastSentenceIndex The index of the last sentence that was being read.
 * @property homeUrl The user's chosen home page URL.
 */
data class UserPreferences(
    val lastUrl: String,
    val lastSentenceIndex: Int,
    val homeUrl: String
)

/**
 * A repository class for managing user settings.
 * It abstracts the data source (DataStore) from the rest of the app.
 *
 * @property context The application context, used to access the DataStore.
 */
class SettingsRepository(private val context: Context) {
    // An object to hold the keys for the preferences.
    // Using this object prevents typos in key names.
    private object PreferencesKeys {
        val LAST_URL = stringPreferencesKey("last_url")
        val LAST_SENTENCE_INDEX = intPreferencesKey("last_sentence_index")
        val HOME_URL = stringPreferencesKey("home_url")
    }

    /**
     * A Flow that emits the user's preferences whenever they change.
     * This allows the UI to reactively update when settings are modified.
     */
    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            // If an error occurs while reading the data, log it.
            if (exception is IOException) {
                Log.e("SettingsRepository", "Error reading preferences.", exception)
            } else {
                // For other types of exceptions, re-throw them.
                throw exception
            }
        }
        .map { preferences ->
            // Map the raw Preferences object to a UserPreferences object.
            // Provide default values if the preferences are not yet set.
            val lastUrl = preferences[PreferencesKeys.LAST_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"
            val lastSentenceIndex = preferences[PreferencesKeys.LAST_SENTENCE_INDEX] ?: 0
            val homeUrl = preferences[PreferencesKeys.HOME_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"
            UserPreferences(lastUrl, lastSentenceIndex, homeUrl)
        }

    /**
     * Suspended function to update the last visited URL in the DataStore.
     *
     * @param url The new URL to save.
     */
    suspend fun updateLastUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_URL] = url
        }
    }

    /**
     * Suspended function to update the last sentence index in the DataStore.
     *
     * @param index The new sentence index to save.
     */
    suspend fun updateLastSentenceIndex(index: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SENTENCE_INDEX] = index
        }
    }

    /**
     * Suspended function to update the home URL in the DataStore.
     *
     * @param url The new home URL to save.
     */
    suspend fun updateHomeUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HOME_URL] = url
        }
    }
}

// --- Main Activity ---
/**
 * The main and only activity of the application.
 * It hosts the Jetpack Compose UI and manages the connection to the PlaybackService.
 */
class MainActivity : ComponentActivity() {

    // A mutable state to hold the MediaController instance.
    // The MediaController is used to control media playback.
    private var mediaController: MediaController? by mutableStateOf(null)

    // A ServiceConnection object to manage the connection to the PlaybackService.
    private val connection = object : ServiceConnection {
        /**
         * Called when the service is connected.
         * It creates a MediaController to communicate with the Media3 session.
         */
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

        /**
         * Called when the service is disconnected.
         * It clears the MediaController instance.
         */
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            mediaController = null
        }
    }

    // An ActivityResultLauncher for requesting the notification permission.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Show a toast message indicating whether the permission was granted.
        if (isGranted) {
            Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The entry point of the activity.
     * It sets up the UI and requests necessary permissions.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge display for a more immersive UI.
        enableEdgeToEdge()
        // Request the notification permission if needed.
        askNotificationPermission()

        // Set the content of the activity to be a Jetpack Compose UI.
        setContent {
            PageVoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Create an instance of the SettingsRepository.
                    val settingsRepository = SettingsRepository(applicationContext)
                    // The main composable of the app, providing the ViewModel and MediaController.
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

    /**
     * Called when the activity is becoming visible to the user.
     * It starts and binds to the PlaybackService.
     */
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        Intent(this, PlaybackService::class.java).also { intent ->
            // Start the service as a foreground service on Android Oreo and above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // Bind to the service to get a communication channel.
            bindService(intent, connection, BIND_AUTO_CREATE)
            Log.d(TAG, "Service bound")
        }
    }

    /**
     * Called when the activity is no longer visible to the user.
     * It releases the MediaController and unbinds from the PlaybackService.
     */
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        // Release the MediaController to free up resources.
        mediaController?.release()
        mediaController = null
        // Unbind from the service.
        unbindService(connection)
        Log.d(TAG, "Service unbound")
    }

    /**
     * Checks for and requests the notification permission on Android 13 (Tiramisu) and above.
     */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Launch the permission request.
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

// --- ViewModel ---
/**
 * A factory for creating instances of MainViewModel.
 * This is needed because MainViewModel has a dependency on SettingsRepository.
 *
 * @property settingsRepository The repository for user settings.
 */
class MainViewModelFactory(
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Create and return a new instance of MainViewModel.
            return MainViewModel(settingsRepository) as T
        }
        // Throw an exception if the modelClass is unknown.
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * The ViewModel for the main screen.
 * It holds the UI state and handles the business logic.
 *
 * @property settingsRepository The repository for user settings.
 */
class MainViewModel(
    val settingsRepository: SettingsRepository
) : ViewModel() {
    // A list of sentences extracted from the web page.
    var sentences by mutableStateOf<List<String>>(emptyList())
        private set

    // The current URL being displayed.
    val url = mutableStateOf("")
    // The user's home page URL.
    val homeUrl = mutableStateOf("")

    // A flag to indicate if the app is currently parsing a web page.
    val isLoading = mutableStateOf(false)
    // The modified HTML of the web page, with sentences wrapped in spans.
    var modifiedHtmlForWebView by mutableStateOf<String?>(null)
    // A trigger to start the parsing process.
    val parsingTrigger = mutableStateOf(0)

    // The index of the sentence to start playback from.
    var initialSentenceIndex by mutableStateOf(0)
        private set

    // An init block to load the initial state from the SettingsRepository.
    init {
        viewModelScope.launch {
            val prefs = settingsRepository.userPreferencesFlow.first()
            url.value = prefs.lastUrl
            homeUrl.value = prefs.homeUrl
            initialSentenceIndex = prefs.lastSentenceIndex
        }
    }

    /**
     * Called when the URL is changed in the address bar.
     *
     * @param newUrl The new URL.
     */
    fun onUrlChanged(newUrl: String) {
        url.value = newUrl
    }

    /**
     * Called when the URL changes from within the WebView (e.g., by clicking a link).
     *
     * @param newUrl The new URL.
     * @param stopPlayback A function to stop the current playback.
     */
    fun onUrlChangedFromWebView(newUrl: String, stopPlayback: () -> Unit) {
        if (url.value != newUrl) {
            url.value = newUrl
            stopPlayback()
            viewModelScope.launch {
                settingsRepository.updateLastUrl(newUrl)
                settingsRepository.updateLastSentenceIndex(0)
                initialSentenceIndex = 0
            }
            // Reset the state for the new page.
            modifiedHtmlForWebView = null
            sentences = emptyList()
        }
    }

    /**
     * Loads a new URL entered by the user.
     *
     * @param stopPlayback A function to stop the current playback.
     */
    fun loadNewUrl(stopPlayback: () -> Unit) {
        stopPlayback()
        viewModelScope.launch {
            settingsRepository.updateLastUrl(url.value)
            settingsRepository.updateLastSentenceIndex(0)
            initialSentenceIndex = 0
        }
        // Reset the state for the new page.
        sentences = emptyList()
    }

    /**
     * Updates the user's home URL.
     *
     * @param newHomeUrl The new home URL.
     */
    fun updateHomeUrl(newHomeUrl: String) {
        viewModelScope.launch {
            settingsRepository.updateHomeUrl(newHomeUrl)
            homeUrl.value = newHomeUrl
        }
    }

    /**
     * Triggers the parsing of the web page.
     */
    fun triggerParsing() {
        parsingTrigger.value++
    }

    /**
     * Parses the HTML of a web page, extracts sentences, and prepares the HTML for highlighting.
     *
     * @param rawContent The raw HTML of the web page.
     * @param baseUrl The base URL of the web page.
     * @param onFinished A callback function to be called when the process is finished.
     */
    fun parseAndPrepare(rawContent: String, baseUrl: String, onFinished: () -> Unit) {
        viewModelScope.launch {
            isLoading.value = true
            modifiedHtmlForWebView = null

            // Perform the parsing on a background thread.
            val (htmlResult, parsedSentences) = withContext(Dispatchers.Default) {
                val document: Document = Jsoup.parse(rawContent, baseUrl)
                // Select the elements that are likely to contain the main content.
                var contentElements = document.select("p, h1, h2, h3, h4, h5, h6")
                if (contentElements.isEmpty()) contentElements = document.select("pre")

                val plainSentences = mutableListOf<String>()
                var sentenceCounter = 0
                if (contentElements.isNotEmpty()) {
                    // Inject some CSS for highlighting the currently spoken sentence.
                    document.head().append(
                        """<style>
                        .pagevox-highlight { background-color: #FFDE03 !important; color: black !important; transition: background-color 0.3s ease-in-out; }
                        body { font-family: sans-serif; line-height: 1.6; padding: 1em; }
                        pre { white-space: pre-wrap; word-wrap: break-word; }
                       </style>""".trimIndent()
                    )
                    // Iterate over the content elements.
                    contentElements.forEach { element ->
                        val elementText = element.text()
                        // Split the text into sentences.
                        val elementSentences = elementText.split(Regex("(?<=[.!?])\\s*")).filter { it.isNotBlank() }
                        if (elementSentences.isNotEmpty()) {
                            plainSentences.addAll(elementSentences)
                            // Wrap each sentence in a span with a unique ID.
                            val newHtml = elementSentences.joinToString(" ") { sentence ->
                                "<span id=\"pagevox-sentence-${sentenceCounter++}\">${sentence}</span>"
                            }
                            // Replace the original content of the element with the modified HTML.
                            element.html(newHtml)
                        }
                    }
                }

                // Return the modified HTML and the list of sentences.
                if (plainSentences.isNotEmpty()) {
                    Pair(document.outerHtml(), plainSentences.toList())
                } else {
                    Pair<String?, List<String>>(null, emptyList())
                }
            }

            // Update the state on the main thread.
            if (htmlResult != null && parsedSentences.isNotEmpty()) {
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

/**
 * The main composable of the app.
 * It sets up the screen layout and handles user interactions.
 *
 * @param viewModel The ViewModel for the main screen.
 * @param mediaController The MediaController for controlling playback.
 */
@Composable
fun PageVoxApp(viewModel: MainViewModel, mediaController: MediaController?) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    val urlState = viewModel.url.value
    val homeUrlState = viewModel.homeUrl.value

    // A DisposableEffect to listen for playback state changes.
    DisposableEffect(mediaController) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(currentlyPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $currentlyPlaying")
                isPlaying = currentlyPlaying
            }
        }
        mediaController?.addListener(listener)
        // Remove the listener when the composable is disposed.
        onDispose {
            mediaController?.removeListener(listener)
        }
    }

    // A function to start or resume playback.
    val play: () -> Unit = {
        Log.d(TAG, "Play function called")
        if (mediaController != null && mediaController?.isConnected == true) {
            if (mediaController?.isPlaying == true) {
                // If already playing, pause.
                mediaController?.pause()
            } else if (viewModel.sentences.isNotEmpty()) {
                // If not playing and sentences are available, start playback.
                val bundle = Bundle().apply {
                    putStringArrayList("sentences", ArrayList(viewModel.sentences))
                    putInt("startIndex", viewModel.initialSentenceIndex)
                }
                mediaController?.sendCustomCommand(SessionCommand("playSentences", bundle), bundle)
            } else {
                // If no sentences are available, trigger parsing.
                viewModel.triggerParsing()
            }
        } else {
            Log.w(TAG, "MediaController not ready, aborting playback")
            Toast.makeText(context, "Playback service not ready, please wait.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    // A function to stop playback.
    val stop: () -> Unit = {
        mediaController?.stop()
    }

    // Show a loading indicator while the initial URL is being loaded.
    if (urlState.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        // The main screen layout.
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
                        // Open the system's Text-to-Speech settings.
                        try {
                            val intent = Intent("com.android.settings.TTS_SETTINGS")
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.e("PageVoxApp", "TTS Settings screen not found", e)
                            // As a fallback, try to open the accessibility settings.
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
                // The WebView for displaying the web page.
                WebContent(
                    url = urlState,
                    onWebViewReady = { webView = it },
                    parsingTrigger = viewModel.parsingTrigger.value,
                    modifiedHtml = viewModel.modifiedHtmlForWebView,
                    highlightIndex = -1, // TODO: This should be updated during playback
                    onHtmlReadyForParsing = { html, baseUrl ->
                        viewModel.parseAndPrepare(html, baseUrl) { play() }
                    },
                    onUrlChangedFromWebView = { newUrl ->
                        viewModel.onUrlChangedFromWebView(newUrl) { stop() }
                    }
                )
                // Show a loading indicator while parsing.
                if (viewModel.isLoading.value) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    // Show the settings screen as a dialog.
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

/**
 * A composable for the address bar at the top of the screen.
 *
 * @param url The current URL.
 * @param onUrlChanged A callback for when the URL text is changed.
 * @param onGo A callback for when the user wants to navigate to the entered URL.
 * @param onBack A callback for navigating back.
 * @param onForward A callback for navigating forward.
 * @param onHome A callback for navigating to the home page.
 */
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

/**
 * A composable for displaying the web content in a WebView.
 *
 * @param url The URL to display.
 * @param onWebViewReady A callback for when the WebView is ready.
 * @param parsingTrigger A trigger to start parsing the page.
 * @param modifiedHtml The modified HTML with highlighted sentences.
 * @param highlightIndex The index of the sentence to highlight.
 * @param onHtmlReadyForParsing A callback for when the HTML is ready to be parsed.
 * @param onUrlChangedFromWebView A callback for when the URL changes from within the WebView.
 */
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
    // Create and remember a WebView instance.
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                // Notify the app when a page has finished loading.
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { onUrlChangedFromWebView(it) }
                }
            }
        }
    }

    // A LaunchedEffect to load the URL when it changes.
    LaunchedEffect(url, webView) {
        onWebViewReady(webView)
        webView.loadUrl(url)
    }

    // A LaunchedEffect to extract the HTML when the parsing is triggered.
    LaunchedEffect(parsingTrigger) {
        if (parsingTrigger > 0) {
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                // The returned HTML is a JSON string, so it needs to be unescaped.
                val unescapedHtml = html.trim().removeSurrounding("\"")
                    .replace("\\u003C", "<").replace("\\n", "\n")
                    .replace("\\t", "\t").replace("\\\"", "\"")
                onHtmlReadyForParsing(unescapedHtml, url)
            }
        }
    }

    // A LaunchedEffect to load the modified HTML when it's available.
    LaunchedEffect(modifiedHtml) {
        if (modifiedHtml != null) {
            webView.loadDataWithBaseURL(url, modifiedHtml, "text/html", "UTF-8", url)
        }
    }

    // A LaunchedEffect to highlight the currently spoken sentence.
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

    // Use the AndroidView composable to embed the WebView in the Compose UI.
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

/**
 * A composable for the player controls at the bottom of the screen.
 *
 * @param isPlaying Whether playback is currently active.
 * @param isPlaybackReady Whether the playback service is ready.
 * @param onPlay A callback for starting or resuming playback.
 * @param onPause A callback for pausing playback.
 * @param onStop A callback for stopping playback.
 * @param onSettingsClick A callback for opening the settings screen.
 * @param onTtsSettingsClick A callback for opening the system's TTS settings.
 */
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
                // Show a loading indicator if the playback service is not ready.
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
// A new composable function needs to be added for the settings screen.
// This will be a simple dialog with a text field for the home URL and save/cancel buttons.
@Composable
fun SettingsScreen(
    currentHomeUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var homeUrl by remember { mutableStateOf(currentHomeUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Set your home page URL:")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = homeUrl,
                    onValueChange = { homeUrl = it },
                    label = { Text("Home URL") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(homeUrl) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}