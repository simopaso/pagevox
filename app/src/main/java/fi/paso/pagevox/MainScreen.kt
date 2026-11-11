package fi.paso.pagevox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate

// Voluntary support for development. Unlocks nothing in the app and is never
// required for any feature — it is a link out, not a purchase.
// Deliberately without locale/country parameters: those would force the payment
// page into Finnish for every visitor, and PageVox's audience isn't Finnish.
private const val SUPPORT_URL = "https://paypal.me/SimoPaso"

// Where the layout switches from "phone" to "there is room for more than the
// page". 600dp is the Material medium-width breakpoint: an unfolded foldable,
// a tablet, and a phone in landscape all clear it, a folded phone does not.
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

// Below this the phone toolbar's Play button drops its wide form, so the six
// controls still fit on a small screen or with a large display size.
private val COMPACT_TOOLBAR_WIDTH = 360.dp

// The speed presets' button group: as wide as a menu goes, so five fit.
private val SPEED_GROUP_WIDTH = 280.dp

// How long Play waits for a page on its way before acting on what's there.
private const val NAVIGATION_WAIT_MS = 15_000L
private const val NAVIGATION_POLL_MS = 150L
private const val ADDRESS_SETTLE_MS = 450L
// Head start for a navigation to be under way before the place preview looks at it.
private const val PLACE_PREVIEW_DELAY_MS = 600L

