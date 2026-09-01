package fi.paso.pagevox

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Intent
import android.media.AudioManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private const val TAG = "PlaybackService"

// A short silence spoken before the first sentence of a section, so a heading
// lands as a chapter break instead of running straight out of the previous
// paragraph. It is queued as its own utterance, hence the id prefix: the
// progress listener must ignore it, or the silence's onDone would advance the
// sentence index and skip the heading it was meant to introduce.
private const val SECTION_PAUSE_MS = 400L
private const val SILENCE_UTTERANCE_PREFIX = "gap-"

// How often the audio mode is sampled while there is something to pause or
// resume. One binder call a second, and only while playing (or parked waiting
// for a call to end) — see [PlaybackService.isInCall].
private const val CALL_POLL_MS = 1_000L

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var tts: TextToSpeech

    private var currentSentenceIndex = 0
    private var isTtsReady = false
    // A playSentences command received before the TTS engine finished
    // initializing. Replayed at the end of the init callback so the user
    // isn't left tapping Play to no effect on a cold service start.
    private var pendingStartIndex: Int? = null

    // The voice configured by the user in system TTS settings, captured at init.
    // Restored whenever the content language matches it.
    private var userDefaultVoice: Voice? = null
    // The language tag currently applied to [tts], to avoid redundant switches.
    private var appliedLanguageTag: String? = null
    // The user-picked voice name currently applied to [tts] (overrides language).
    private var appliedVoiceName: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    // Set when playback was paused by a call rather than by the user, so that
    // the end of the call resumes the page — and a manual pause during the
    // call does not get undone when it ends.
    private var pausedForCall = false
    // Guards the one playWhenReady=false we issue ourselves, so the listener
    // can tell our pause apart from the user's.
    private var togglingForCall = false

    // Owns persistence of the reading position. Deliberately not routed through
    // the Activity/MediaController — that connection only exists while the app
    // is in the foreground (MainActivity tears it down in onStop()), so a pause
    // triggered from the lock-screen/notification while the app is backgrounded
    // would otherwise never reach disk. A SupervisorJob scoped to the service's
    // own lifecycle (cancelled in onDestroy) survives Activity teardown.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepo: SettingsRepository

    // Silent ExoPlayer track whose duration matches the estimated reading time of
    // the loaded sentences. ExoPlayer drives the notification progress bar from
    // this; we seek to each sentence's predicted start as TTS speaks it.
    private val DEFAULT_DURATION_MS = 60_000L
    private val DURATION_BUFFER_MS = 5_000L

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        settingsRepo = SettingsRepository(applicationContext)
        // Adopt the position preserved in the process-scoped singleton. When a
        // paused service is destroyed and later recreated (same process), this
        // is how the new instance resumes where the old one left off instead of
        // restarting from sentence 0. On a genuinely fresh process the singleton
        // is 0 and the position is seeded from disk when the page is extracted.
        currentSentenceIndex = PlaybackDataRepository.currentIndex

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
                // If the user tapped Play before init completed, honor it now.
                pendingStartIndex?.let { idx ->
                    pendingStartIndex = null
                    mainHandler.post { startPlayback(idx) }
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
                pendingStartIndex = null
            }
        }

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // A pause we did not issue (the user tapping pause while a call is
                // running) cancels the pending auto-resume: we only ever resume
                // playback we stopped ourselves.
                if (!playWhenReady && !togglingForCall) pausedForCall = false
                if (playWhenReady) resumePlayback() else pausePlayback()
            }

            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                // The player carries nothing but silence; the audio the user hears
                // comes from the TTS engine, which knows nothing about audio focus.
                // So a transient focus loss (an incoming call ringing, another app's
                // spoken prompt) suppresses the silent track while the engine talks
                // straight over the top of it — playWhenReady stays true, and this
                // is the only callback we get. Mirror the suppression onto the
                // engine so the narration actually stops.
                if (!player.playWhenReady) return
                if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    // Focus is back — but on a Bluetooth headset that happens as soon
                    // as the *ringtone* stops, i.e. the moment the call is answered,
                    // because the call itself runs on the SCO/voice path and does not
                    // hold media focus. Resuming here is exactly how the narration
                    // ended up playing over the conversation; stay quiet until the
                    // call is genuinely over.
                    if (isInCall()) pauseForCall() else resumePlayback()
                } else {
                    pausePlayback()
                }
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
                if (utteranceId.isSectionGap()) return
                mainHandler.post {
                    // Seek the silent track to this sentence's predicted start so
                    // the notification's progress bar advances per sentence.
                    val startMs = PlaybackDataRepository.getSentenceStartMs(currentSentenceIndex)
                    if (player.currentMediaItem != null) player.seekTo(startMs)
                    broadcastCurrentIndex()
                    persistPosition()
                }
            }

            override fun onDone(utteranceId: String?) {
                // The section gap is part of the *current* sentence's playback,
                // not a finished sentence — advancing here would skip a heading.
                if (utteranceId.isSectionGap()) return
                mainHandler.post {
                    currentSentenceIndex++
                    if (player.playWhenReady) speakNextSentence()
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId.isSectionGap()) return
                Log.e(TAG, "TTS error on: $utteranceId")
                mainHandler.post { player.playWhenReady = false }
            }
        })
    }

    private fun String?.isSectionGap() = this?.startsWith(SILENCE_UTTERANCE_PREFIX) == true

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
        startCallWatch()
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
        // Belt-and-braces: onStart already persisted this sentence's index when
        // it began speaking, but a pause is exactly the moment a task-removal
        // teardown (onTaskRemoved stops the service when paused) might follow
        // within moments, so make sure the write is in flight right now too.
        persistPosition()
    }

    // ── Phone calls ──────────────────────────────────────────────────────────
    //
    // The player's own audio-focus handling cannot carry this alone, for two
    // reasons. TTS audio never passes through the player, so focus changes do not
    // reach the engine at all (that half is handled in the suppression callback
    // above). And a call over a Bluetooth headset runs on the voice/SCO path,
    // releasing media focus the instant the ringtone stops — so focus says "you
    // may play again" in the middle of the call, which is the bug: the page kept
    // being read over the conversation.
    //
    // The audio mode is the one signal that stays raised for the whole call, for
    // telephony and VoIP alike, and reading it needs no permission (unlike
    // TelephonyManager's call state, which wants READ_PHONE_STATE on API 31+).
    // We pause on it and resume when the phone returns to MODE_NORMAL.

    /** True while the phone is ringing or in a call of any kind. Every mode above
     *  MODE_NORMAL is a call mode — ringtone, telephony, VoIP, call screening, or
     *  one of the redirect modes added in later API levels — so comparing against
     *  NORMAL rather than listing constants stays correct as that list grows. */
    private fun isInCall(): Boolean {
        val mode = try { audioManager?.mode } catch (e: Exception) { null } ?: return false
        return mode > AudioManager.MODE_NORMAL
    }

    /** Pause for a call, routed through playWhenReady so the notification and
     *  lock-screen controls show paused too and the position is persisted. */
    private fun pauseForCall() {
        if (!player.playWhenReady) return
        Log.d(TAG, "Pausing for call (audio mode=${audioManager?.mode})")
        pausedForCall = true
        togglingForCall = true
        try { player.playWhenReady = false } finally { togglingForCall = false }
    }

    private fun onAudioModeChanged() {
        if (isInCall()) {
            pauseForCall()
        } else if (pausedForCall) {
            Log.d(TAG, "Call over, resuming")
            pausedForCall = false
            player.playWhenReady = true
        }
    }

    /** There is no mode-change callback before API 31, so sample it instead. The
     *  loop runs only while playing or while parked mid-call, and stops itself as
     *  soon as neither is true. Noticing a call *start* up to a second late costs
     *  nothing in practice — the focus loss above catches the ring immediately;
     *  this is what notices the call ending. */
    private fun startCallWatch() {
        mainHandler.removeCallbacks(callWatchRunnable)
        mainHandler.post(callWatchRunnable)
    }

    private val callWatchRunnable = object : Runnable {
        override fun run() {
            onAudioModeChanged()
            if (player.playWhenReady || pausedForCall) {
                mainHandler.postDelayed(this, CALL_POLL_MS)
            }
        }
    }

    /** Save the current sentence index against the loaded page's URL, straight
     *  to disk from the service — not routed through the Activity/ViewModel, so
     *  it lands even when no MediaController is connected (app backgrounded or
     *  paused from the lock-screen notification). See [serviceScope]. */
    private fun persistPosition() {
        // Publish to the in-process singleton first: this is what a resume reads
        // when the process is still alive but this service instance has been
        // (or is about to be) destroyed, so it must be updated even when there's
        // no pageUrl to write to disk against.
        PlaybackDataRepository.currentIndex = currentSentenceIndex
        val url = PlaybackDataRepository.pageUrl ?: return
        // Carried along so the library can show progress and time-left for this
        // page later, without loading and re-extracting it.
        val total = PlaybackDataRepository.sentences.size
        val remainingMs = PlaybackDataRepository.baseRemainingMsFrom(currentSentenceIndex)
        serviceScope.launch {
            settingsRepo.updateReadingPosition(url, currentSentenceIndex, total, remainingMs)
        }
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
        if (!isTtsReady) {
            // Defer until the engine finishes initializing.
            pendingStartIndex = index
            return
        }
        try { tts.stop() } catch (e: Exception) { Log.e(TAG, "tts.stop() failed", e) }
        // An explicit play/seek supersedes any pending post-call resume.
        pausedForCall = false
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
        pausedForCall = false
        currentSentenceIndex = 0
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    private fun speakNextSentence() {
        // Speak the narration-cleaned form of the sentence (citation markers
        // stripped, URLs shortened, abbreviations expanded); the verbatim text
        // stays in the repository for the UI and the follow-along highlight.
        val sentence = PlaybackDataRepository.getSpokenSentence(currentSentenceIndex)
        if (sentence != null) {
            // Re-evaluate voice/language so a mid-session voice change (or revert
            // to default) applies at this sentence boundary; memoized, so cheap.
            applyContentLanguage()
            // Wrap the engine calls: a misbehaving TTS engine throwing here used
            // to take the whole process down. Treat as a soft stop instead.
            try {
                tts.setSpeechRate(PlaybackDataRepository.speechRate)
                // A heading gets a beat of silence in front of it. The gap is
                // flushed in first and the sentence appended after it, so the
                // pair plays as one uninterrupted unit.
                val opensSection = currentSentenceIndex > 0 &&
                    PlaybackDataRepository.isSectionStart(currentSentenceIndex)
                if (opensSection) {
                    tts.playSilentUtterance(
                        SECTION_PAUSE_MS,
                        TextToSpeech.QUEUE_FLUSH,
                        SILENCE_UTTERANCE_PREFIX + UUID.randomUUID()
                    )
                }
                val queueMode = if (opensSection) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                tts.speak(sentence, queueMode, null, UUID.randomUUID().toString())
            } catch (e: Exception) {
                Log.e(TAG, "tts.speak() failed", e)
                player.playWhenReady = false
            }
        } else {
            Log.d(TAG, "End of sentences")
            currentSentenceIndex = 0
            // Tell the UI playback has finished so it can clear the highlight.
            // We send a distinct command (not updateIndex=0) so the UI does NOT
            // persist 0 over the user's last-known position — that would mean a
            // page finishing in the background wipes the resume point.
            mediaSession?.broadcastCustomCommand(
                SessionCommand("playbackEnded", Bundle.EMPTY), Bundle.EMPTY
            )
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

    /** Jump to the next/previous page section (heading). Going back before the
     *  first heading lands on the top of the page rather than doing nothing —
     *  the text ahead of a page's first heading is real content. Skipping past
     *  the last section is a no-op: there is nothing after it to jump to. */
    private fun skipSection(forward: Boolean) {
        if (PlaybackDataRepository.sentences.isEmpty()) return
        if (PlaybackDataRepository.sectionStarts.isEmpty()) {
            // No headings on this page — fall back to a plain sentence skip so
            // the gesture never feels dead.
            skipSentences(if (forward) 1 else -1)
            return
        }
        val target = if (forward) {
            PlaybackDataRepository.nextSectionStart(currentSentenceIndex) ?: return
        } else {
            PlaybackDataRepository.previousSectionStart(currentSentenceIndex) ?: 0
        }
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
        mainHandler.removeCallbacks(callWatchRunnable)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        if (::tts.isInitialized) {
            try { tts.stop() } catch (e: Exception) { Log.e(TAG, "tts.stop() failed", e) }
            try { tts.shutdown() } catch (e: Exception) { Log.e(TAG, "tts.shutdown() failed", e) }
        }
        serviceScope.cancel()
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
                .add(SessionCommand("playbackEnded", Bundle.EMPTY))
                .add(SessionCommand("stopPlayback",  Bundle.EMPTY))
                .add(SessionCommand("skipNext",      Bundle.EMPTY))
                .add(SessionCommand("skipPrevious",  Bundle.EMPTY))
                .add(SessionCommand("skipNextSection",     Bundle.EMPTY))
                .add(SessionCommand("skipPreviousSection", Bundle.EMPTY))
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
                "skipNextSection"     -> mainHandler.post { skipSection(forward = true) }
                "skipPreviousSection" -> mainHandler.post { skipSection(forward = false) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
