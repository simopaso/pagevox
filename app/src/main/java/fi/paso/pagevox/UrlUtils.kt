package fi.paso.pagevox

import android.net.Uri

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

/**
 * Computes the parent "folder" URL by dropping the final path segment.
 *   https://site.com/a/b/file.txt -> https://site.com/a/b/
 *   https://site.com/a/b/         -> https://site.com/a/
 *   https://site.com/a/           -> https://site.com/
 * Returns null when already at the site root (nothing to go up to), and for
 * anything that isn't a web page: going "up" from the bundled manual at
 * file:///android_asset/manual.html would land on a directory the WebView
 * can't render, leaving the user staring at an error page.
 */
fun parentFolderUrl(rawUrl: String): String? {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val authority = uri.authority ?: ""
    // Strip a trailing slash so the final segment can be removed uniformly.
    val path = (uri.path ?: "").removeSuffix("/")
    if (path.isEmpty()) return null  // already at root
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    return "$scheme://$authority$parent/"
}

/** Canonical form used to detect harmless URL redirects (trailing slash,
 *  http→https upgrade, fragment changes, host case). Two URLs that normalize
 *  to the same string represent the "same page" for reading-position purposes. */
internal fun normalizeUrlForCompare(u: String): String = try {
    val uri = Uri.parse(u)
    val scheme = (uri.scheme ?: "").lowercase().let { if (it == "http") "https" else it }
    val host = (uri.authority ?: "").lowercase()
    val path = (uri.path ?: "").trimEnd('/')
    val q = uri.query?.takeIf { it.isNotBlank() }
    buildString {
        append(scheme).append("://").append(host).append(path)
        if (q != null) append('?').append(q)
    }
} catch (e: Exception) { u }

/**
 * A human title for the media notification. A page with no `<title>` — every
 * plain-text file — is given one by Chromium, formatted from its URL. That
 * put "example.com/books/chapter-01.txt" on the lock screen, and it isn't
 * the raw URL either: Chromium drops the scheme (https:// included), drops a
 * leading "www." and decodes %20 (NavigationEntryImpl::GetTitleForDisplay:
 * kFormatUrlOmitDefaults | OmitHTTPS | OmitTrivialSubdomains). So a title is
 * recognised as a URL by starting with the page's own host, not by having a
 * scheme, and the name is then taken from the real URL rather than the lossy
 * display copy. The result is the file name ("chapter-01.txt"), or the site
 * when the path has none; null when there's nothing usable, so the caller can
 * fall back to the site name.
 *
 * java.net.URI rather than android.net.Uri so this stays testable on the JVM;
 * its getPath() undoes percent-encoding, so "my%20notes.txt" reads as
 * "my notes.txt".
 */
internal fun readableTitle(pageTitle: String, pageUrl: String?): String? {
    val title = pageTitle.trim()
    if (title.isEmpty()) return null
    if (!titleIsUrl(title, pageUrl)) return title
    fileNameOrHost(pageUrl)?.let { return it }
    return fileNameOrHost(if (URL_SCHEME.containsMatchIn(title)) title else "https://$title")
}

private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

// "host.tld/..." with no whitespace in the host part: Chromium's display form
// of a URL. Only consulted when there's no page URL to compare against, and it
// requires the slash so a real title like "Node.js" isn't mistaken for one.
private val DISPLAY_URL = Regex("""^[A-Za-z0-9.-]+\.[A-Za-z]{2,}(:\d+)?/.*$""")

private fun titleIsUrl(title: String, pageUrl: String?): Boolean {
    if (URL_SCHEME.containsMatchIn(title)) return true
    if (pageUrl != null && title == pageUrl.trim()) return true
    val host = pageUrl?.let { runCatching { java.net.URI(it.trim()).host }.getOrNull() }?.lowercase()
        ?: return DISPLAY_URL.matches(title)
    val t = title.lowercase()
    return listOf(host, host.removePrefix("www.")).any { h ->
        t == h || t.startsWith("$h/") || t.startsWith("$h:")
    }
}

private fun fileNameOrHost(url: String?): String? {
    val uri = url?.let { runCatching { java.net.URI(it.trim()) }.getOrNull() } ?: return null
    uri.path?.split('/')?.lastOrNull { it.isNotBlank() }?.let { return it }
    return uri.host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}

/**
 * True when [a] and [b] are the same document apart from query and fragment:
 * same scheme, host and path, ignoring a trailing slash and host case. Used to
 * tell a single-page app's real navigation (a new path) from the query and
 * fragment rewrites sites make while you scroll. java.net.URI so it stays
 * testable on the JVM.
 */
internal fun samePath(a: String, b: String): Boolean {
    fun key(u: String): String? = runCatching {
        val uri = java.net.URI(u.trim())
        val scheme = uri.scheme?.lowercase()?.let { if (it == "http") "https" else it }
        "$scheme://${uri.host?.lowercase()}${uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()}" +
            (uri.rawPath ?: "").trimEnd('/')
    }.getOrNull()
    val ka = key(a)
    return ka != null && ka == key(b)
}