@Composable
fun MainScreen(viewModel: MainViewModel, controller: MediaController?) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showClearSiteData by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    // When a navigation was asked for, while its page hasn't replaced the old one.
    // A time and not a flag, because some never finish: a link that turns out to
    // be a download commits nothing, and must not leave taps ignored for good.
    var navigationPendingSince by remember { mutableStateOf<Long?>(null) }
    fun navigationPending() = navigationPendingSince?.let {
        SystemClock.uptimeMillis() - it < NAVIGATION_WAIT_MS
    } == true
    // A Play press is waiting for the page; further presses add nothing.
    var playStarting by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    val context = LocalContext.current

    // Backup and restore go through the system file picker: no storage
    // permission needed, and the user decides where the file lives — Drive,
    // Downloads, a USB stick.
    // LocalResources rather than context.getString: it tracks configuration
    // changes, which matters here because this activity handles most of them
    // itself instead of being recreated (see configChanges in the manifest).
    val resources = LocalResources.current
    fun toast(message: Int) =
        Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportLibrary(uri, appVersion) { ok ->
                toast(if (ok) R.string.export_done else R.string.export_failed)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.readImport(uri) { toast(R.string.import_invalid) }
    }

    // Back navigates the WebView while there's page history; once there isn't,
    // intercept it to confirm before exiting instead of silently closing.
    BackHandler {
        when {
            // A side panel is the shallowest thing on screen, so it goes first.
            // (In the compact layout the library is a bottom sheet, which
            // handles its own back press before this ever runs.)
            showLibrary || showContents -> {
                showLibrary = false
                showContents = false
            }
            canGoBack -> webView?.goBack()
            else -> showExitConfirm = true
        }
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
        // Moving to the page the player is already reading isn't navigating
        // away from it — that is how the screen follows the player into the
        // next chapter file, and stopping here would end the very read it's
        // following.
        val toPlayingPage = PlaybackDataRepository.pageUrl?.let {
            normalizeUrlForCompare(it) == normalizeUrlForCompare(curr)
        } == true
        if (prev != null && prev != curr && !toPlayingPage) {
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
            // What the user has asked for, not what is audible this instant: the
            // player is briefly "not playing" while it buffers, which showed Play
            // right after a press and made the next press a no-op. Likewise
            // during the gap while the next chapter is fetched.
            private fun update() {
                isPlaying = controller?.let {
                    it.playWhenReady && it.playbackState != Player.STATE_IDLE &&
                        it.playbackState != Player.STATE_ENDED
                } == true
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()
            override fun onPlaybackStateChanged(playbackState: Int) = update()
        }
        controller?.addListener(listener)
        if (controller != null) {
            isPlaying = controller.playWhenReady && controller.playbackState != Player.STATE_IDLE &&
                controller.playbackState != Player.STATE_ENDED
        }
        onDispose { controller?.removeListener(listener) }
    }

    // Playback Logic
    //
    // [freshPage] is true when the sentences were extracted just now, for this
    // press. That page must be started explicitly at its start index: deciding
    // from the controller's playbackState instead is unreliable right after a
    // stopPlayback, because the controller learns the new state asynchronously
    // and may still report the old page as READY — a plain play() would then
    // start the new page from sentence 0. The controller is read live for the
    // same reason this can run late: it is called from the extraction callback.
    fun togglePlay(freshPage: Boolean = false) {
        val controller = latestController.value ?: return
        if (freshPage) {
            val args = Bundle().apply {
                putInt("startIndex", viewModel.getStartIndex())
                putBoolean("resume", true)
            }
            controller.sendCustomCommand(SessionCommand("playSentences", Bundle.EMPTY), args)
            return
        }
        if (isPlaying) {
            controller.pause()
        } else {
            // If already prepared/playing silent audio, just play.
            // If we need to load new content (e.g. fresh start), send command.
            // Simple heuristic: if we have sentences and player is IDLE, we start fresh.
            if (controller.playbackState == Player.STATE_IDLE && viewModel.sentences.isNotEmpty()) {
                val args = Bundle().apply {
                    putInt("startIndex", viewModel.getStartIndex())
                    putBoolean("resume", true)
                }
                controller.sendCustomCommand(SessionCommand("playSentences", Bundle.EMPTY), args)
            } else {
                controller.play()
            }
        }
    }

    /**
     * Bring the app's idea of "the current page" up to date with what the
     * WebView is actually showing, before a Play or a tap acts on it.
     *
     * The app hears about a navigation only from onPageFinished, and on an HTML
     * page that waits for every subresource — ads, images, trackers — often
     * seconds after the text is on screen. A Play pressed in that window acted
     * on the *previous* page's sentences; then onPageFinished arrived, the
     * navigation was recognised, and the stop it triggers (the LaunchedEffect
     * above) ended the read that had only just started. So the first press did
     * nothing and the second worked. Plain-text files finish loading at once,
     * which is why only HTML pages showed it.
     *
     * WebViewContainer now reports navigations from onPageStarted too, which
     * closes that window at the source; this stays as the backstop for any
     * navigation that reaches the screen without passing through it.
     */
    fun syncToShownPage() {
        val shown = webView?.url ?: return
        if (viewModel.url.isEmpty() || shown.startsWith("data:")) return
        if (normalizeUrlForCompare(shown) == normalizeUrlForCompare(viewModel.url)) return
        Log.i("MainScreen", "Page changed before it finished loading; syncing to $shown")
        val playerOnShown = PlaybackDataRepository.pageUrl?.let {
            normalizeUrlForCompare(it) == normalizeUrlForCompare(shown)
        } == true
        // Stop the old page's read now, *before* the new page is started, and
        // record the navigation as already seen, so the LaunchedEffect doesn't
        // send a stop of its own later — after Play has started the new page.
        if (!playerOnShown) {
            latestController.value?.sendCustomCommand(
                SessionCommand("stopPlayback", Bundle.EMPTY), Bundle.EMPTY
            )
        }
        lastSeenUrl = shown
        viewModel.loadUrl(shown)
    }

    /**
     * Extract the shown page's text once it has settled, hand it to the ViewModel,
     * then [onReady]. See [extractWhenSettled].
     */
    fun extractShownPage(onReady: () -> Unit) {
        val view = webView
        if (view == null) {
            playStarting = false
            viewModel.isLoading = false
            return
        }
        viewModel.isLoading = true
        // Stops polling if the WebView is replaced: nothing may call a view whose
        // renderer has died. See onWebViewGone.
        extractWhenSettled(view, viewModel.readerMode, isActive = { webView === view }) { lang, blocks, image, next ->
            playStarting = false
            viewModel.isLoading = false
            viewModel.onTextsExtracted(lang, blocks, image, next, onReady)
        }
    }

    /**
     * Run [action] once any navigation under way has delivered its page. Until it
     * commits the old document is still in the WebView, so a press in that gap
     * read the previous page's text and filed it, and its reading position, under
     * the new address; or, after a tapped link, restarted the old page only for
     * the arriving page to stop it again. The address check covers the moment
     * before a navigation has been reported as pending.
     */
    fun whenPageReady(action: () -> Unit, waitedMs: Long = 0) {
        val view = webView
        val addressDiffers = view?.url?.let {
            normalizeUrlForCompare(it) != normalizeUrlForCompare(viewModel.url)
        } == true
        val keepWaiting = navigationPending() || (addressDiffers && waitedMs < ADDRESS_SETTLE_MS)
        if (view == null || !keepWaiting) {
            navigationPendingSince = null
            action()
        } else {
            view.postDelayed({ whenPageReady(action, waitedMs + NAVIGATION_POLL_MS) }, NAVIGATION_POLL_MS)
        }
    }

    // Show where reading stopped as soon as a page you had started is open, rather
    // than "1 / N" and no highlight until Play is pressed. A page's text is
    // otherwise only read from it when Play or a tap asks for it, so this reads it
    // early, and only for pages with a saved place: a page that's merely being
    // looked at is left alone (reading it opens its folded sections). The result is
    // the same as pressing Play and pausing at once, so everything that follows
    // works as it does then.
    //
    // Not while anything is playing: the service reads its sentences out of the
    // same repository this fills, and a page opened over a read still going (a
    // shared link, say) must not replace them under it.
    LaunchedEffect(viewModel.url, webView, viewModel.readerMode) {
        val view = webView ?: return@LaunchedEffect
        val pageUrl = viewModel.url
        if (pageUrl.isEmpty()) return@LaunchedEffect
        fun shown() = view.url?.let { normalizeUrlForCompare(it) == normalizeUrlForCompare(pageUrl) } == true
        fun wanted() = webView === view && viewModel.url == pageUrl && viewModel.hasPlaceToShow &&
            !playStarting && latestController.value?.playWhenReady == false
        delay(PLACE_PREVIEW_DELAY_MS)
        var waited = 0L
        while (waited < NAVIGATION_WAIT_MS && (navigationPending() || view.progress < 100 || !shown())) {
            delay(NAVIGATION_POLL_MS)
            waited += NAVIGATION_POLL_MS
        }
        if (!shown() || !wanted()) return@LaunchedEffect
        extractWhenSettled(view, viewModel.readerMode, isActive = { wanted() }) { lang, blocks, image, next ->
            viewModel.onTextsExtracted(lang, blocks, image, next, {}, quiet = true)
        }
    }

    // ── Adaptive layout ───────────────────────────────────────────────────
    //
    // Narrow: address bar on top, scrubber and control bar along the bottom.
    // Wide (an unfolded foldable, a tablet, a phone in landscape): the same
    // controls move into a rail down the leading edge, which hands the page back
    // the full height of the window, and the trailing edge can carry a panel —
    // the library, or the page's contents — beside the page instead of on top
    // of it.
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowWidth = with(density) { windowInfo.containerSize.width.toDp() }
    val wide = windowWidth >= WIDE_LAYOUT_MIN_WIDTH
    // Enough for a list of page titles, never so much that the page stops being
    // readable: at the breakpoint itself the page still keeps around 250dp.
    val panelWidth = (windowWidth * 0.38f).coerceIn(280.dp, 380.dp)

    // The two panels are mutually exclusive. Between 600 and 900dp there is room
    // for the page and one of them, not both.
    fun openPanel(library: Boolean = false, contents: Boolean = false) {
        showLibrary = library
        showContents = contents
    }

    // Folding, unfolding or rotating moves the WebView between two entirely
    // different call sites. Left to itself Compose would treat that as a new
    // WebView: the page would reload, the scroll position would be lost and the
    // extracted sentences thrown away, mid-sentence. movableContentOf relocates
    // the *same* composition — the same WebView instance with it — so the fold
    // is invisible to the reader. Everything captured below is either stable
    // (the ViewModel) or read live (latestController), so nothing goes stale
    // across the move.
    val pageContent = remember {
        movableContentOf {
            if (viewModel.url.isNotEmpty()) {
                WebViewContainer(
                    url = viewModel.url,
                    currentSentence = viewModel.currentSentenceText,
                    onWebViewReady = { webView = it },
                    onWebViewGone = {
                        // Its extraction callback will never come, and a Play press
                        // waiting on one must not be left waiting.
                        webView = null
                        playStarting = false
                        viewModel.isLoading = false
                    },
                    onUrlChange = { viewModel.onPageUrlSettled(it) },
                    onTitleChange = { pageUrl, title -> viewModel.onPageTitle(pageUrl, title) },
                    onCanGoBackChanged = { canGoBack = it },
                    onNavigationPending = { navigationPendingSince = if (it) SystemClock.uptimeMillis() else null },
                    onTextTapped = { clickedText ->
                        // The tap was on the page that's on its way out.
                        if (navigationPending()) return@WebViewContainer
                        val seekAndPlay = {
                            // A tap that can't be matched to a sentence must NOT
                            // fall back to sentence 0 — that would jump reading to
                            // the top of the page instead of the tapped spot.
                            val idx = viewModel.findSentenceIndex(clickedText)
                            if (idx >= 0) {
                                val args = Bundle().apply { putInt("startIndex", idx) }
                                latestController.value?.sendCustomCommand(
                                    SessionCommand("playSentences", Bundle.EMPTY), args
                                )
                            }
                        }
                        // A tap is on the page as shown, even if it hasn't
                        // finished loading yet — see syncToShownPage.
                        syncToShownPage()
                        if (viewModel.sentences.isEmpty()) {
                            extractShownPage({ seekAndPlay() })
                        } else {
                            seekAndPlay()
                        }
                    },
                    forceDark = viewModel.forceDarkWeb,
                    textZoom = viewModel.textZoom,
                    readerMode = viewModel.readerMode,
                    followAlong = viewModel.followAlong
                )
            }
        }
    }

    // Scrubber and controls are emitted from one place each and called from
    // whichever arrangement is on screen, so the two layouts can't drift apart.
    val scrubber: @Composable () -> Unit = {
        HorizontalReadingScrubber(
            totalSentences = viewModel.sentences.size,
            currentIndex = viewModel.currentHighlightIndex,
            sentences = viewModel.sentences,
            sectionStarts = viewModel.sectionStarts,
            sectionTitleAt = { viewModel.sectionTitleAt(it) },
            onSeekChange = { idx ->
                // Live-scroll the WebView to the sentence under the thumb.
                val text = viewModel.sentences.getOrNull(idx)
                if (text != null) {
                    val escaped = JSONObject.quote(text)
                    webView?.evaluateJavascript(
                        "(function(){ window.find($escaped, false, false, true); })();",
                        null
                    )
                }
            },
            onSeekFinished = { idx ->
                // Commit: start playback from this position (the service
                // broadcasts updateIndex, which drives the thumb + highlight).
                // window.find above works by selecting the text it finds, and that
                // selection would otherwise stay on the page after the thumb is let go.
                webView?.evaluateJavascript("window.getSelection().removeAllRanges();", null)
                val args = Bundle().apply { putInt("startIndex", idx) }
                controller?.sendCustomCommand(
                    SessionCommand("playSentences", Bundle.EMPTY), args
                )
            },
            playing = isPlaying
        )
    }

    val controls: @Composable (Boolean) -> Unit = { railLayout ->
        val parentUrl = remember(viewModel.url) { parentFolderUrl(viewModel.url) }
        PlaybackControls(
            wide = railLayout,
            libraryOpen = showLibrary,
            contentsOpen = showContents,
            onOpenContents = { openPanel(contents = !showContents) },
            isPlaying = isPlaying,
            busy = viewModel.isLoading,
            hasSentences = viewModel.sentences.isNotEmpty(),
            speechRate = viewModel.speechRate,
            readerMode = viewModel.readerMode,
            followAlong = viewModel.followAlong,
            onToggleFollowAlong = { viewModel.toggleFollowAlong() },
            continueToNextFile = viewModel.continueToNextFile,
            onToggleContinueToNextFile = { viewModel.toggleContinueToNextFile() },
            textZoom = viewModel.textZoom,
            canGoBack = canGoBack,
            canGoUp = parentUrl != null,
            onBack = {
                if (webView?.canGoBack() == true) navigationPendingSince = SystemClock.uptimeMillis()
                webView?.goBack()
            },
            onForward = {
                if (webView?.canGoForward() == true) navigationPendingSince = SystemClock.uptimeMillis()
                webView?.goForward()
            },
            onUp = { parentUrl?.let { viewModel.loadUrl(it) } },
            onHome = { viewModel.loadUrl(viewModel.homeUrl) },
            onOpenManual = { viewModel.loadUrl(manualUrl()) },
            onOpenLibrary = {
                if (railLayout) openPanel(library = !showLibrary) else showLibrary = true
            },
            onPlayPause = {
                // While something plays the button shows Pause, and pausing is
                // what it does — whatever page is showing. Otherwise act on the
                // page actually on screen.
                if (isPlaying) {
                    togglePlay()
                } else if (!playStarting) {
                    playStarting = true
                    viewModel.isLoading = true
                    whenPageReady({
                        syncToShownPage()
                        if (viewModel.sentences.isEmpty()) {
                            extractShownPage({ togglePlay(freshPage = true) })
                        } else {
                            playStarting = false
                            viewModel.isLoading = false
                            togglePlay()
                        }
                    })
                }
            },
            onSkipPrevious = {
                controller?.sendCustomCommand(SessionCommand("skipPrevious", Bundle.EMPTY), Bundle.EMPTY)
            },
            onSkipNext = {
                controller?.sendCustomCommand(SessionCommand("skipNext", Bundle.EMPTY), Bundle.EMPTY)
            },
            onSkipPreviousSection = {
                controller?.sendCustomCommand(SessionCommand("skipPreviousSection", Bundle.EMPTY), Bundle.EMPTY)
            },
            onSkipNextSection = {
                controller?.sendCustomCommand(SessionCommand("skipNextSection", Bundle.EMPTY), Bundle.EMPTY)
            },
            onTextSmaller = { viewModel.decreaseTextSize() },
            onTextLarger = { viewModel.increaseTextSize() },
            onSetSpeed = { viewModel.applySpeechRate(it) },
            onToggleReader = {
                viewModel.toggleReaderMode()
                // Sentences were cleared; stop any in-progress read so the
                // next play re-extracts with the new scoping.
                controller?.sendCustomCommand(SessionCommand("stopPlayback", Bundle.EMPTY), Bundle.EMPTY)
            },
            onSettings = { showSettings = true },
            onSupportDevelopment = { openOutsideThisApp(context, SUPPORT_URL) },
            onTtsSettings = {
                try {
                    val intent = Intent("com.android.settings.TTS_SETTINGS")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    webView?.context?.startActivity(intent)
                } catch (e: Exception) {}
            }
        )
    }

    Scaffold(
        topBar = {
            AddressBar(
                url = viewModel.url,
                history = viewModel.history,
                isBookmarked = viewModel.isCurrentBookmarked,
                onGo = { viewModel.submitAddressBarInput(it) },
                onToggleBookmark = { viewModel.toggleBookmark() }
            )
        },
        bottomBar = {
            if (!wide) {
                Column(Modifier.navigationBarsPadding()) {
                    scrubber()
                    controls(false)
                }
            }
        }
    ) { padding ->
        if (wide) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                controls(true)
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        pageContent()
                    }
                    scrubber()
                }
                if (showLibrary || showContents) {
                    SidePanel(
                        title = stringResource(
                            if (showContents) R.string.panel_contents else R.string.menu_library
                        ),
                        onClose = { openPanel() },
                        modifier = Modifier.width(panelWidth)
                    ) {
                        if (showContents) {
                            ContentsPanel(
                                sentences = viewModel.sentences,
                                sectionStarts = viewModel.sectionStarts,
                                sectionTitleAt = { viewModel.sectionTitleAt(it) },
                                currentIndex = viewModel.currentHighlightIndex,
                                remainingMsFrom = { viewModel.remainingMsFrom(it) },
                                speechRate = viewModel.speechRate,
                                onSeek = { idx ->
                                    val args = Bundle().apply { putInt("startIndex", idx) }
                                    controller?.sendCustomCommand(
                                        SessionCommand("playSentences", Bundle.EMPTY), args
                                    )
                                }
                            )
                        } else {
                            LibraryContent(
                                continueListening = viewModel.continueListening,
                                bookmarks = viewModel.bookmarks,
                                history = viewModel.history,
                                speechRate = viewModel.speechRate,
                                onOpen = { pageUrl -> viewModel.loadUrl(pageUrl) },
                                onRemoveBookmark = { viewModel.removeBookmark(it) },
                                onClearHistory = { viewModel.clearHistory() },
                                fillHeight = true
                            )
                        }
                    }
                }
            }
        } else {
            // The loading animation is on the play button now, where the press was.
            Box(Modifier.padding(padding).fillMaxSize()) {
                pageContent()
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            currentHomeUrl = viewModel.homeUrl,
            forceDarkWeb = viewModel.forceDarkWeb,
            onToggleForceDarkWeb = { viewModel.updateForceDarkWeb(it) },
            selectedVoice = viewModel.selectedVoice,
            onSelectVoice = { viewModel.updateSelectedVoice(it) },
            onShowLicenses = { showLicenses = true },
            onClearSiteData = { showClearSiteData = true },
            onExportLibrary = { exportLauncher.launch("pagevox-backup-${LocalDate.now()}.json") },
            onImportLibrary = {
                // Several document providers label a .json file as a generic
                // binary or plain text, and would hide it behind a JSON-only
                // filter. The contents are validated on the way in regardless.
                importLauncher.launch(
                    arrayOf("application/json", "application/octet-stream", "text/plain")
                )
            },
            onDismiss = { showSettings = false },
            onSave = { newUrl: String ->
                viewModel.updateHomeUrl(newUrl)
                showSettings = false
            }
        )
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }

    if (showClearSiteData) {
        AlertDialog(
            onDismissRequest = { showClearSiteData = false },
            title = { Text(stringResource(R.string.clear_site_data_title)) },
            text = { Text(stringResource(R.string.clear_site_data_message)) },
            confirmButton = {
                TextButton(onClick = {
                    clearSiteData(webView)
                    showClearSiteData = false
                    showSettings = false
                }) { Text(stringResource(R.string.clear_site_data_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearSiteData = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    viewModel.pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.import_confirm_message))
                    Spacer(Modifier.height(12.dp))
                    // What's in the file, so picking the wrong backup is caught
                    // before it overwrites anything.
                    Text(
                        stringResource(
                            R.string.import_confirm_counts,
                            backup.bookmarks.size,
                            backup.history.size
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmImport { ok ->
                        toast(if (ok) R.string.import_done else R.string.import_failed)
                    }
                    // Settings holds the home page in an edit field; closing it
                    // stops a later Save from writing the pre-restore value back.
                    showSettings = false
                }) { Text(stringResource(R.string.import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLibrary && !wide) {
        LibrarySheet(
            continueListening = viewModel.continueListening,
            bookmarks = viewModel.bookmarks,
            history = viewModel.history,
            speechRate = viewModel.speechRate,
            onOpen = { pageUrl ->
                showLibrary = false
                viewModel.loadUrl(pageUrl)
            },
            onRemoveBookmark = { viewModel.removeBookmark(it) },
            onClearHistory = { viewModel.clearHistory() },
            onDismiss = { showLibrary = false }
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    activity?.finish()
                }) { Text(stringResource(R.string.exit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * The playback controls, in whichever shape the window calls for: a floating
 * toolbar along the bottom on a phone, or a vertical one down the leading edge
 * when the window is wide enough that a bottom row would strand the buttons a
 * hand-span apart.
 *
 * Same buttons in the same order either way — folding the device should not
 * rearrange the user's controls — except that the rail, having room, promotes a
 * few actions out of the overflow menu and can show which side panel is open.
 */
@Composable
fun PlaybackControls(
    wide: Boolean,
    libraryOpen: Boolean,
    contentsOpen: Boolean,
    onOpenContents: () -> Unit,
    isPlaying: Boolean,
    busy: Boolean,
    hasSentences: Boolean,
    speechRate: Float,
    readerMode: Boolean,
    followAlong: Boolean,
    onToggleFollowAlong: () -> Unit,
    continueToNextFile: Boolean,
    onToggleContinueToNextFile: () -> Unit,
    textZoom: Int,
    canGoBack: Boolean,
    canGoUp: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onOpenManual: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPreviousSection: () -> Unit,
    onSkipNextSection: () -> Unit,
    onTextSmaller: () -> Unit,
    onTextLarger: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleReader: () -> Unit,
    onSettings: () -> Unit,
    onSupportDevelopment: () -> Unit,
    onTtsSettings: () -> Unit
) {
    // One list of buttons, two containers. Emitting the items from a single
    // lambda is what stops the bar and the rail drifting apart as controls come
    // and go. Its argument is the Play button's form, which the container decides.
    val railScroll = rememberScrollState()
    // A press that has to wait for the page shows that on the Play button. In the
    // rail the button can have been scrolled away (a phone held sideways), so
    // bring it back into view: otherwise nothing on screen says the press landed.
    LaunchedEffect(busy) {
        if (busy && wide) railScroll.animateScrollTo(0)
    }
    val items: @Composable (IconButtonDefaults.IconButtonWidthOption) -> Unit = { playWidth ->
        IconButton(onBack, enabled = canGoBack, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
        }
        IconButton(onForward, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.nav_forward))
        }
        SkipButton(
            icon = Icons.Default.SkipPrevious,
            label = stringResource(R.string.previous_sentence),
            longPressLabel = stringResource(R.string.previous_section),
            enabled = hasSentences,
            onClick = onSkipPrevious,
            onLongClick = onSkipPreviousSection
        )
        PlayPauseButton(isPlaying = isPlaying, busy = busy, widthOption = playWidth, onClick = onPlayPause)
        SkipButton(
            icon = Icons.Default.SkipNext,
            label = stringResource(R.string.next_sentence),
            longPressLabel = stringResource(R.string.next_section),
            enabled = hasSentences,
            onClick = onSkipNext,
            onLongClick = onSkipNextSection
        )
        if (wide) {
            // Room to hand back the actions the phone layout has to hide.
            ControlSeparator()
            IconButton(onUp, enabled = canGoUp, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.ArrowUpward, stringResource(R.string.menu_up_one_level))
            }
            IconButton(onHome, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.Home, stringResource(R.string.menu_home))
            }
            RailToggle(libraryOpen, { onOpenLibrary() }, Icons.Default.Bookmarks, stringResource(R.string.menu_library))
            RailToggle(contentsOpen, { onOpenContents() }, Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.panel_contents))
            ControlSeparator()
            RailToggle(readerMode, { onToggleReader() }, Icons.AutoMirrored.Filled.Article, stringResource(R.string.menu_reader_mode))
            RailToggle(followAlong, { onToggleFollowAlong() }, Icons.Default.Highlight, stringResource(R.string.menu_follow_along))
            ControlSeparator()
        }
        BottomBarOverflow(
            promotedInline = wide,
            speechRate = speechRate,
            readerMode = readerMode,
            followAlong = followAlong,
            onToggleFollowAlong = onToggleFollowAlong,
            continueToNextFile = continueToNextFile,
            onToggleContinueToNextFile = onToggleContinueToNextFile,
            textZoom = textZoom,
            canGoUp = canGoUp,
            onUp = onUp,
            onHome = onHome,
            onOpenManual = onOpenManual,
            onOpenLibrary = onOpenLibrary,
            onTextSmaller = onTextSmaller,
            onTextLarger = onTextLarger,
            onSetSpeed = onSetSpeed,
            onToggleReader = onToggleReader,
            onSettings = onSettings,
            onSupportDevelopment = onSupportDevelopment,
            onTtsSettings = onTtsSettings
        )
    }

    // Material 3 Expressive floating toolbars: a pill of controls standing off
    // the edge of the screen instead of a bar fused to it.
    if (wide) {
        // Scrollable because the rail also serves a phone in landscape, where
        // the whole stack of controls is taller than the window. The scroll is
        // outside the toolbar, which has to be measured at its full height.
        Box(
            Modifier
                .fillMaxHeight()
                .verticalScroll(railScroll)
                .padding(8.dp)
        ) {
            VerticalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors()
            ) { items(IconButtonDefaults.IconButtonWidthOption.Uniform) }
        }
    } else {
        // Six fixed-size controls, and nothing in a toolbar shrinks: on a narrow
        // window (a small phone, or the display size turned up) the row is wider
        // than the screen, and the end of it, the menu with Settings in it, was
        // simply cut off. So the Play button gives up its wide form first, and if
        // that still isn't enough the row scrolls rather than losing a control.
        // fillMaxWidth before the scroll keeps the row centred when it does fit.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val playWidth = if (maxWidth < COMPACT_TOOLBAR_WIDTH) IconButtonDefaults.IconButtonWidthOption.Uniform
                else IconButtonDefaults.IconButtonWidthOption.Wide
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors()
                ) { items(playWidth) }
            }
        }
    }
}

