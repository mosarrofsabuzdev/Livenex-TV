package com.example.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LiveNexViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LiveNexUiState())
    val uiState: StateFlow<LiveNexUiState> = _uiState.asStateFlow()

    fun setPipSupported(supported: Boolean) {
        _uiState.update { current ->
            current.copy(isPipSupported = supported)
        }
    }

    fun setPreparingPip(preparing: Boolean) {
        _uiState.update { current ->
            current.copy(isPreparingPip = preparing)
        }
    }

    fun onPipModeChanged(isInPipMode: Boolean) {
        _uiState.update { current ->
            current.copy(
                isInPipMode = isInPipMode,
                isPreparingPip = false,
            )
        }
    }

    fun onConnectivityChanged(isOnline: Boolean) {
        _uiState.update { current ->
            val updatedError = when {
                !isOnline && !current.isInitialLoadComplete && current.error == null -> {
                    WebErrorInfo(
                        failingUrl = current.currentUrl,
                        errorCode = -2,
                        description = "No internet connection",
                        isOfflineError = true,
                    )
                }
                else -> current.error
            }
            current.copy(
                isOnline = isOnline,
                error = updatedError,
                isInitialLoadComplete = if (!isOnline) true else current.isInitialLoadComplete,
            )
        }
    }

    fun onPageStarted(url: String) {
        _uiState.update { current ->
            current.copy(
                currentUrl = url.ifBlank { current.currentUrl },
                isLoading = true,
                loadProgress = if (current.loadProgress < 10) 10 else current.loadProgress,
                error = null,
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update { current ->
            val clamped = progress.coerceIn(0, 100)
            current.copy(
                loadProgress = clamped,
                isLoading = clamped < 100,
                isInitialLoadComplete = current.isInitialLoadComplete || clamped >= 35,
                isRefreshing = if (clamped >= 100) false else current.isRefreshing,
            )
        }
    }

    fun onPageFinished(url: String) {
        _uiState.update { current ->
            current.copy(
                currentUrl = url.ifBlank { current.currentUrl },
                loadProgress = 100,
                isLoading = false,
                isInitialLoadComplete = true,
                isRefreshing = false,
            )
        }
    }

    fun onMainFrameError(failingUrl: String, errorCode: Int, description: String) {
        _uiState.update { current ->
            current.copy(
                isLoading = false,
                isInitialLoadComplete = true,
                isRefreshing = false,
                loadProgress = 100,
                error = WebErrorInfo(
                    failingUrl = failingUrl.ifBlank { current.currentUrl },
                    errorCode = errorCode,
                    description = description,
                    isOfflineError = !current.isOnline || isLikelyOfflineCode(errorCode),
                ),
            )
        }
    }

    fun onStartRefresh() {
        _uiState.update { current ->
            current.copy(
                isRefreshing = true,
                isLoading = true,
                error = null,
            )
        }
    }

    fun onClearError() {
        _uiState.update { current ->
            current.copy(
                error = null,
                isLoading = true,
            )
        }
    }

    fun onFullscreenChanged(isFullscreen: Boolean) {
        _uiState.update { current ->
            current.copy(isFullscreenVideo = isFullscreen)
        }
    }

    private fun isLikelyOfflineCode(errorCode: Int): Boolean {
        return errorCode == -2 || errorCode == -6 || errorCode == -8
    }
}
