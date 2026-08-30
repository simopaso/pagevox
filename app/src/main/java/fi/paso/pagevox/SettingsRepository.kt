package fi.paso.pagevox

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "settings")

// WebView text zoom is a percentage (100 = normal). Bounded so the page stays
// usable at the extremes; each button press moves one step.
internal const val DEFAULT_TEXT_ZOOM = 100
internal const val MIN_TEXT_ZOOM = 50
internal const val MAX_TEXT_ZOOM = 300
internal const val TEXT_ZOOM_STEP = 10

// TTS speech rate multiplier (1.0 = the engine's normal pace). The presets the
// speed control cycles through.
internal const val DEFAULT_SPEECH_RATE = 1.0f
val SPEECH_RATE_PRESETS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * The bundled user manual (`app/src/main/assets/manual*.html`), loaded straight
 * out of the APK. It is the factory-default home page and the page a fresh
 * install opens, so the app explains itself — read aloud by its own reader.
 *
 * Served over `file:///android_asset/` deliberately: assets stay reachable
 * through that path even though WebView's `allowFileAccess` defaults to false
 * on targetSdk 30 and above. Don't "fix" a manual that fails to load by turning
 * file access on — that would open the whole filesystem to every page visited.
 */
private const val MANUAL_ASSET_PREFIX = "file:///android_asset/manual"

/** Languages the manual has been translated into. Each needs a matching
 *  `assets/manual-<tag>.html`; anything else gets the English original. */
private val MANUAL_TRANSLATIONS = setOf("fi", "sv", "de", "fr")

/** The manual in the app's current language. Each translated file declares its
 *  own `<html lang>`, so the reader also picks a matching TTS voice for it. */
fun manualUrl(): String = manualUrlForLanguage(Locale.getDefault().language)

/** Pure half of [manualUrl], split out so it can be unit-tested. */
internal fun manualUrlForLanguage(language: String?): String {
    val tag = language?.lowercase().orEmpty()
    return if (tag in MANUAL_TRANSLATIONS) "$MANUAL_ASSET_PREFIX-$tag.html"
    else "$MANUAL_ASSET_PREFIX.html"
}

/** True for any language's manual. Used to re-resolve a stored home page when
 *  the device language has changed since it was saved, so a user who switches
 *  their phone to Swedish doesn't keep landing on the Finnish manual. */
internal fun isManualUrl(url: String): Boolean = url.startsWith(MANUAL_ASSET_PREFIX)

data class UserPreferences(
    val lastUrl: String,
    val lastSentenceIndex: Int,
    val homeUrl: String,
    val forceDarkWeb: Boolean,
    val textZoom: Int,
    val speechRate: Float,
    val readerMode: Boolean,
    val followAlong: Boolean,
    val selectedVoice: String
)

/** A visited or bookmarked page. [title] falls back to the URL when unknown.
 *  [position] is the sentence index reading last stopped at on that page, so
 *  every page in history remembers its own resume point.
 *
 *  [sentenceCount] and [remainingMs] are recorded alongside it so the library can
 *  show how far through a page you are, and roughly how long is left, without
 *  having to load and re-extract it. [remainingMs] is the estimate at 1× speed —
 *  divide by the current speech rate to display. Both are 0 on entries written
 *  before this was tracked, and on pages that have never been read. */