/**
 * Play and pause as one large button that changes shape with the state: round
 * while paused, a rounded square while reading, morphing between the two on a
 * spring. While a page is being read in for the first press it shows Material's
 * loading animation in place of the icon, so the wait is visible where the press
 * was rather than in the middle of the page. [widthOption] is Wide where the
 * horizontal toolbar has room for it.
 *
 * The label is on the button itself, not on the icon, because while loading
 * there is no icon: the loading animation has no description, and the button
 * was announced to TalkBack without a name. It now keeps "Play/Pause" and adds
 * that it is loading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    busy: Boolean,
    widthOption: IconButtonDefaults.IconButtonWidthOption,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val label = stringResource(R.string.play_pause)
    val loading = stringResource(R.string.loading)
    FilledIconToggleButton(
        checked = isPlaying,
        onCheckedChange = { onClick() },
        shapes = IconButtonDefaults.toggleableShapes(
            shape = CircleShape,
            pressedShape = MaterialTheme.shapes.medium,
            checkedShape = MaterialTheme.shapes.large
        ),
        // Primary either way: it's the one button that matters, playing or not.
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = primary,
            contentColor = onPrimary,
            checkedContainerColor = primary,
            checkedContentColor = onPrimary
        ),
        modifier = Modifier
            .size(IconButtonDefaults.mediumContainerSize(widthOption))
            .semantics {
                contentDescription = label
                if (busy) stateDescription = loading
            }
    ) {
        if (busy) {
            LoadingIndicator(Modifier.size(IconButtonDefaults.mediumIconSize + 8.dp), color = onPrimary)
        } else {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize)
            )
        }
    }
}

/** A rail button that stays lit while its panel or mode is on, and squares off
 *  when it is, as Material 3 Expressive toggles do. */
