package com.example

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
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

    private val isPipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        viewModel.setPipSupported(isPipSupported)

        networkMonitor = NetworkMonitor(applicationContext)
        networkMonitor.start()

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
                    onEnterPipMode = { enterLiveNexPipMode() },
                    onExitApp = { finish() },
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnterEnabled: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setAutoEnterEnabled(autoEnterEnabled)
                .setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams(autoEnterEnabled: Boolean) {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            setPictureInPictureParams(buildPipParams(autoEnterEnabled))
        } catch (_: IllegalStateException) {
            // Ignore if activity is finishing or not yet attached to window.
        }
    }

    private fun enterLiveNexPipMode() {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return
        val state = viewModel.uiState.value
        val allowAutoEnter = state.isInitialLoadComplete && state.error == null
        try {
            enterPictureInPictureMode(buildPipParams(autoEnterEnabled = allowAutoEnter))
        } catch (_: IllegalStateException) {
            // Ignore if PiP cannot be entered in the current lifecycle state.
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ uses setAutoEnterEnabled(true); Android 8–11 enters PiP via onUserLeaveHint
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
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
            // Keep video smoothly running while floating in PiP
            activeWebView?.onResume()
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
    }

    override fun onResume() {
        super.onResume()
        activeWebView?.onResume()
    }

    override fun onStop() {
        // Pause WebView only when fully stopped (not during PiP where the activity stays visible)
        if (!isInPictureInPictureMode) {
            activeWebView?.onPause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        networkMonitor.stop()
        activeWebView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
        }
        activeWebView = null
        super.onDestroy()
    }
}
