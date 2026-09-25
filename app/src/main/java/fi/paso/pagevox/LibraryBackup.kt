package fi.paso.pagevox

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything a user would lose on an uninstall, as a file they can keep.
 *
 * Android's own auto-backup already covers the same DataStore file, but it can't
 * be relied on for the moments that matter most: it restores nothing into an app
 * signed with a different certificate (a signing-key change means a reinstall
 * from empty), it needs a Google account with backup switched on, and it gives
 * the user no copy of their own. A plain JSON file through the system file
 * picker has none of those limits.
 *
 * Every settings field is nullable, meaning "not in this backup — leave the
 * current value alone". That is what lets an older app read a newer backup (and
 * vice versa) without clobbering anything it doesn't understand.
 */
internal data class LibraryBackup(
    val homeUrl: String? = null,
    val lastUrl: String? = null,
    val lastSentenceIndex: Int? = null,
    val forceDarkWeb: Boolean? = null,
    val textZoom: Int? = null,
    val speechRate: Float? = null,
    val readerMode: Boolean? = null,
    val followAlong: Boolean? = null,
    val selectedVoice: String? = null,
    val history: List<WebPage> = emptyList(),
    val bookmarks: List<WebPage> = emptyList()
)

/** Thrown for a file that isn't a PageVox backup, or is one we can't read. */
internal class InvalidBackupException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// Identifies the file as ours, so importing some unrelated JSON the user picked
// by mistake fails loudly instead of "restoring" an empty library over theirs.
private const val BACKUP_FORMAT = "pagevox-backup"
private const val BACKUP_VERSION = 1

internal fun LibraryBackup.toJson(appVersion: String, exportedAt: String): String {
    val settings = JSONObject()
    homeUrl?.let { settings.put("homeUrl", it) }
    lastUrl?.let { settings.put("lastUrl", it) }
    lastSentenceIndex?.let { settings.put("lastSentenceIndex", it) }
    forceDarkWeb?.let { settings.put("forceDarkWeb", it) }
    textZoom?.let { settings.put("textZoom", it) }
    speechRate?.let { settings.put("speechRate", it.toDouble()) }
    readerMode?.let { settings.put("readerMode", it) }
    followAlong?.let { settings.put("followAlong", it) }
    selectedVoice?.let { settings.put("selectedVoice", it) }
    return JSONObject()
        .put("format", BACKUP_FORMAT)
        .put("version", BACKUP_VERSION)
        .put("appVersion", appVersion)
        .put("exportedAt", exportedAt)
        .put("settings", settings)
        .put("history", pagesToJson(history))
        .put("bookmarks", pagesToJson(bookmarks))
        .toString(2)
}

/**
 * Read a backup file back. Values are sanity-clamped on the way in rather than
 * trusted: the file is plain text the user could have edited, and a text zoom
 * of 5000% or a negative reading position would otherwise go straight into the
 * WebView and the player.
 */
internal fun parseLibraryBackup(text: String): LibraryBackup {
    val root = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw InvalidBackupException("Not JSON", e)
    }
    if (root.optString("format") != BACKUP_FORMAT) {
        throw InvalidBackupException("Not a PageVox backup")
    }
    // A future version is still worth reading: every field is optional and
    // unknown ones are ignored, so it degrades to "restore what we understand".
    val settings = root.optJSONObject("settings") ?: JSONObject()
    return LibraryBackup(
        homeUrl = settings.optStringOrNull("homeUrl"),
        lastUrl = settings.optStringOrNull("lastUrl"),
        lastSentenceIndex = settings.optIntOrNull("lastSentenceIndex")?.coerceAtLeast(0),
        forceDarkWeb = settings.optBooleanOrNull("forceDarkWeb"),
        textZoom = settings.optIntOrNull("textZoom")?.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM),
        speechRate = settings.optDoubleOrNull("speechRate")?.toFloat()?.coerceIn(0.5f, 3.0f),
        readerMode = settings.optBooleanOrNull("readerMode"),
        followAlong = settings.optBooleanOrNull("followAlong"),
        selectedVoice = settings.optStringOrNull("selectedVoice"),
        history = pagesFromJson(root.optJSONArray("history")),
        bookmarks = pagesFromJson(root.optJSONArray("bookmarks"))
    )
}

// ── WebPage <-> JSON ─────────────────────────────────────────────────────────
//
// One encoding for both the DataStore entries and the backup file, so a page
// round-trips through a backup exactly as it is stored. The short field name
// "total" for the sentence count is historical (it is what DataStore already
// holds on existing installs) and must not be renamed.

internal fun pageToJson(page: WebPage): JSONObject =
    JSONObject()
        .put("url", page.url)
        .put("title", page.title)
        .put("position", page.position)
        .put("total", page.sentenceCount)
        .put("remainingMs", page.remainingMs)

/** Null for an entry with no usable URL, so one bad row can't sink the list. */
internal fun pageFromJson(o: JSONObject): WebPage? {
    val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
    return WebPage(
        url = url,
        title = o.optString("title", url).ifBlank { url },
        position = o.optInt("position", 0).coerceAtLeast(0),
        sentenceCount = o.optInt("total", 0).coerceAtLeast(0),
        remainingMs = o.optLong("remainingMs", 0L).coerceAtLeast(0L)
    )
}

internal fun pagesToJson(pages: List<WebPage>): JSONArray =
    JSONArray().apply { pages.forEach { put(pageToJson(it)) } }

internal fun pagesFromJson(arr: JSONArray?): List<WebPage> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::pageFromJson) }
}

// org.json's opt* getters return a default (0, false, "") for a missing key,
// which is indistinguishable from a real 0/false/"" — and here "missing" has to
// mean "leave the current setting alone".
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) (opt(key) as? Number)?.toInt() else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) (opt(key) as? Number)?.toDouble() else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) opt(key) as? Boolean else null
