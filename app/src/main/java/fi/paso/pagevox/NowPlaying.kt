package fi.paso.pagevox

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The page the player last had loaded, in the form the service needs to read it
 * again without a WebView.
 *
 * Sentences are extracted by JavaScript inside the page, so they only exist
 * while the activity is showing it. That was fine as long as playback could only
 * be started from the app — but a paused service is stopped by Android within
 * about a minute, and a headphone Play press after that starts a brand-new
 * process with no page, no WebView and no sentences. This snapshot is what lets
 * that press resume reading anyway (see PlaybackService.onPlaybackResumption).
 *
 * The reading *position* is deliberately not in here: it changes at every
 * sentence and already reaches disk through SettingsRepository, so this file is
 * written once per page rather than once per sentence.
 */
internal data class NowPlaying(
    val url: String,
    val title: String,
    val language: String?,
    val sentences: List<String>,
    val spoken: List<String>,
    val sectionStarts: List<Int>,
    val sectionTitles: List<String>,
    val imageUrl: String? = null
)

private const val NOW_PLAYING_VERSION = 1

internal fun NowPlaying.toJson(): String =
    JSONObject()
        .put("v", NOW_PLAYING_VERSION)
        .put("url", url)
        .put("title", title)
        .put("language", language ?: JSONObject.NULL)
        .put("sentences", JSONArray(sentences))
        .put("spoken", JSONArray(spoken))
        .put("sectionStarts", JSONArray(sectionStarts))
        .put("sectionTitles", JSONArray(sectionTitles))
        .put("imageUrl", imageUrl ?: JSONObject.NULL)
        .toString()

/** Null for anything unreadable or empty — resumption then simply doesn't
 *  happen, which is the right failure mode for a cache file. */
internal fun parseNowPlaying(text: String): NowPlaying? = try {
    val o = JSONObject(text)
    val sentences = o.getJSONArray("sentences").strings()
    val url = o.optString("url")
    if (sentences.isEmpty() || url.isBlank()) null
    else NowPlaying(
        url = url,
        title = o.optString("title"),
        language = if (o.isNull("language")) null else o.optString("language").ifBlank { null },
        sentences = sentences,
        spoken = o.optJSONArray("spoken")?.strings().orEmpty(),
        sectionStarts = o.optJSONArray("sectionStarts")?.ints().orEmpty(),
        sectionTitles = o.optJSONArray("sectionTitles")?.strings().orEmpty(),
        imageUrl = if (o.isNull("imageUrl")) null else o.optString("imageUrl").ifBlank { null }
    )
} catch (e: Exception) {
    null
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
private fun JSONArray.ints(): List<Int> = (0 until length()).map { optInt(it) }

/**
 * One small file in noBackupFilesDir: it is a cache of something that can
 * always be re-extracted from the page, so it has no place in a backup.
 * AtomicFile, because a process killed mid-write must leave the previous
 * snapshot intact rather than a truncated one.
 */
internal class NowPlayingStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "now_playing.json"))

    fun save(snapshot: NowPlaying) {
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.e("NowPlayingStore", "Couldn't open snapshot for writing", e)
            return
        }
        try {
            out.write(snapshot.toJson().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e("NowPlayingStore", "Couldn't write snapshot", e)
        }
    }

    fun load(): NowPlaying? = try {
        parseNowPlaying(String(file.readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
        null   // no snapshot yet: nothing has been played on this install
    }
}
