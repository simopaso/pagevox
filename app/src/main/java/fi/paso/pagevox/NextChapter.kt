package fi.paso.pagevox

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/**
 * Finding and fetching the file after the one just read, so a book split into
 * chapter files plays through instead of stopping at every chapter.
 *
 * Only plain-text documents qualify. Their text needs no WebView to extract —
 * the whole file is one block, exactly what the page script would return — so
 * the playback service can do all of this itself with the screen off and the
 * activity long gone. An HTML page would need a WebView to run the extraction
 * script in, which the service doesn't have.
 *
 * Everything above [fetchNextTextFile] is pure and unit-tested.
 */

private const val TAG = "NextChapter"

// Extensions treated as plain text. The next file must share the current one's.
private val TEXT_EXTENSIONS = setOf("txt", "text", "md")

/** Decoded last path segment of [url], or null. */
private fun fileName(url: String): String? =
    runCatching { URI(url.trim()).path }.getOrNull()
        ?.split('/')?.lastOrNull { it.isNotBlank() }

private fun extensionOf(name: String): String =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

/** True for a web URL whose file name ends in a plain-text extension. */
internal fun isTextDocument(url: String): Boolean {
    val scheme = runCatching { URI(url.trim()).scheme }.getOrNull()?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    return fileName(url)?.let { extensionOf(it) in TEXT_EXTENSIONS } == true
}

/** The folder [url] sits in, with its encoding intact ("…/books/"). */
internal fun folderOf(url: String): String? =
    runCatching { URI(url.trim()).resolve(".").toString() }.getOrNull()

/**
 * "chapter-2" before "chapter-10": digit runs compare by value, everything
 * else case-insensitively. A plain string sort would read chapter 10 straight
 * after chapter 1.
 */
internal val naturalOrder: Comparator<String> = Comparator { a, b ->
    val chunk = Regex("""\d+|\D+""")
    val x = chunk.findAll(a).map { it.value }.toList()
    val y = chunk.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]
        val q = y[i]
        val cmp = if (p[0].isDigit() && q[0].isDigit()) {
            // Compare by value without overflow: strip zeros, then length, then digits.
            val pt = p.trimStart('0')
            val qt = q.trimStart('0')
            if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
        } else {
            p.lowercase().compareTo(q.lowercase())
        }
        if (cmp != 0) return@Comparator cmp
    }
    x.size - y.size
}

/**
 * The file after [currentUrl] in a server's folder listing (the plain HTML
 * index Apache, nginx and most static hosts generate). Candidates are the
 * listing's links that point into the same folder and share the current file's
 * extension; the next one is the first that sorts after the current name in
 * [naturalOrder], so it works even if the listing is sorted by date, or
 * doesn't mention the current file at all.
 */
internal fun nextFromListing(currentUrl: String, listingUrl: String, listingHtml: String): String? {
    val folder = runCatching { URI(listingUrl.trim()) }.getOrNull() ?: return null
    val current = fileName(currentUrl) ?: return null
    val ext = extensionOf(current)
    val href = Regex("""href\s*=\s*["']([^"'#?]+)""", RegexOption.IGNORE_CASE)
    val candidates = href.findAll(listingHtml)
        .mapNotNull { m -> runCatching { folder.resolve(m.groupValues[1].trim()) }.getOrNull() }
        .filter { it.scheme.equals(folder.scheme, ignoreCase = true) && it.host == folder.host }
        // Directly inside this folder: not the parent, not a subfolder.
        .filter { it.path != null && it.path.substringBeforeLast('/') + "/" == folder.path }
        .mapNotNull { uri -> fileName(uri.toString())?.let { it to uri.toString() } }
        .filter { (name, _) -> extensionOf(name) == ext }
        .distinctBy { it.first }
        .toList()
    return candidates
        .filter { (name, _) -> naturalOrder.compare(name, current) > 0 }
        .minWithOrNull { a, b -> naturalOrder.compare(a.first, b.first) }
        ?.second
}

