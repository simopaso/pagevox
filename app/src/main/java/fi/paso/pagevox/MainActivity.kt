package fi.paso.pagevox

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import fi.paso.pagevox.ui.theme.PageVoxTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private var mediaController: MediaController? by mutableStateOf(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // ViewModel at Activity level so onStart() can reference it inside the
    // MediaController.Listener — setListener() must be called at build time
    // and addListener() does NOT dispatch MediaController.Listener callbacks
    // such as onCustomCommand.
    private val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelFactory(SettingsRepository(applicationContext))
        )[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Open a URL/text shared into the app on cold start. Runs synchronously
        // before the ViewModel finishes loading prefs, so its init won't clobber
        // the shared URL (see MainViewModel.init's url.isEmpty() guard).
        handleShareIntent(intent)

        setContent {
            PageVoxTheme {
                MainScreen(mainViewModel, mediaController)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** Route ACTION_VIEW (a tapped/opened link) or ACTION_SEND (Share → PageVox)
     *  into the browser. */
    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { mainViewModel.loadUrl(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { mainViewModel.loadSharedText(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        // Don't startForegroundService() here: PlaybackService is a Media3
        // MediaSessionService that only promotes itself to the foreground once
        // playback begins. Starting it as a foreground service while idle makes a
        // startForeground() promise that's never kept, which crashes the app when
        // the service is stopped (e.g. backing out of a clean start). Connecting
        // a MediaController below binds the service; Media3 handles foregrounding
        // when the user actually presses play.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(
                    controller: MediaController,
                    command: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (command.customAction) {
                        "updateIndex"   -> mainViewModel.updateHighlight(args.getInt("index"))
                        "playbackEnded" -> mainViewModel.onPlaybackEnded()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                mediaController = future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Controller connection failed", e)
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        // releaseFuture (rather than mediaController?.release()) also covers the
        // window where onStop arrives before the async build completes — the
        // listener above would otherwise assign a live, bound controller that
        // nothing ever releases, keeping the service bound forever.
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }
}
