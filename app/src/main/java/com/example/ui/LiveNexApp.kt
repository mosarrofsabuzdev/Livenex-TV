package com.example.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.BrandedSplashOverlay
import com.example.ui.components.OfflineErrorOverlay
import com.example.ui.components.PullToRefreshIndicatorPill
import com.example.web.LiveNexWebChromeClient
import com.example.web.LiveNexWebView
import com.example.web.LiveNexWebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiveNexApp(
    viewModel: LiveNexViewModel,
    onRegisterWebView: (LiveNexWebView?) -> Unit,
    onEnterPipMode: () -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var webViewRef by remember { mutableStateOf<LiveNexWebView?>(null) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    // Fullscreen HTML5 custom video view & callback
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val animatedProgress by animateFloatAsState(
        targetValue = (uiState.loadProgress.coerceIn(0, 100) / 100f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "tv_load_progress",
    )

    // Failsafe so launch overlay never lingers if a third-party script stalls
    LaunchedEffect(Unit) {
        delay(3500)
        if (!viewModel.uiState.value.isInitialLoadComplete) {
            viewModel.onProgressChanged(50)
        }
    }

    fun showToastMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun exitFullscreenVideo() {
        val currentView = fullscreenView
        val callback = fullscreenCallback
        fullscreenView = null
        fullscreenCallback = null
        (currentView?.parent as? ViewGroup)?.removeView(currentView)
        callback?.onCustomViewHidden()
        viewModel.onFullscreenChanged(false)
    }

    fun reloadCurrentOrHome() {
        val wv = webViewRef ?: return
        viewModel.onStartRefresh()
        val failingUrl = uiState.error?.failingUrl
        when {
            !failingUrl.isNullOrBlank() -> wv.loadUrl(failingUrl)
            wv.url.isNullOrBlank() || wv.url == "about:blank" -> wv.loadUrl(LiveNexConstants.HOME_URL)
            else -> wv.reload()
        }
    }

    fun loadHomePage() {
        val wv = webViewRef ?: return
        viewModel.onClearError()
        wv.loadUrl(LiveNexConstants.HOME_URL)
    }

    // Priority Back Handler: Fullscreen video -> WebView history -> Double-back exit safeguard
    BackHandler(enabled = !uiState.isInPipMode) {
        when {
            fullscreenView != null -> {
                exitFullscreenVideo()
            }
            webViewRef?.canGoBack() == true -> {
                webViewRef?.goBack()
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime <= 2000L) {
                    onExitApp()
                } else {
                    lastBackPressTime = now
                    showToastMessage(context.getString(R.string.press_back_again_to_exit))
                }
            }
        }
    }

    // Clean up custom fullscreen view if composition leaves
    DisposableEffect(Unit) {
        onDispose {
            onRegisterWebView(null)
            fullscreenCallback?.onCustomViewHidden()
            fullscreenView = null
            fullscreenCallback = null
        }
    }

    val isImmersiveOrPip = uiState.isFullscreenVideo || uiState.isInPipMode

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Pure TV Viewport — no browser top bar; respects safe system insets when browsing,
        // and expands 100% edge-to-edge in Fullscreen Video & Picture-in-Picture modes.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isImmersiveOrPip) {
                        Modifier
                            .statusBarsPadding()
                            .displayCutoutPadding()
                            .navigationBarsPadding()
                    } else {
                        Modifier
                    },
                ),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("livenex_webview"),
                factory = { ctx ->
                    LiveNexWebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        webViewClient = LiveNexWebViewClient(
                            context = ctx,
                            onPageStartedCallback = { url, _ ->
                                viewModel.onPageStarted(url)
                            },
                            onPageFinishedCallback = { url, _, _ ->
                                viewModel.onPageFinished(url)
                            },
                            onMainFrameErrorCallback = { failingUrl, errorCode, description ->
                                viewModel.onMainFrameError(failingUrl, errorCode, description)
                            },
                            onBlockedLinkCallback = {
                                showToastMessage(ctx.getString(R.string.external_link_blocked))
                            },
                            onExternalAppMissingCallback = {
                                showToastMessage(ctx.getString(R.string.external_app_unavailable))
                            },
                        )
                        webChromeClient = LiveNexWebChromeClient(
                            onProgressChangedCallback = { progress ->
                                viewModel.onProgressChanged(progress)
                            },
                            onTitleReceivedCallback = { /* No browser title bar in TV mode */ },
                            onEnterFullscreen = { view, callback ->
                                fullscreenCallback?.onCustomViewHidden()
                                (fullscreenView?.parent as? ViewGroup)?.removeView(fullscreenView)
                                fullscreenView = view
                                fullscreenCallback = callback
                                viewModel.onFullscreenChanged(true)
                            },
                            onExitFullscreen = {
                                exitFullscreenVideo()
                            },
                        )
                        onPullProgress = { progress ->
                            pullProgress = progress
                        }
                        onPullRefreshTriggered = {
                            reloadCurrentOrHome()
                        }
                        webViewRef = this
                        onRegisterWebView(this)
                        loadUrl(LiveNexConstants.HOME_URL)
                    }
                },
                update = { wv ->
                    wv.pullToRefreshEnabled = !isImmersiveOrPip &&
                        !uiState.isRefreshing &&
                        uiState.error == null
                },
            )

            // Ultra-thin crimson loading line at top edge (hidden when loaded or in PiP/fullscreen)
            AnimatedVisibility(
                visible = !isImmersiveOrPip &&
                    uiState.isInitialLoadComplete &&
                    uiState.isLoading &&
                    uiState.loadProgress in 1..99,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(260)),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .testTag("page_load_progress_bar"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }

            // Pull-to-refresh crimson indicator pill
            if (!isImmersiveOrPip && uiState.error == null) {
                PullToRefreshIndicatorPill(
                    pullProgress = pullProgress,
                    isRefreshing = uiState.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // Offline / Load Error Recovery Screen (hidden in PiP & fullscreen)
            AnimatedVisibility(
                visible = uiState.error != null && !isImmersiveOrPip,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val activeError = uiState.error
                if (activeError != null) {
                    OfflineErrorOverlay(
                        error = activeError,
                        isOnline = uiState.isOnline,
                        onRetryClick = { reloadCurrentOrHome() },
                        onGoHomeClick = { loadHomePage() },
                    )
                }
            }
        }

        // Initial Branded Cinema Launch Overlay (hidden immediately if in PiP)
        AnimatedVisibility(
            visible = !uiState.isInitialLoadComplete && !uiState.isInPipMode,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(320)),
            modifier = Modifier.fillMaxSize(),
        ) {
            BrandedSplashOverlay()
        }

        // Dedicated Edge-to-Edge Fullscreen HTML5 Video Container
        val activeFullscreenView = fullscreenView
        if (activeFullscreenView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("fullscreen_video_container"),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { container ->
                        if (activeFullscreenView.parent !== container) {
                            (activeFullscreenView.parent as? ViewGroup)?.removeView(activeFullscreenView)
                            container.removeAllViews()
                            container.addView(
                                activeFullscreenView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                )

                // Unobtrusive one-tap PiP button in fullscreen video (hidden automatically inside PiP window)
                if (uiState.isPipSupported && !uiState.isInPipMode) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .displayCutoutPadding()
                            .padding(14.dp),
                    ) {
                        IconButton(
                            onClick = onEnterPipMode,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("fullscreen_pip_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PictureInPictureAlt,
                                contentDescription = stringResource(R.string.cd_enter_pip),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }

        // Cinema-styled Snackbar feedback (hidden while in PiP window)
        if (!uiState.isInPipMode) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
    }
}
