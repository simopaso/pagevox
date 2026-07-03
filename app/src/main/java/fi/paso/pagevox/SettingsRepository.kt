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

/** A visited or bookmarked page. [title] falls back to the URL when unknown. */
data class WebPage(val url: String, val title: String)

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
            prefs[Keys.LAST_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
            prefs[Keys.LAST_SENTENCE_INDEX] ?: 0,
            prefs[Keys.HOME_URL] ?: "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
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

    suspend fun addHistory(page: WebPage) = context.dataStore.edit { prefs ->
        val previous = decodePages(prefs[Keys.HISTORY]).filter { it.url != page.url }
        prefs[Keys.HISTORY] = encodePages((listOf(page) + previous).take(HISTORY_LIMIT))
    }

    /** Patch the title of the most recent history entry for [url], once known. */
    suspend fun updatePageTitle(url: String, title: String) = context.dataStore.edit { prefs ->
        val current = decodePages(prefs[Keys.HISTORY])
        if (current.firstOrNull()?.url == url && current.first().title != title) {
            prefs[Keys.HISTORY] = encodePages(listOf(WebPage(url, title)) + current.drop(1))
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
            pages.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
            return arr.toString()
        }

        fun decodePages(raw: String?): List<WebPage> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val url = o.getString("url")
                    WebPage(url, o.optString("title", url).ifBlank { url })
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
