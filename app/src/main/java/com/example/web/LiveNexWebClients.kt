package com.example.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Handles navigation safely inside [LiveNexWebView], blocking unsupported/unsafe URI schemes,
 * installing the player PiP polyfill on web pages, and surfacing main-frame connection errors.
 */
class LiveNexWebViewClient(
    private val context: Context,
    private val onPageStartedCallback: (url: String, canGoBack: Boolean) -> Unit,
    private val onPageFinishedCallback: (url: String, title: String?, canGoBack: Boolean) -> Unit,
    private val onMainFrameErrorCallback: (failingUrl: String, errorCode: Int, description: String) -> Unit,
    private val onBlockedLinkCallback: () -> Unit,
    private val onExternalAppMissingCallback: () -> Unit,
) : WebViewClient() {

    private var mainFrameErrorOccurred = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        return handleUriNavigation(uri)
    }

    private fun handleUriNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when (scheme) {
            "https", "http" -> {
                false
            }
            "mailto", "tel" -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    onExternalAppMissingCallback()
                }
                true
            }
            else -> {
                onBlockedLinkCallback()
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        mainFrameErrorOccurred = false
        onPageStartedCallback(url.orEmpty(), view?.canGoBack() == true)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            (view as? LiveNexWebView)?.installWebPipPolyfill()
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!mainFrameErrorOccurred) {
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                (view as? LiveNexWebView)?.installWebPipPolyfill()
            }
            onPageFinishedCallback(
                url.orEmpty(),
                view?.title,
                view?.canGoBack() == true,
            )
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val code = error?.errorCode ?: ERROR_UNKNOWN
            if (code == ERROR_CONNECT || code == ERROR_HOST_LOOKUP || code == ERROR_TIMEOUT ||
                code == ERROR_IO || code == ERROR_FAILED_SSL_HANDSHAKE || code == ERROR_UNKNOWN
            ) {
                mainFrameErrorOccurred = true
                onMainFrameErrorCallback(
                    request.url?.toString().orEmpty(),
                    code,
                    error?.description?.toString().orEmpty(),
                )
            }
        }
    }
}

/**
 * Supports HTML5 fullscreen video playback, page loading progress, dynamic page titles,
 * and a dark cinema default video poster.
 */
class LiveNexWebChromeClient(
    private val onProgressChangedCallback: (Int) -> Unit,
    private val onTitleReceivedCallback: (String?) -> Unit,
    private val onEnterFullscreen: (View, CustomViewCallback?) -> Unit,
    private val onExitFullscreen: () -> Unit,
) : WebChromeClient() {

    private val darkPosterBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.parseColor("#0A0B0F"))
        }
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedCallback(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        onTitleReceivedCallback(title)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        onEnterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        onExitFullscreen()
    }

    override fun getDefaultVideoPoster(): Bitmap = darkPosterBitmap
}