data class WebPage(
    val url: String,
    val title: String,
    val position: Int = 0,
    val sentenceCount: Int = 0,
    val remainingMs: Long = 0L
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val LAST_URL = stringPreferencesKey("last_url")
        val LAST_SENTENCE_INDEX = intPreferencesKey("last_sentence_index")
        val HOME_URL = stringPreferencesKey("home_url")
        val HISTORY = stringPreferencesKey("history")
        val BOOKMARKS = stringPreferencesKey("bookmarks")
        val FORCE_DARK_WEB = booleanPreferencesKey("force_dark_web")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val READER_MODE = booleanPreferencesKey("reader_mode")
        val FOLLOW_ALONG = booleanPreferencesKey("follow_along")
        val SELECTED_VOICE = stringPreferencesKey("selected_voice")
    }

    val prefsFlow = context.dataStore.data.map { prefs ->
        UserPreferences(
            // A blank saved value counts as "not set": clearing the home page
            // field in Settings is how the user asks for the manual back, and a
            // fresh install has neither key, so it opens on the manual too.
            prefs[Keys.LAST_URL]?.takeIf { it.isNotBlank() } ?: manualUrl(),
            prefs[Keys.LAST_SENTENCE_INDEX] ?: 0,
            // Any stored manual is re-resolved to the current language, so the
            // home page follows a device-language change instead of pinning the
            // language that happened to be set when it was saved.
            prefs[Keys.HOME_URL]?.takeIf { it.isNotBlank() && !isManualUrl(it) } ?: manualUrl(),
            prefs[Keys.FORCE_DARK_WEB] ?: false,
            prefs[Keys.TEXT_ZOOM] ?: DEFAULT_TEXT_ZOOM,
            prefs[Keys.SPEECH_RATE] ?: DEFAULT_SPEECH_RATE,
            prefs[Keys.READER_MODE] ?: false,
            prefs[Keys.FOLLOW_ALONG] ?: true,
            prefs[Keys.SELECTED_VOICE] ?: ""
        )
    }

    // Most-recent-first list of visited pages, used for the History view and
    // address-bar autocomplete. Stored as a JSON array of {url, title}.
    val historyFlow = context.dataStore.data.map { decodePages(it[Keys.HISTORY]) }

    // User-saved pages, newest first.
    val bookmarksFlow = context.dataStore.data.map { decodePages(it[Keys.BOOKMARKS]) }

    suspend fun updateLastUrl(url: String) = context.dataStore.edit { it[Keys.LAST_URL] = url }
    suspend fun updateLastIndex(index: Int) = context.dataStore.edit { it[Keys.LAST_SENTENCE_INDEX] = index }
    suspend fun updateHomeUrl(url: String) = context.dataStore.edit { it[Keys.HOME_URL] = url }
    suspend fun updateForceDarkWeb(enabled: Boolean) = context.dataStore.edit { it[Keys.FORCE_DARK_WEB] = enabled }
    suspend fun updateTextZoom(zoom: Int) = context.dataStore.edit { it[Keys.TEXT_ZOOM] = zoom }
    suspend fun updateSpeechRate(rate: Float) = context.dataStore.edit { it[Keys.SPEECH_RATE] = rate }
    suspend fun updateReaderMode(enabled: Boolean) = context.dataStore.edit { it[Keys.READER_MODE] = enabled }
    suspend fun updateFollowAlong(enabled: Boolean) = context.dataStore.edit { it[Keys.FOLLOW_ALONG] = enabled }
    suspend fun updateSelectedVoice(name: String) = context.dataStore.edit { it[Keys.SELECTED_VOICE] = name }

    /** Move [page] to the top of history. A revisit (matched on the normalized
     *  URL, so redirect variants don't duplicate) keeps the known title and the
     *  saved reading position of the old entry. */
    suspend fun addHistory(page: WebPage) = context.dataStore.edit { prefs ->
        val existing = decodePages(prefs[Keys.HISTORY])
        val key = normalizeUrlForCompare(page.url)
        val old = existing.firstOrNull { normalizeUrlForCompare(it.url) == key }
        val merged = page.copy(
            title = if (page.title == page.url && old != null) old.title else page.title,
            position = old?.position ?: page.position,
            // Revisiting a page must not wipe its progress readout: the caller
            // only knows the URL, so carry the old counts until the page is
            // extracted again and updateReadingPosition refreshes them.
            sentenceCount = old?.sentenceCount ?: page.sentenceCount,
            remainingMs = old?.remainingMs ?: page.remainingMs
        )
        val rest = existing.filter { normalizeUrlForCompare(it.url) != key }
        prefs[Keys.HISTORY] = encodePages((listOf(merged) + rest).take(HISTORY_LIMIT))
    }

    /** Patch the title of the most recent history entry for [url], once known. */
    suspend fun updatePageTitle(url: String, title: String) = context.dataStore.edit { prefs ->
        val current = decodePages(prefs[Keys.HISTORY])
        if (current.firstOrNull()?.url == url && current.first().title != title) {
            prefs[Keys.HISTORY] = encodePages(listOf(current.first().copy(title = title)) + current.drop(1))
        }
    }

    /** Persist the reading position: the global last-index (relaunch restore)
     *  and the per-page position on [url]'s history entry, in one edit — this
     *  runs at every sentence boundary, so don't write the file twice.
     *  [sentenceCount] and [remainingMs] come along for the ride so the library's
     *  "Continue listening" shelf can show progress for a page that isn't
     *  loaded; both are ignored when 0 (nothing extracted yet) rather than
     *  overwriting good values with zeroes. */
    suspend fun updateReadingPosition(
        url: String,
        index: Int,
        sentenceCount: Int = 0,
        remainingMs: Long = 0L
    ) = context.dataStore.edit { prefs ->
        prefs[Keys.LAST_SENTENCE_INDEX] = index
        val key = normalizeUrlForCompare(url)
        val current = decodePages(prefs[Keys.HISTORY])
        if (current.any { normalizeUrlForCompare(it.url) == key }) {
            prefs[Keys.HISTORY] = encodePages(current.map {
                if (normalizeUrlForCompare(it.url) == key) {
                    it.copy(
                        position = index,
                        sentenceCount = if (sentenceCount > 0) sentenceCount else it.sentenceCount,
                        remainingMs = if (sentenceCount > 0) remainingMs else it.remainingMs
                    )
                } else it
            })
        }
    }

    suspend fun clearHistory() = context.dataStore.edit { it.remove(Keys.HISTORY) }

    suspend fun addBookmark(page: WebPage) = context.dataStore.edit { prefs ->
        val previous = decodePages(prefs[Keys.BOOKMARKS]).filter { it.url != page.url }
        prefs[Keys.BOOKMARKS] = encodePages(listOf(page) + previous)
    }

    suspend fun removeBookmark(url: String) = context.dataStore.edit { prefs ->
        prefs[Keys.BOOKMARKS] = encodePages(decodePages(prefs[Keys.BOOKMARKS]).filter { it.url != url })
    }

    private companion object {
        const val HISTORY_LIMIT = 100

        fun encodePages(pages: List<WebPage>): String {
            val arr = JSONArray()
            pages.forEach {
                arr.put(
                    JSONObject()
                        .put("url", it.url)
                        .put("title", it.title)
                        .put("position", it.position)
                        .put("total", it.sentenceCount)
                        .put("remainingMs", it.remainingMs)
                )
            }
            return arr.toString()
        }

        fun decodePages(raw: String?): List<WebPage> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val url = o.getString("url")
                    WebPage(
                        url,
                        o.optString("title", url).ifBlank { url },
                        o.optInt("position", 0),
                        o.optInt("total", 0),
                        o.optLong("remainingMs", 0L)
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
