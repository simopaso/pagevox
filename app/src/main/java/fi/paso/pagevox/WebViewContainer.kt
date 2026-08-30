package fi.paso.pagevox

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "WebViewContainer"

/** A pending HTTP Basic/Digest challenge waiting on the credentials dialog.
 *  The handler must be answered exactly once — proceed or cancel — or the page
 *  load hangs forever. */
private data class HttpAuthRequest(
    val handler: HttpAuthHandler,
    val host: String,
    val realm: String
)

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

    // Set when a site asks for HTTP Basic/Digest credentials; drives the dialog
    // at the bottom of this composable.
    var authRequest by remember { mutableStateOf<HttpAuthRequest?>(null) }

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
            // Sign-in buttons routinely call window.open(); without multiple-window
            // support those calls are silently dropped and the button does nothing
            // at all. onCreateWindow below turns the popup back into a normal
            // navigation in this same view.
            settings.setSupportMultipleWindows(true)

            // Cookies are how a login survives leaving the page. Third-party
            // cookies are off by default on a modern target SDK, which breaks
            // every single-sign-on flow where an identity provider sets or reads
            // a cookie on a different host than the page you're on.
            CookieManager.getInstance().let { cookies ->
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("WebViewConsole", "[${msg.messageLevel()}] ${msg.message()}")
                    return true
                }

                /** A page asked for a popup window. PageVox has no tabs, so hand
                 *  the page a throwaway WebView whose only job is to report the
                 *  URL it's told to load, then navigate the real view there. */
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message
                ): Boolean {
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    val relay = WebView(view.context)
                    relay.settings.javaScriptEnabled = true
                    relay.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            relayView: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            view.loadUrl(request.url.toString())
                            // Destroying it from inside its own callback is not
                            // safe; let the current dispatch finish first.
                            relayView.post { runCatching { relayView.destroy() } }
                            return true
                        }
                    }
                    transport.webView = relay
                    resultMsg.sendToTarget()
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
                /** Anything that isn't a web page belongs to another app: an
                 *  OAuth callback to a custom scheme, an "intent://" app link, a
                 *  mailto: or tel: address. Left unhandled these render as an
                 *  error page, which is exactly where a sign-in flow dead-ends. */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url ?: return false
                    return when (uri.scheme?.lowercase()) {
                        // Everything the WebView renders itself. "javascript" and
                        // "blob" are in the list defensively: no other app could
                        // ever open them, and a "javascript:void(0)" link — which
                        // is most of the web's buttons — must not be treated as a
                        // request to leave the browser.
                        "http", "https", "file", "data", "about", "javascript", "blob", null -> false
                        else -> openExternally(view, uri)
                    }
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView,
                    handler: HttpAuthHandler,
                    host: String,
                    realm: String
                ) {
                    // A first attempt may reuse credentials saved for this
                    // host/realm; a retry means those were wrong, so ask.
                    if (handler.useHttpAuthUsernamePassword()) {
                        val saved = runCatching {
                            WebViewDatabase.getInstance(view.context)
                                .getHttpAuthUsernamePassword(host, realm)
                        }.getOrNull()
                        val user = saved?.getOrNull(0)
                        if (!user.isNullOrEmpty()) {
                            handler.proceed(user, saved.getOrNull(1).orEmpty())
                            return
                        }
                    }
                    authRequest = HttpAuthRequest(handler, host, realm)
                }

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
            if (event == Lifecycle.Event.ON_PAUSE) {
                // WebView writes its cookie store to disk on its own schedule, so
                // a login completed seconds before the process is killed can be
                // lost. Backgrounding is the moment to make it durable.
                runCatching { CookieManager.getInstance().flush() }
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

    authRequest?.let { request ->
        HttpAuthDialog(
            host = request.host,
            realm = request.realm,
            onCancel = {
                request.handler.cancel()
                authRequest = null
            },
            onSubmit = { user, password ->
                // Remember it the way a browser does, so a protected site doesn't
                // ask again on every page. Cleared by "Clear cookies and sign out".
                runCatching {
                    WebViewDatabase.getInstance(context)
                        .setHttpAuthUsernamePassword(request.host, request.realm, user, password)
                }
                request.handler.proceed(user, password)
                authRequest = null
            }
        )
    }
}

/** Credentials prompt for HTTP Basic/Digest auth. Without one, the default
 *  WebViewClient silently cancels the challenge and the site just fails. */
@Composable
private fun HttpAuthDialog(
    host: String,
    realm: String,
    onCancel: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.http_auth_title, host)) },
        text = {
            Column {
                if (realm.isNotBlank()) {
                    Text(
                        realm,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.http_auth_username)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.http_auth_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(user, password) }) {
                Text(stringResource(R.string.http_auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Hands a non-web URL to whichever app claims it, and reports whether the
 * WebView should stop trying to load it (always — an unhandled scheme renders
 * as an error page, which is worse than nothing).
 */
private fun openExternally(view: WebView, uri: Uri): Boolean {
    val url = uri.toString()
    val isIntentUrl = uri.scheme.equals("intent", ignoreCase = true)
    val intent = runCatching {
        if (isIntentUrl) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        else Intent(Intent.ACTION_VIEW, uri)
    }.getOrNull() ?: return true

    if (isIntentUrl) {
        // An "intent://" URL is attacker-controlled text: left as parsed it can
        // name any component in any app, including ones never meant to be
        // reachable from the web. Strip the targeting and require the same
        // category a browser-launched intent would carry.
        intent.component = null
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
    }
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

    return try {
        view.context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        // "Open in our app" banners name a web page to fall back to when the app
        // isn't installed.
        val fallback = runCatching { intent.getStringExtra("browser_fallback_url") }.getOrNull()
        if (!fallback.isNullOrBlank()) view.loadUrl(fallback)
        else Log.d(TAG, "No app can open $url")
        true
    }
}

/**
 * Signs out of everything: cookies, the web storage sites keep tokens in, and
 * saved HTTP auth credentials. Reloads afterwards so the current page reflects
 * the signed-out state instead of showing a stale logged-in render.
 */
internal fun clearSiteData(webView: WebView?) {
    runCatching {
        CookieManager.getInstance().let { cookies ->
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
    runCatching { WebStorage.getInstance().deleteAllData() }
    webView?.context?.let { ctx ->
        runCatching { WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword() }
    }
    webView?.reload()
}
