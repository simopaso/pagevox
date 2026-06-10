package fi.paso.pagevox

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.UUID

private const val TAG = "PlaybackService"

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var tts: TextToSpeech

    private var currentSentenceIndex = 0
    private var isTtsReady = false

    // The voice configured by the user in system TTS settings, captured at init.
    // Restored whenever the content language matches it.
    private var userDefaultVoice: Voice? = null
    // The language tag currently applied to [tts], to avoid redundant switches.
    private var appliedLanguageTag: String? = null
    // The user-picked voice name currently applied to [tts] (overrides language).
    private var appliedVoiceName: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Silent ExoPlayer track whose duration matches the estimated reading time of
    // the loaded sentences. ExoPlayer drives the notification progress bar from
    // this; we seek to each sentence's predicted start as TTS speaks it.
    private val DEFAULT_DURATION_MS = 60_000L
    private val DURATION_BUFFER_MS = 5_000L

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Don't force a language here: leaving the engine untouched means
                // it uses the voice the user selected in system TTS settings.
                // We remember that voice so we can restore it whenever the page
                // language matches it (see applyContentLanguage).
                userDefaultVoice = try { tts.voice ?: tts.defaultVoice } catch (e: Exception) { null }
                isTtsReady = true
                setupTtsListeners()
                Log.d(TAG, "TTS ready; default voice=${userDefaultVoice?.name} (${userDefaultVoice?.locale})")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) resumePlayback() else pausePlayback()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // If our duration estimate was short and TTS is still speaking
                // when the silent track ends, loop back near the end so the
                // notification doesn't disappear mid-read.
                if (playbackState == Player.STATE_ENDED && isTtsReady && tts.isSpeaking) {
                    val dur = player.duration
                    if (dur > 0) {
                        player.seekTo((dur - 2_000L).coerceAtLeast(0L))
                        player.playWhenReady = true
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
                player.clearMediaItems()
            }
        })

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@PlaybackService, MainActivity::class.java))
            getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        mediaSession = MediaSession.Builder(this, TtsSeekingPlayer())
            .setSessionActivity(pendingIntent)
            .setCallback(CustomSessionCallback())
            .build()
    }

    // ── ForwardingPlayer that maps notification seek-bar drags onto sentences ──
    //
    // The system MediaController (and the media-style notification) calls
    // seekTo(positionMs) when the user drags the progress bar. Our inner
    // ExoPlayer just plays silent audio, so a raw seek would do nothing useful
    // to the TTS. We translate the position back into a sentence index and
    // restart playback there. Our own internal player.seekTo() calls (in
    // startPlayback / TTS onStart) bypass this wrapper because they go through
    // the inner [player] reference directly.
    @androidx.annotation.OptIn(UnstableApi::class)
    private inner class TtsSeekingPlayer : ForwardingPlayer(player) {
        override fun seekTo(positionMs: Long) {
            handleSessionSeek(positionMs) { super.seekTo(it) }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            handleSessionSeek(positionMs) { super.seekTo(mediaItemIndex, it) }
        }

        private inline fun handleSessionSeek(positionMs: Long, fallback: (Long) -> Unit) {
            if (PlaybackDataRepository.sentences.isEmpty()) {
                fallback(positionMs)
                return
            }
            val target = PlaybackDataRepository.indexAtPositionMs(positionMs)
            mainHandler.post { startPlayback(target) }
        }
    }

    // ── TTS listener ──────────────────────────────────────────────────────────

    private fun setupTtsListeners() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    // Seek the silent track to this sentence's predicted start so
                    // the notification's progress bar advances per sentence.
                    val startMs = PlaybackDataRepository.getSentenceStartMs(currentSentenceIndex)
                    if (player.currentMediaItem != null) player.seekTo(startMs)
                    broadcastCurrentIndex()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    currentSentenceIndex++
                    if (player.playWhenReady) speakNextSentence()
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error on: $utteranceId")
                mainHandler.post { player.playWhenReady = false }
            }
        })
    }

    /**
     * Pick a TTS voice for the loaded page. The user's system-selected voice is
     * honored whenever the page language matches it (or the page declares no
     * language); only a genuinely different content language causes a switch to
     * that language's voice. Results are memoized so we don't reconfigure the
     * engine on every playback start for the same language.
     */
    private fun applyContentLanguage() {
        if (!isTtsReady) return
        if (userDefaultVoice == null) {
            userDefaultVoice = try { tts.voice ?: tts.defaultVoice } catch (e: Exception) { null }
        }

        // A voice the user explicitly picked in-app overrides content-aware
        // switching entirely — honor it on every page regardless of language.
        val selectedName = PlaybackDataRepository.selectedVoiceName?.takeIf { it.isNotBlank() }
        if (selectedName != null) {
            if (selectedName == appliedVoiceName) return
            val voice = try { tts.voices?.firstOrNull { it.name == selectedName } } catch (e: Exception) { null }
            if (voice != null) {
                try {
                    tts.voice = voice
                    appliedVoiceName = selectedName
                    appliedLanguageTag = null   // force a re-evaluation if the user reverts to default
                    Log.d(TAG, "Applied user-selected voice '$selectedName'")
                } catch (e: Exception) {
                    // Some 3rd-party engines throw when reconfigured after the
                    // service has been backgrounded — don't take the process down.
                    Log.e(TAG, "Failed to apply voice '$selectedName'", e)
                }
            }
            return
        }
        appliedVoiceName = null

        val tag = PlaybackDataRepository.language?.takeIf { it.isNotBlank() }
        if (tag == appliedLanguageTag) return

        val defaultVoice = userDefaultVoice
        val defaultLang = defaultVoice?.locale?.language ?: Locale.getDefault().language
        val pageLocale = tag?.let { Locale.forLanguageTag(it) }?.takeIf { it.language.isNotBlank() }

        try {
            when {
                // No declared language, or same language as the user's voice → keep
                // the user's chosen voice.
                pageLocale == null || pageLocale.language == defaultLang -> {
                    if (defaultVoice != null) tts.voice = defaultVoice
                    else if (pageLocale != null) tts.setLanguage(pageLocale)
                }
                // Different language → switch to it (engine default voice for that
                // locale). Fall back to the user's voice if unavailable.
                else -> {
                    val res = tts.setLanguage(pageLocale)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Content language '$tag' unsupported; using default voice")
                        if (defaultVoice != null) tts.voice = defaultVoice
                    } else {
                        Log.d(TAG, "Switched TTS to content language '$tag'")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply content language '$tag'", e)
        }
        appliedLanguageTag = tag
    }

    // ── Silent-player helpers ─────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun setupSilentPlayer() {
        val totalMs = PlaybackDataRepository.totalDurationMs
        val durationMs = if (totalMs > 0) totalMs + DURATION_BUFFER_MS else DEFAULT_DURATION_MS
        val source = SilenceMediaSource.Factory()
            .setDurationUs(durationMs * 1_000L)
            .createMediaSource()
        player.setMediaSource(source)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
    }

    @OptIn(UnstableApi::class)
    private fun ensureSilentPlayer() {
        if (player.currentMediaItem == null) setupSilentPlayer()
    }

    // ── Playback control ──────────────────────────────────────────────────────

    /**
     * Called when the system/lock-screen resumes playback (playWhenReady → true).
     * Only speaks if TTS is idle; avoids double-speaking when startPlayback() already
     * queued an utterance.
     */
    private fun resumePlayback() {
        if (!isTtsReady) return
        ensureSilentPlayer()
        if (!tts.isSpeaking) speakNextSentence()
    }

    private fun pausePlayback() {
        if (!isTtsReady) return
        // tts.stop() can throw IllegalStateException on some engines (notably
        // Samsung's) when the service has been backgrounded while paused and
        // the engine has been partially reclaimed by the system. Swallow it
        // so a pause never crashes the process — the worst case is that the
        // current utterance finishes naturally.
        try { tts.stop() } catch (e: Exception) { Log.e(TAG, "tts.stop() failed", e) }
    }

    /**
     * Starts fresh playback from [index].
     *
     * Two cases:
     *  - player was paused (playWhenReady=false): flip it to true → onPlayWhenReadyChanged
     *    fires → resumePlayback() → speakNextSentence().
     *  - player was already playing (playWhenReady=true): onPlayWhenReadyChanged won't
     *    fire again, so we speak the first sentence directly here.
     */
    private fun startPlayback(index: Int) {
        if (isTtsReady) try { tts.stop() } catch (e: Exception) { Log.e(TAG, "tts.stop() failed", e) }
        applyContentLanguage()
        currentSentenceIndex = index
        // Rebuild the silent track with the current sentence list's estimated
        // total duration so notification progress reflects the loaded page.
        setupSilentPlayer()
        val startMs = PlaybackDataRepository.getSentenceStartMs(index)
        if (startMs > 0) player.seekTo(startMs)
        if (!player.playWhenReady) {
            player.playWhenReady = true   // → onPlayWhenReadyChanged → resumePlayback()
        } else {
            speakNextSentence()           // player was already playing, trigger directly
        }
    }

    private fun stopPlayback() {
        if (isTtsReady) try { tts.stop() } catch (e: Exception) { Log.e(TAG, "tts.stop() failed", e) }
        currentSentenceIndex = 0
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    private fun speakNextSentence() {
        val sentence = PlaybackDataRepository.getSentence(currentSentenceIndex)
        if (sentence != null) {
            // Re-evaluate voice/language so a mid-session voice change (or revert
            // to default) applies at this sentence boundary; memoized, so cheap.
            applyContentLanguage()
            // Wrap the engine calls: a misbehaving TTS engine throwing here used
            // to take the whole process down. Treat as a soft stop instead.
            try {
                tts.setSpeechRate(PlaybackDataRepository.speechRate)
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            } catch (e: Exception) {
                Log.e(TAG, "tts.speak() failed", e)
                player.playWhenReady = false
            }
        } else {
            Log.d(TAG, "End of sentences")
            currentSentenceIndex = 0
            broadcastCurrentIndex()   // tell UI to reset highlight to the start
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
        }
    }

    /** Jump [delta] sentences from the current position and (re)start playback.
     *  The service holds the authoritative index, so prev/next are exact. */
    private fun skipSentences(delta: Int) {
        val count = PlaybackDataRepository.sentences.size
        if (count == 0) return
        val target = (currentSentenceIndex + delta).coerceIn(0, count - 1)
        startPlayback(target)
    }

    private fun broadcastCurrentIndex() {
        val bundle = Bundle().apply { putInt("index", currentSentenceIndex) }
        mediaSession?.broadcastCustomCommand(SessionCommand("updateIndex", bundle), bundle)
    }

    // ── MediaSessionService ───────────────────────────────────────────────────

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service alive while actively playing (user may still be listening).
        // Stop it when paused so we don't leave an idle foreground service forever.
        if (!player.playWhenReady) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        if (::tts.isInitialized) {
            try { tts.stop() } catch (e: Exception) { Log.e(TAG, "tts.stop() failed", e) }
            try { tts.shutdown() } catch (e: Exception) { Log.e(TAG, "tts.shutdown() failed", e) }
        }
        super.onDestroy()
    }

    // ── Session callback ──────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private inner class CustomSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = super.onConnect(session, controller)
                .availableSessionCommands.buildUpon()
                .add(SessionCommand("playSentences", Bundle.EMPTY))
                .add(SessionCommand("updateIndex",   Bundle.EMPTY))
                .add(SessionCommand("stopPlayback",  Bundle.EMPTY))
                .add(SessionCommand("skipNext",      Bundle.EMPTY))
                .add(SessionCommand("skipPrevious",  Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            super.onPostConnect(session, controller)
            // Re-broadcast the current sentence index so an activity returning
            // from background gets the latest reading position without having
            // to wait for the next TTS sentence boundary.
            mainHandler.post {
                if (PlaybackDataRepository.sentences.isNotEmpty()) {
                    broadcastCurrentIndex()
                }
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "playSentences" -> mainHandler.post { startPlayback(args.getInt("startIndex", 0)) }
                "stopPlayback"  -> mainHandler.post { stopPlayback() }
                "skipNext"      -> mainHandler.post { skipSentences(1) }
                "skipPrevious"  -> mainHandler.post { skipSentences(-1) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
