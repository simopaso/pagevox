package fi.paso.pagevox

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import org.json.JSONObject

@Composable
fun MainScreen(viewModel: MainViewModel, controller: MediaController?) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val activity = LocalActivity.current

    // Back navigates the WebView while there's page history; once there isn't,
    // intercept it to confirm before exiting instead of silently closing.
    BackHandler {
        if (canGoBack) webView?.goBack() else showExitConfirm = true
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
                onToggleBookmark = { viewModel.toggleBookmark() }
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
                val parentUrl = remember(viewModel.url) { parentFolderUrl(viewModel.url) }
                BottomBar(
                    isPlaying = isPlaying,
                    hasSentences = viewModel.sentences.isNotEmpty(),
                    speechRate = viewModel.speechRate,
                    readerMode = viewModel.readerMode,
                    followAlong = viewModel.followAlong,
                    onToggleFollowAlong = { viewModel.toggleFollowAlong() },
                    textZoom = viewModel.textZoom,
                    canGoBack = canGoBack,
                    canGoUp = parentUrl != null,
                    onBack = { webView?.goBack() },
                    onForward = { webView?.goForward() },
                    onUp = { parentUrl?.let { viewModel.loadUrl(it) } },
                    onHome = { viewModel.loadUrl(viewModel.homeUrl) },
                    onOpenLibrary = { showLibrary = true },
                    onPlayPause = {
                        if (viewModel.sentences.isEmpty()) {
                            extractTexts(webView, viewModel.readerMode) { lang, texts ->
                                viewModel.onTextsExtracted(lang, texts) { togglePlay() }
                            }
                        } else {
                            togglePlay()
                        }
                    },
                    onSkipPrevious = {
                        controller?.sendCustomCommand(SessionCommand("skipPrevious", Bundle.EMPTY), Bundle.EMPTY)
                    },
                    onSkipNext = {
                        controller?.sendCustomCommand(SessionCommand("skipNext", Bundle.EMPTY), Bundle.EMPTY)
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
                                    extractTexts(webView, viewModel.readerMode) { lang, texts ->
                                        viewModel.onTextsExtracted(lang, texts) { seekAndPlay() }
                                    }
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
            selectedVoice = viewModel.selectedVoice,
            onSelectVoice = { viewModel.updateSelectedVoice(it) },
            onShowLicenses = { showLicenses = true },
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

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit PageVox?") },
            text = { Text("There's no previous page to go back to. Close the app?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    activity?.finish()
                }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun BottomBar(
    isPlaying: Boolean,
    hasSentences: Boolean,
    speechRate: Float,
    readerMode: Boolean,
    followAlong: Boolean,
    onToggleFollowAlong: () -> Unit,
    textZoom: Int,
    canGoBack: Boolean,
    canGoUp: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTextSmaller: () -> Unit,
    onTextLarger: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleReader: () -> Unit,
    onSettings: () -> Unit,
    onTtsSettings: () -> Unit
) {
    BottomAppBar {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            IconButton(onForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            IconButton(onSkipPrevious, enabled = hasSentences) {
                Icon(Icons.Default.SkipPrevious, "Previous sentence")
            }
            FloatingActionButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
            }
            IconButton(onSkipNext, enabled = hasSentences) {
                Icon(Icons.Default.SkipNext, "Next sentence")
            }
            BottomBarOverflow(
                speechRate = speechRate,
                readerMode = readerMode,
                followAlong = followAlong,
                onToggleFollowAlong = onToggleFollowAlong,
                textZoom = textZoom,
                canGoUp = canGoUp,
                onUp = onUp,
                onHome = onHome,
                onOpenLibrary = onOpenLibrary,
                onTextSmaller = onTextSmaller,
                onTextLarger = onTextLarger,
                onSetSpeed = onSetSpeed,
                onToggleReader = onToggleReader,
                onSettings = onSettings,
                onTtsSettings = onTtsSettings
            )
        }
    }
}

/**
 * The "⋮" menu in the bottom bar. Hosts the relocated navigation (up/home/
 * bookmarks), text-size control, plus speed presets, reader mode, settings, TTS.
 */
@Composable
private fun BottomBarOverflow(
    speechRate: Float,
    readerMode: Boolean,
    followAlong: Boolean,
    onToggleFollowAlong: () -> Unit,
    textZoom: Int,
    canGoUp: Boolean,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onOpenLibrary: () -> Unit,
    onTextSmaller: () -> Unit,
    onTextLarger: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleReader: () -> Unit,
    onSettings: () -> Unit,
    onTtsSettings: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Up one level") },
                enabled = canGoUp,
                onClick = { onUp(); open = false },
                leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("Home") },
                onClick = { onHome(); open = false },
                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("Bookmarks & history") },
                onClick = { onOpenLibrary(); open = false },
                leadingIcon = { Icon(Icons.Default.Bookmarks, contentDescription = null) }
            )
            HorizontalDivider()
            // Text-size stepper. These don't close the menu so zoom can be nudged
            // repeatedly; the current percentage shows between the −/+ buttons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Text size", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onTextSmaller) { Icon(Icons.Default.TextDecrease, "Decrease text size") }
                    Text("$textZoom%", style = MaterialTheme.typography.labelLarge)
                    IconButton(onTextLarger) { Icon(Icons.Default.TextIncrease, "Increase text size") }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Reader mode") },
                onClick = { onToggleReader(); open = false },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                trailingIcon = { if (readerMode) Icon(Icons.Default.Check, contentDescription = "On") }
            )
            DropdownMenuItem(
                text = { Text("Follow along") },
                onClick = { onToggleFollowAlong(); open = false },
                leadingIcon = { Icon(Icons.Default.Highlight, contentDescription = null) },
                trailingIcon = { if (followAlong) Icon(Icons.Default.Check, contentDescription = "On") }
            )
            HorizontalDivider()
            Text(
                "Reading speed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )
            SPEECH_RATE_PRESETS.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(rate)) },
                    onClick = { onSetSpeed(rate); open = false },
                    trailingIcon = {
                        if (rate == speechRate) Icon(Icons.Default.Check, contentDescription = "Selected")
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = { onSettings(); open = false },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("TTS voice settings") },
                onClick = { onTtsSettings(); open = false },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) }
            )
        }
    }
}

/** "1×", "1.25×", … without trailing-zero noise. */
private fun formatSpeed(rate: Float): String {
    val s = if (rate % 1f == 0f) rate.toInt().toString()
            else rate.toString().trimEnd('0').trimEnd('.')
    return "$s×"
}