@Composable
private fun RailToggle(checked: Boolean, onToggle: () -> Unit, icon: ImageVector, label: String) {
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = Color.Transparent
        )
    ) { Icon(icon, label) }
}

/** Groups the rail's buttons. Rail-only: the phone toolbar has no room to spare
 *  for dividers between six controls. */
@Composable
private fun ControlSeparator() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp).width(40.dp))
}

/**
 * Container for the wide layout's trailing panel: a titled surface with a close
 * button. It sits beside the page rather than over it — being able to look at
 * both at once is the whole point of the extra width.
 */
@Composable
private fun SidePanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxHeight().padding(top = 4.dp, end = 8.dp, bottom = 8.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.panel_close))
                }
            }
            content()
        }
    }
}

/**
 * Skip control that moves one sentence on a tap and a whole page section on a
 * long press — the sentence-level and chapter-level gestures a listener wants
 * live on the same button, so the bottom bar doesn't grow two more icons.
 * A plain IconButton consumes the press itself, hence the hand-rolled surface.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkipButton(
    icon: ImageVector,
    label: String,
    longPressLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // Squares off under the finger and springs back round, like the toolbar's
    // other buttons. Hand-rolled for the same reason as the rest of this button.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateIntAsState(
        targetValue = if (pressed) 28 else 50,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "skipPress"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(percent = corner))
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                onClickLabel = label,
                // IconButton sets this for us; a hand-rolled clickable has to say
                // so itself or TalkBack announces the icon as plain text.
                role = Role.Button,
                onLongClickLabel = longPressLabel,
                onClick = onClick,
                onLongClick = {
                    // Section jumps are invisible until the TTS catches up, so
                    // confirm the long press landed.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f)
        )
    }
}

/**
 * The "⋮" menu in the bottom bar. Hosts the relocated navigation (up/home/
 * bookmarks), text-size control, plus speed presets, reader mode, settings, TTS.
 */
