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

private const val TAG = "PlaybackService"
private const val NOTIFICATION_ID = 123
private const val NOTIFICATION_CHANNEL_ID = "pagevox_playback_channel"
private const val NOTIFICATION_CHANNEL_NAME = "PageVox Playback"

class PlaybackService : MediaSessionService(), TextToSpeech.OnInitListener {

    private var mediaSession: MediaSession? = null
    private lateinit var player: Player
    private lateinit var tts: TextToSpeech

    private var sentences = mutableListOf<String>()
    private var currentSentenceIndex = 0

    // State variables to handle the TTS initialization race condition.
    private var isTtsInitialized = false
    private var pendingPlayback: Runnable? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // TTS initialization is asynchronous. onInit will be called when it's ready.
        tts = TextToSpeech(this, this)
        
        // --- THIS IS THE NEW, CORRECTED LOGIC ---
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

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(CustomMediaSessionCallback())
            .build()
        Log.d(TAG, "MediaSession created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d(TAG, "onGetSession for controller: ${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        if (mediaSession?.player?.playWhenReady == false) {
            stopSelf()
        }
    }

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
    @OptIn(UnstableApi::class)
    private inner class CustomMediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "onConnect from controller: ${controller.packageName}")
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands =
                connectionResult.availableSessionCommands.buildUpon()
                    .remove(Player.COMMAND_SEEK_BACK)
                    .remove(Player.COMMAND_SEEK_FORWARD)
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(SessionCommand("playSentences", Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands)
                .build()
        }

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

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Log.d(TAG, "onPostConnect from controller: ${controller.packageName}")
            if (session.player.playbackState == Player.STATE_IDLE) {
                session.player.prepare()
            }
        }
    }

    // --- Notification related methods ---
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

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PageVox")
            .setContentText("Reading text aloud")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}