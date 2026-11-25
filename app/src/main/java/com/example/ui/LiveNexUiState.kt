package com.example.ui

object LiveNexConstants {
    const val HOME_URL = "https://www.livenextv.xyz"
    const val BRAND_NAME = "LiveNex TV"
}

data class WebErrorInfo(
    val failingUrl: String,
    val errorCode: Int,
    val description: String,
    val isOfflineError: Boolean,
)

data class LiveNexUiState(
    val currentUrl: String = LiveNexConstants.HOME_URL,
    val loadProgress: Int = 0,
    val isLoading: Boolean = true,
    val isInitialLoadComplete: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val isFullscreenVideo: Boolean = false,
    val isInPipMode: Boolean = false,
    val isPipSupported: Boolean = false,
    val error: WebErrorInfo? = null,
)