@Composable
private fun BottomBarOverflow(
    promotedInline: Boolean,
    speechRate: Float,
    readerMode: Boolean,
    followAlong: Boolean,
    onToggleFollowAlong: () -> Unit,
    continueToNextFile: Boolean,
    onToggleContinueToNextFile: () -> Unit,
    textZoom: Int,
    canGoUp: Boolean,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onOpenManual: () -> Unit,
    onOpenLibrary: () -> Unit,
    onTextSmaller: () -> Unit,
    onTextLarger: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleReader: () -> Unit,
    onSettings: () -> Unit,
    onSupportDevelopment: () -> Unit,
    onTtsSettings: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Hidden when the rail already carries them, rather than listed
            // twice: a menu that repeats the buttons next to it reads as clutter.
            if (!promotedInline) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_up_one_level)) },
                    enabled = canGoUp,
                    onClick = { onUp(); open = false },
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_home)) },
                    onClick = { onHome(); open = false },
                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_library)) },
                    onClick = { onOpenLibrary(); open = false },
                    leadingIcon = { Icon(Icons.Default.Bookmarks, contentDescription = null) }
                )
                HorizontalDivider()
            }
            // Text-size stepper. These don't close the menu so zoom can be nudged
            // repeatedly; the current percentage shows between the −/+ buttons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.menu_text_size), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onTextSmaller) {
                        Icon(Icons.Default.TextDecrease, stringResource(R.string.decrease_text_size))
                    }
                    Text(
                        stringResource(R.string.text_zoom_percent, textZoom),
                        style = MaterialTheme.typography.labelLarge
                    )
                    IconButton(onTextLarger) {
                        Icon(Icons.Default.TextIncrease, stringResource(R.string.increase_text_size))
                    }
                }
            }
            HorizontalDivider()
            if (!promotedInline) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_reader_mode)) },
                    onClick = { onToggleReader(); open = false },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                    trailingIcon = {
                        if (readerMode) Icon(Icons.Default.Check, stringResource(R.string.state_on))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_follow_along)) },
                    onClick = { onToggleFollowAlong(); open = false },
                    leadingIcon = { Icon(Icons.Default.Highlight, contentDescription = null) },
                    trailingIcon = {
                        if (followAlong) Icon(Icons.Default.Check, stringResource(R.string.state_on))
                    }
                )
                HorizontalDivider()
            }
            // Not promoted to the rail: it's a set-and-forget preference, not
            // something reached for mid-read.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_continue_next_file)) },
                onClick = { onToggleContinueToNextFile(); open = false },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                trailingIcon = {
                    if (continueToNextFile) Icon(Icons.Default.Check, stringResource(R.string.state_on))
                }
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.menu_reading_speed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )
            // A connected button group: the presets read as one control with one
            // choice made, and picking another leaves the menu open to compare.
            Row(
                Modifier.width(SPEED_GROUP_WIDTH).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                SPEECH_RATE_PRESETS.forEachIndexed { i, rate ->
                    ToggleButton(
                        checked = rate == speechRate,
                        onCheckedChange = { onSetSpeed(rate) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            SPEECH_RATE_PRESETS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // A visible container for the unpicked ones too, or on the
                        // menu's own surface they read as loose labels, not a group.
                        colors = ToggleButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        // ToggleButton's own toggle semantics, unchanged: overriding
                        // the role to RadioButton gave TalkBack a radio button with
                        // an on/off state.
                        modifier = Modifier.weight(1f)
                    ) {
                        // Shrinks to fit rather than clipping: five to a menu leaves
                        // about 52dp a button, and with a large font "1.25×" read "1.2".
                        Text(
                            formatSpeed(rate),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 8.sp,
                                maxFontSize = MaterialTheme.typography.labelLarge.fontSize
                            )
                        )
                    }
                }
            }
            HorizontalDivider()
            // Reachable whatever the user sets as their home page — otherwise
            // the manual becomes unfindable the moment they set one.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_user_manual)) },
                onClick = { onOpenManual(); open = false },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_settings)) },
                onClick = { onSettings(); open = false },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_tts_settings)) },
                onClick = { onTtsSettings(); open = false },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) }
            )
            // Last in the menu on purpose: entirely optional, and nothing in the
            // app is gated behind it.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_support_development)) },
                onClick = { onSupportDevelopment(); open = false },
                leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null) }
            )
        }
    }
}

/**
 * Opens [url] in some *other* app. PageVox registers itself as a handler for
 * http and https links, so a plain ACTION_VIEW can resolve straight back to this
 * app — which for a payment page means rendering it in a WebView we control.
 * Excluding our own activity from the chooser keeps that from happening.
 */
internal fun openOutsideThisApp(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    val chooser = Intent.createChooser(view, context.getString(R.string.open_with)).apply {
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(context, MainActivity::class.java))
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { Log.e("MainScreen", "No app available to open $url", it) }
}

/** "1×", "1.25×", … without trailing-zero noise. */
private fun formatSpeed(rate: Float): String {
    val s = if (rate % 1f == 0f) rate.toInt().toString()
            else rate.toString().trimEnd('0').trimEnd('.')
    return "$s×"
}
