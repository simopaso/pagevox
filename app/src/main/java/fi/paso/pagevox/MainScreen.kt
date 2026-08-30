package fi.paso.pagevox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import org.json.JSONObject

// Voluntary support for development. Unlocks nothing in the app and is never
// required for any feature — it is a link out, not a purchase.
// Deliberately without locale/country parameters: those would force the payment
// page into Finnish for every visitor, and PageVox's audience isn't Finnish.
private const val SUPPORT_URL = "https://paypal.me/SimoPaso"

@Composable
fun MainScreen(viewModel: MainViewModel, controller: MediaController?) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showClearSiteData by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    val context = LocalContext.current

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
                        val args = Bundle().apply { putInt("startIndex", idx) }
                        controller?.sendCustomCommand(
                            SessionCommand("playSentences", Bundle.EMPTY), args
                        )
                    }
                )
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
                    onOpenManual = { viewModel.loadUrl(manualUrl()) },
                    onOpenLibrary = { showLibrary = true },
                    onPlayPause = {
                        if (viewModel.sentences.isEmpty()) {
                            extractTexts(webView, viewModel.readerMode) { lang, blocks ->
                                viewModel.onTextsExtracted(lang, blocks) { togglePlay() }
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
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.url.isNotEmpty()) {
                WebViewContainer(
                    url = viewModel.url,
                    currentSentence = viewModel.currentSentenceText,
                    onWebViewReady = { webView = it },
                    onUrlChange = { viewModel.onPageUrlSettled(it) },
                    onTitleChange = { pageUrl, title -> viewModel.onPageTitle(pageUrl, title) },
                    onCanGoBackChanged = { canGoBack = it },
                    onTextTapped = { clickedText ->
                        val seekAndPlay = {
                            // A tap that can't be matched to a sentence must NOT
                            // fall back to sentence 0 — that would jump reading to
                            // the top of the page instead of the tapped spot.
                            val idx = viewModel.findSentenceIndex(clickedText)
                            if (idx >= 0) {
                                val args = Bundle().apply { putInt("startIndex", idx) }
                                controller?.sendCustomCommand(
                                    SessionCommand("playSentences", Bundle.EMPTY), args
                                )
                            }
                        }
                        if (viewModel.sentences.isEmpty()) {
                            extractTexts(webView, viewModel.readerMode) { lang, blocks ->
                                viewModel.onTextsExtracted(lang, blocks) { seekAndPlay() }
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
            onClearSiteData = { showClearSiteData = true },
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

    if (showLibrary) {
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
    BottomAppBar {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
            }
            IconButton(onForward) {
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
            FloatingActionButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(R.string.play_pause)
                )
            }
            SkipButton(
                icon = Icons.Default.SkipNext,
                label = stringResource(R.string.next_sentence),
                longPressLabel = stringResource(R.string.next_section),
                enabled = hasSentences,
                onClick = onSkipNext,
                onLongClick = onSkipNextSection
            )
            BottomBarOverflow(
                speechRate = speechRate,
                readerMode = readerMode,
                followAlong = followAlong,
                onToggleFollowAlong = onToggleFollowAlong,
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
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
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
    speechRate: Float,
    readerMode: Boolean,
    followAlong: Boolean,
    onToggleFollowAlong: () -> Unit,
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
            Text(
                stringResource(R.string.menu_reading_speed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )
            SPEECH_RATE_PRESETS.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(rate)) },
                    onClick = { onSetSpeed(rate); open = false },
                    trailingIcon = {
                        if (rate == speechRate) {
                            Icon(Icons.Default.Check, stringResource(R.string.state_selected))
                        }
                    }
                )
            }
            HorizontalDivider()
            // Reachable whatever the user sets as their home page — otherwise
            // the manual becomes unfindable the moment they set one.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_user_manual)) },
                onClick = { onOpenManual(); open = false },
                leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) }
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
private fun openOutsideThisApp(context: Context, url: String) {
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
