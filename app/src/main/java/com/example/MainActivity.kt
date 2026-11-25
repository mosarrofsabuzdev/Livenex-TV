package com.example

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.network.NetworkMonitor
import com.example.ui.LiveNexApp
import com.example.ui.LiveNexViewModel
import com.example.ui.theme.LiveNexTheme
import com.example.web.LiveNexWebView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: LiveNexViewModel by viewModels()
    private lateinit var networkMonitor: NetworkMonitor
    private var activeWebView: LiveNexWebView? = null
    private var pipSourceRect: Rect? = null
    private var isPipPlaying: Boolean = true
    private var isReceiverRegistered: Boolean = false

    private val pipMediaAction: String
        get() = "$packageName.ACTION_PIP_TOGGLE_PLAYBACK"

    private val isPipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != pipMediaAction) return
            activeWebView?.togglePipPlayback { nowPlaying ->
                isPipPlaying = nowPlaying
                val state = viewModel.uiState.value
                updatePipParams(
                    autoEnterEnabled = state.isInitialLoadComplete && state.error == null,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        viewModel.setPipSupported(isPipSupported)

        networkMonitor = NetworkMonitor(applicationContext)
        networkMonitor.start()

        registerPipControlReceiver()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnline.collect { isOnline ->
                    viewModel.onConnectivityChanged(isOnline)
                }
            }
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.isFullscreenVideo, uiState.isInPipMode) {
                applyWindowAndOrientation(
                    isFullscreenVideo = uiState.isFullscreenVideo,
                    isInPipMode = uiState.isInPipMode,
                )
            }

            LaunchedEffect(
                uiState.isInitialLoadComplete,
                uiState.error,
                uiState.isFullscreenVideo,
            ) {
                updatePipParams(
                    autoEnterEnabled = uiState.isInitialLoadComplete && uiState.error == null,
                )
            }

            LiveNexTheme {
                LiveNexApp(
                    viewModel = viewModel,
                    onRegisterWebView = { webView ->
                        activeWebView = webView
                    },
                    onPipSourceRectChanged = { rect ->
                        pipSourceRect = rect
                        val state = viewModel.uiState.value
                        updatePipParams(
                            autoEnterEnabled = state.isInitialLoadComplete && state.error == null,
                        )
                    },
                    onEnterPipMode = { enterLiveNexPipMode() },
                    onExitApp = { finish() },
                )
            }
        }
    }

    private fun registerPipControlReceiver() {
        if (isReceiverRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val filter = IntentFilter(pipMediaAction)
        ContextCompat.registerReceiver(
            this,
            pipControlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnterEnabled: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(buildPlayPauseAction()))

        pipSourceRect?.takeIf { !it.isEmpty }?.let { rect ->
            builder.setSourceRectHint(rect)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setAutoEnterEnabled(autoEnterEnabled)
                .setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPlayPauseAction(): RemoteAction {
        val intent = Intent(pipMediaAction).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            PIP_PLAYBACK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val iconRes = if (isPipPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val title = getString(
            if (isPipPlaying) R.string.pip_action_pause else R.string.pip_action_play,
        )
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent,
        )
    }

    private fun updatePipParams(autoEnterEnabled: Boolean) {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            setPictureInPictureParams(buildPipParams(autoEnterEnabled))
        } catch (_: IllegalStateException) {
            // Ignore if activity is finishing or not yet attached to window.
        }
    }

    private fun enterLiveNexPipMode(): Boolean {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isInPictureInPictureMode) return true
        val state = viewModel.uiState.value
        val allowAutoEnter = state.isInitialLoadComplete && state.error == null
        isPipPlaying = true

        // Focus inline/iframe player cleanly before morphing into PiP when not in HTML5 fullscreen
        if (!state.isFullscreenVideo) {
            activeWebView?.enterPipVideoFocus()
        }

        return try {
            val entered = enterPictureInPictureMode(buildPipParams(autoEnterEnabled = allowAutoEnter))
            if (!entered && !state.isFullscreenVideo) {
                activeWebView?.exitPipVideoFocus()
            }
            entered
        } catch (_: IllegalStateException) {
            if (!state.isFullscreenVideo) {
                activeWebView?.exitPipVideoFocus()
            }
            false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Trigger PiP on Home/Recents across Android 8–16 (essential on MIUI/HyperOS & 3-button nav)
        if (isPipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = viewModel.uiState.value
            if (state.isInitialLoadComplete && state.error == null && !isInPictureInPictureMode) {
                enterLiveNexPipMode()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.onPipModeChanged(isInPictureInPictureMode)

        if (isInPictureInPictureMode) {
            if (!viewModel.uiState.value.isFullscreenVideo) {
                activeWebView?.enterPipVideoFocus()
            }
            // Keep WebView video & timers smoothly running inside the floating PiP window
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
        } else {
            activeWebView?.exitPipVideoFocus()
        }
    }

    private fun applyWindowAndOrientation(
        isFullscreenVideo: Boolean,
        isInPipMode: Boolean,
    ) {
        if (isInPipMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (isFullscreenVideo) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
    }

    override fun onPause() {
        if (isInPictureInPictureMode) {
            // Prevent Chromium WebView from pausing playback when the PiP window loses input focus
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
        }
        super.onPause()
    }

    override fun onStop() {
        // Pause WebView only when fully stopped (not during PiP where the activity remains visible)
        if (!isInPictureInPictureMode) {
            activeWebView?.onPause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(pipControlReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            isReceiverRegistered = false
        }
        networkMonitor.stop()
        activeWebView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
        }
        activeWebView = null
        super.onDestroy()
    }

    companion object {
        private const val PIP_PLAYBACK_REQUEST_CODE = 4001
    }
}
