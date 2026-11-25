package com.example

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var exitFullscreenAction: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPipFallbackRunnable: Runnable? = null

    // Tracks whether we promoted an inline <video> into temporary native fullscreen solely for PiP
    private var pendingPipOnFullscreen: Boolean = false
    private var enteredFullscreenForPip: Boolean = false
    private var pendingPipAspectRatio: Rational = Rational(16, 9)

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
        disableAutoEnterPip()

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

            LaunchedEffect(uiState.isFullscreenVideo, uiState.isInPipMode, uiState.isPreparingPip) {
                applyWindowAndOrientation(
                    isFullscreenVideo = uiState.isFullscreenVideo,
                    isInPipMode = uiState.isInPipMode,
                    isPreparingPip = uiState.isPreparingPip,
                )
            }

            LiveNexTheme {
                LiveNexApp(
                    viewModel = viewModel,
                    onRegisterWebView = { webView ->
                        activeWebView = webView
                    },
                    onRegisterExitFullscreen = { action ->
                        exitFullscreenAction = action
                    },
                    onFullscreenVideoMounted = {
                        handleFullscreenVideoMounted()
                    },
                    onWebPlayerPipRequested = { width, height, awaitFullscreen ->
                        handleWebPlayerPipRequest(width, height, awaitFullscreen)
                    },
                    onFullscreenPipButtonClick = {
                        handleFullscreenButtonPipRequest()
                    },
                    onExitApp = { finish() },
                )
            }
        }
    }

    /**
     * Triggered when the user taps the website video player's own PiP icon.
     * Waits briefly for native HTML5 video fullscreen (`onShowCustomView`) to mount the pure
     * video surface; if not mounted in time, enters PiP directly with the strictly isolated <video>.
     */
    private fun handleWebPlayerPipRequest(
        videoWidth: Int,
        videoHeight: Int,
        awaitFullscreenView: Boolean,
    ) {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return

        cancelPendingPipFallback()
        pendingPipAspectRatio = safePipRational(videoWidth, videoHeight)
        viewModel.setPreparingPip(true)

        if (awaitFullscreenView && !viewModel.uiState.value.isFullscreenVideo) {
            pendingPipOnFullscreen = true
            val fallback = Runnable {
                if (pendingPipOnFullscreen) {
                    pendingPipOnFullscreen = false
                    enteredFullscreenForPip = false
                    enterLiveNexPipNow(pendingPipAspectRatio)
                }
            }
            pendingPipFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, 240L)
        } else {
            pendingPipOnFullscreen = false
            enteredFullscreenForPip = false
            // Allow one compositor frame so isolated <video> is painted before entering PiP
            mainHandler.postDelayed({
                enterLiveNexPipNow(pendingPipAspectRatio)
            }, 64L)
        }
    }

    /**
     * Called immediately when `WebChromeClient.onShowCustomView` mounts the pure video surface.
     */
    private fun handleFullscreenVideoMounted() {
        if (!pendingPipOnFullscreen) return
        cancelPendingPipFallback()
        pendingPipOnFullscreen = false
        enteredFullscreenForPip = true
        // Wait one frame for the fullscreen video SurfaceView to attach before shrinking into PiP
        mainHandler.postDelayed({
            enterLiveNexPipNow(pendingPipAspectRatio)
        }, 48L)
    }

    /**
     * Triggered when the user taps the PiP icon while already in fullscreen video mode.
     * Isolates only the <video> inside any fullscreen wrapper div before entering PiP.
     */
    private fun handleFullscreenButtonPipRequest() {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return

        cancelPendingPipFallback()
        pendingPipOnFullscreen = false
        enteredFullscreenForPip = false
        viewModel.setPreparingPip(true)

        val wv = activeWebView
        if (wv != null) {
            wv.isolateVideoForPipAndRun { width, height ->
                enterLiveNexPipNow(safePipRational(width, height))
            }
        } else {
            enterLiveNexPipNow(Rational(16, 9))
        }
    }

    private fun cancelPendingPipFallback() {
        pendingPipFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPipFallbackRunnable = null
    }

    private fun safePipRational(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        // Android requires PiP aspect ratio within [0.418410, 2.390000]
        return when {
            ratio < 0.45f -> Rational(9, 20)
            ratio > 2.35f -> Rational(235, 100)
            else -> Rational(width, height)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(aspectRatio: Rational = Rational(16, 9)): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Never auto-enter PiP on minimize/Home — PiP activates ONLY via explicit PiP icon tap
            builder
                .setAutoEnterEnabled(false)
                .setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun disableAutoEnterPip() {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            setPictureInPictureParams(buildPipParams())
        } catch (_: IllegalStateException) {
            // Ignore if window is not yet attached.
        }
    }

    private fun enterLiveNexPipNow(aspectRatio: Rational) {
        if (!isPipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            viewModel.setPreparingPip(false)
            return
        }
        if (isInPictureInPictureMode) {
            viewModel.setPreparingPip(false)
            return
        }
        try {
            val entered = enterPictureInPictureMode(buildPipParams(aspectRatio))
            if (!entered) {
                recoverFailedPipEntry()
            }
        } catch (_: IllegalStateException) {
            recoverFailedPipEntry()
        }
    }

    private fun recoverFailedPipEntry() {
        viewModel.setPreparingPip(false)
        val shouldExitTempFs = enteredFullscreenForPip
        enteredFullscreenForPip = false
        if (shouldExitTempFs) {
            exitFullscreenAction?.invoke()
        }
        activeWebView?.restorePageAfterPip(exitTemporaryFullscreen = shouldExitTempFs)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        cancelPendingPipFallback()
        pendingPipOnFullscreen = false
        viewModel.onPipModeChanged(isInPictureInPictureMode)

        if (isInPictureInPictureMode) {
            // Keep video smoothly running inside the floating PiP window
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
        } else {
            val shouldExitTempFs = enteredFullscreenForPip
            enteredFullscreenForPip = false
            if (shouldExitTempFs) {
                exitFullscreenAction?.invoke()
            }
            activeWebView?.restorePageAfterPip(exitTemporaryFullscreen = shouldExitTempFs)
        }
    }

    private fun applyWindowAndOrientation(
        isFullscreenVideo: Boolean,
        isInPipMode: Boolean,
        isPreparingPip: Boolean,
    ) {
        if (isInPipMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (isPreparingPip) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Do not force orientation rotation while transitioning directly into PiP
            return
        }

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
            // Prevent Chromium WebView from pausing playback when the PiP window loses focus
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
        }
        super.onPause()
    }

    override fun onStop() {
        // Pause WebView cleanly when app is minimized (not in PiP) or when PiP window is closed
        if (!isInPictureInPictureMode) {
            activeWebView?.onPause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelPendingPipFallback()
        networkMonitor.stop()
        activeWebView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
        }
        activeWebView = null
        exitFullscreenAction = null
        super.onDestroy()
    }
}
