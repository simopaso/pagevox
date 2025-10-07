package fi.paso.pagevox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.UUID

// --- Constants ---
// Logcat tag for this service.
private const val TAG = "PlaybackService"
// The unique ID for the foreground service notification.
private const val NOTIFICATION_ID = 123
// The unique ID for the notification channel.
private const val NOTIFICATION_CHANNEL_ID = "pagevox_playback_channel"
// The user-visible name of the notification channel.
private const val NOTIFICATION_CHANNEL_NAME = "PageVox Playback"

/**
 * A MediaSessionService that handles text-to-speech (TTS) playback.
 * It integrates with the Android media framework using Media3.
 */
class PlaybackService : MediaSessionService(), TextToSpeech.OnInitListener {

    // The MediaSession instance that allows other components to control playback.
    private var mediaSession: MediaSession? = null
    // The player instance that handles media playback (in this case, a dummy ExoPlayer).
    private lateinit var player: Player
    // The TextToSpeech engine.
    private lateinit var tts: TextToSpeech

    // A list of sentences to be spoken.
    private var sentences = mutableListOf<String>()
    // The index of the sentence currently being spoken.
    private var currentSentenceIndex = 0

    // --- State variables to handle the TTS initialization race condition. ---
    // A flag to indicate whether the TTS engine has been initialized.
    private var isTtsInitialized = false
    // A Runnable to hold a pending playback request until the TTS engine is ready.
    private var pendingPlayback: Runnable? = null

    /**
     * Called when the service is created.
     * It initializes the TTS engine, the player, and the MediaSession.
     */
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // Create a notification channel for the foreground service notification.
        createNotificationChannel()
        // Start the service as a foreground service to prevent it from being killed by the system.
        startForeground(NOTIFICATION_ID, createNotification())

        // TTS initialization is asynchronous. onInit will be called when it's ready.
        tts = TextToSpeech(this, this)

        // Create a dummy ExoPlayer instance to handle media session callbacks.
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) {
                        // This is the correct way to handle a pause event.
                        Log.d(TAG, "Player paused (playWhenReady=false). Stopping TTS.")
                        tts.stop()
                        pendingPlayback = null
                    }
                }
            })
        }
        player.prepare()

        // Create a MediaSession to integrate with the Android media framework.
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(CustomMediaSessionCallback())
            .build()
        Log.d(TAG, "MediaSession created")
    }

    /**
     * Called when a media controller wants to connect to the session.
     *
     * @param controllerInfo Information about the connecting controller.
     * @return The MediaSession instance.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d(TAG, "onGetSession for controller: ${controllerInfo.packageName}")
        return mediaSession
    }

    /**
     * Called when the task that the service is associated with is removed.
     *
     * @param rootIntent The original intent that started the task.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        if (mediaSession?.player?.playWhenReady == false) {
            stopSelf()
        }
    }

    /**
     * Called when the service is being destroyed.
     * It cleans up resources, such as the TTS engine, the player, and the MediaSession.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // --- TextToSpeech.OnInitListener ---
    /**
     * Called when the TTS engine has been initialized.
     *
     * @param status The initialization status.
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS initialization successful")
            isTtsInitialized = true // Mark TTS as ready
            tts.language = Locale.getDefault()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS onStart")
                    player.playWhenReady = true
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS onDone")
                    currentSentenceIndex++
                    if (currentSentenceIndex < sentences.size) {
                        speakNextSentence()
                    } else {
                        Log.d(TAG, "Playback finished")
                        player.playWhenReady = false
                        player.stop()
                        player.prepare()
                    }
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS onError")
                    player.playWhenReady = false
                }
            })

            // If there's a pending playback request, execute it now.
            pendingPlayback?.run()
            pendingPlayback = null

        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Speaks the next sentence in the list.
     */
    private fun speakNextSentence() {
        if (!isTtsInitialized) {
            Log.e(TAG, "TTS not initialized, cannot speak.")
            return
        }
        if (currentSentenceIndex < sentences.size) {
            val sentence = sentences[currentSentenceIndex]
            val utteranceId = UUID.randomUUID().toString()
            Log.d(TAG, "Speaking sentence (index $currentSentenceIndex): $sentence")
            tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    // --- MediaSession.Callback ---
    /**
     * A custom MediaSession.Callback to handle media session events.
     */
    @OptIn(UnstableApi::class)
    private inner class CustomMediaSessionCallback : MediaSession.Callback {
        /**
         * Called when a controller is connecting to the session.
         *
         * @param session The media session.
         * @param controller The connecting controller.
         * @return The result of the connection.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "onConnect from controller: ${controller.packageName}")
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands =
                connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand("playSentences", Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands)
                .build()
        }

        /**
         * Called when a custom command is received from a controller.
         *
         * @param session The media session.
         * @param controller The controller that sent the command.
         * @param customCommand The custom command.
         * @param args The arguments for the command.
         * @return A ListenableFuture containing the result of the command.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "playSentences") {
                val newSentences = args.getStringArrayList("sentences")
                val startIndex = args.getInt("startIndex", 0)

                if (newSentences != null && newSentences.isNotEmpty()) {
                    val playbackAction = Runnable {
                        Log.d(TAG, "Executing playback action (TTS initialized: $isTtsInitialized)")
                        sentences.clear()
                        sentences.addAll(newSentences)
                        currentSentenceIndex = startIndex
                        player.stop() // Reset player state before starting
                        player.prepare()
                        speakNextSentence()
                    }

                    if (isTtsInitialized) {
                        playbackAction.run()
                    } else {
                        Log.w(TAG, "TTS not initialized yet. Queuing playback request.")
                        pendingPlayback = playbackAction
                    }

                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Called after a controller has connected to the session.
         *
         * @param session The media session.
         * @param controller The connected controller.
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Log.d(TAG, "onPostConnect from controller: ${controller.packageName}")
            if (session.player.playbackState == Player.STATE_IDLE) {
                session.player.prepare()
            }
        }
    }

    // --- Notification related methods ---
    /**
     * Creates a notification channel for the foreground service notification.
     */
    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates the notification for the foreground service.
     *
     * @return The notification.
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PageVox")
            .setContentText("Reading text aloud")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}