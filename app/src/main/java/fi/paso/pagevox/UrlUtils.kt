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
 * A human title for the media notification. Plain-text files and other pages
 * without a `<title>` get their URL reported as the title by WebView, which put
 * "https://example.com/books/chapter-01.txt" in full on the lock screen. A title
 * that is really a URL is cut down to its file name ("chapter-01.txt"), or the
 * site when the path has none. Null when there's nothing usable, so the caller
 * can fall back to the site name.
 *
 * java.net.URI rather than android.net.Uri so this stays testable on the JVM;
 * its getPath() also undoes percent-encoding, so "my%20notes.txt" reads as
 * "my notes.txt".
 */
internal fun readableTitle(pageTitle: String, pageUrl: String?): String? {
    val title = pageTitle.trim()
    if (title.isEmpty()) return null
    val looksLikeUrl = title == pageUrl?.trim() ||
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(title)
    if (!looksLikeUrl) return title
    val uri = try { java.net.URI(title) } catch (e: Exception) { return null }
    uri.path?.split('/')?.lastOrNull { it.isNotBlank() }?.let { return it }
    return uri.host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}