/**
 * [url] with the last number in its file name bumped: chapter-09.txt ->
 * chapter-10.txt, part1-3.txt -> part1-4.txt. Leading zeros keep their width.
 * The fallback for hosts that don't publish a folder listing.
 */
internal fun incrementedFileUrl(url: String): String? {
    val slash = url.lastIndexOf('/')
    if (slash < 0) return null
    val name = url.substring(slash + 1).substringBefore('?').substringBefore('#')
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val number = Regex("""\d+""").findAll(stem).lastOrNull() ?: return null
    val digits = number.value
    val next = (digits.toBigInteger() + java.math.BigInteger.ONE).toString()
    val padded = if (digits.startsWith("0")) next.padStart(digits.length, '0') else next
    val newStem = stem.replaceRange(number.range, padded)
    return url.substring(0, slash + 1) + newStem + name.substring(stem.length)
}

/** Text decoded from a response body: its declared charset, else UTF-8, with a
 *  byte-order mark dropped and line endings normalised. */
internal fun decodeText(bytes: ByteArray, contentType: String?): String {
    val declared = contentType?.let { Regex("""charset=["']?([^;"'\s]+)""", RegexOption.IGNORE_CASE).find(it) }
        ?.groupValues?.get(1)
    val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
}

// ── Network ──────────────────────────────────────────────────────────────────

private const val MAX_TEXT_BYTES = 10 * 1024 * 1024
private const val MAX_LISTING_BYTES = 2 * 1024 * 1024
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) PageVox"

private class Fetched(val url: String, val contentType: String, val body: ByteArray)

/** GET [url], or null on any failure. Blocking: call from a background thread. */
private fun fetch(url: String, cookie: String?, maxBytes: Int): Fetched? {
    val conn = try {
        URL(url).openConnection() as HttpURLConnection
    } catch (e: Exception) {
        return null
    }
    return try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        if (conn.responseCode !in 200..299) return null
        val body = conn.inputStream.use { readCapped(it, maxBytes) } ?: return null
        Fetched(conn.url.toString(), conn.contentType.orEmpty(), body)
    } catch (e: Exception) {
        Log.w(TAG, "Fetch failed: $url", e)
        null
    } finally {
        conn.disconnect()
    }
}

private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > maxBytes) return null
    }
    return out.toByteArray()
}

/** A servable text file, not the HTML "not found" page some hosts send with a 200. */
private fun looksLikeText(f: Fetched): Boolean {
    val type = f.contentType.lowercase()
    if (type.startsWith("text/html") || type.startsWith("application/xhtml")) return false
    return type.startsWith("text/") || type.isEmpty() ||
        type.startsWith("application/octet-stream") || isTextDocument(f.url)
}

/**
 * Find and download the file after [currentUrl]: from the folder listing when
 * the server publishes one (it knows the real names), otherwise by bumping the
 * number in the file name and seeing whether that exists. Returns the final
 * URL and its text, or null when there is no next file. Blocking.
 *
 * [cookie] is the WebView's cookie header for the site, so files behind a
 * login the user has already done in the app are reachable here too.
 */
internal fun fetchNextTextFile(currentUrl: String, cookie: String?): Pair<String, String>? {
    val folder = folderOf(currentUrl)
    val listing = folder?.let { fetch(it, cookie, MAX_LISTING_BYTES) }
        ?.takeIf { it.contentType.lowercase().startsWith("text/html") }
    val nextUrl = if (listing != null) {
        // A listing is authoritative: if it has no next file, there isn't one.
        nextFromListing(currentUrl, listing.url, decodeText(listing.body, listing.contentType))
    } else {
        incrementedFileUrl(currentUrl)
    } ?: return null
    if (!isTextDocument(nextUrl)) return null
    val file = fetch(nextUrl, cookie, MAX_TEXT_BYTES)?.takeIf(::looksLikeText) ?: return null
    val text = decodeText(file.body, file.contentType)
    return if (text.isBlank()) null else file.url to text
}
