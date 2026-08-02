package fi.paso.pagevox

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "WebViewContainer"

/**
 * Carries the WebView across the saved-instance-state boundary. The Saver's
 * `save` lambda runs at onSaveInstanceState time — while the WebView is still
 * alive — which is the only moment saveState() can capture the back/forward
 * list. A plain rememberSaveable Bundle can't do this because it would be
 * serialized before onDispose gets a chance to fill it.
 */
private class WebViewStateHolder {
    var webView: WebView? = null
    var restored: Bundle? = null
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
    textZoom: Int,
    readerMode: Boolean,
    followAlong: Boolean
) {
    val context = LocalContext.current
    val latestOnUrlChange = rememberUpdatedState(onUrlChange)
    val latestOnTitleChange = rememberUpdatedState(onTitleChange)
    val latestOnCanGoBackChanged = rememberUpdatedState(onCanGoBackChanged)
    val latestOnTextTapped = rememberUpdatedState(onTextTapped)
    val latestReaderMode = rememberUpdatedState(readerMode)

    // The last URL onPageFinished reported. Used to break the feedback cycle
    // WebView → onPageFinished → viewModel.loadUrl → LaunchedEffect(url) →
    // webView.loadUrl: a URL that came *from* the WebView must never be pushed
    // back *into* it, or any server/client redirect turns into an infinite
    // reload loop (observed after a device-transfer restore corrupted WebView
    // state).
    var lastReportedUrl by remember { mutableStateOf<String?>(null) }

    val stateHolder = rememberSaveable(
        saver = Saver<WebViewStateHolder, Bundle>(
            save = { holder -> Bundle().also { holder.webView?.saveState(it) } },
            restore = { bundle -> WebViewStateHolder().apply { restored = bundle } }
        )
    ) { WebViewStateHolder() }

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
                        lastReportedUrl = url
                        latestOnUrlChange.value(url)
                    }
                    if (latestReaderMode.value) view?.evaluateJavascript(APPLY_READER_JS, null)
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

            // Restore the back/forward list after an activity recreation
            // (rotation, theme change) instead of re-fetching from network.
            stateHolder.restored?.let { if (!it.isEmpty) restoreState(it) }
            stateHolder.restored = null
            stateHolder.webView = this

            onWebViewReady(this)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            stateHolder.webView = null
            webView.destroy()
        }
    }

    LaunchedEffect(url) {
        if (url.isEmpty() || url.startsWith("data:")) return@LaunchedEffect
        // Don't push a URL the WebView is already at (or a trivial redirect
        // variant of it), and never echo back a URL that originated from
        // onPageFinished — either would re-trigger navigation and can escalate
        // into an infinite reload loop.
        if (url == lastReportedUrl) return@LaunchedEffect
        val current = webView.url
        if (current != null &&
            normalizeUrlForCompare(current) == normalizeUrlForCompare(url)
        ) return@LaunchedEffect
        webView.loadUrl(url)
    }

    // Follow-along: highlight the active sentence and keep it in view. Gated by
    // the user toggle; turning it off clears any existing highlight.
    LaunchedEffect(currentSentence, followAlong) {
        if (followAlong && currentSentence.isNotEmpty()) {
            webView.evaluateJavascript(highlightSentenceJs(JSONObject.quote(currentSentence)), null)
        } else if (!followAlong) {
            webView.evaluateJavascript(CLEAR_HIGHLIGHT_JS, null)
        }
    }

    // Re-assert the follow-along highlight when the app returns to the
    // foreground. The effect above is keyed on the sentence *text*, so a resume
    // that lands on the same sentence it paused on won't re-run it — yet the
    // WebView may have dropped the CSS highlight during the background period
    // (re-layout, renderer trim). Without this, the highlight stays missing
    // until playback advances to the next sentence or the page is reloaded.
    val latestSentence = rememberUpdatedState(currentSentence)
    val latestFollowAlong = rememberUpdatedState(followAlong)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val sentence = latestSentence.value
                if (latestFollowAlong.value && sentence.isNotEmpty()) {
                    webView.evaluateJavascript(highlightSentenceJs(JSONObject.quote(sentence)), null)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    // Apply/remove the reader-mode stylesheet live when toggled.
    LaunchedEffect(readerMode) {
        webView.evaluateJavascript(if (readerMode) APPLY_READER_JS else REMOVE_READER_JS, null)
    }

    AndroidView({ webView }, Modifier.fillMaxSize())
}
