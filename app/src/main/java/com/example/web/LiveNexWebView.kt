package com.example.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import kotlin.math.abs

/**
 * Hardened hardware-accelerated [WebView] with smooth top-of-page pull-to-refresh gesture
 * detection that stays out of the way of normal scrolling, horizontal carousels, pinch-zoom,
 * and fullscreen video playback.
 */
class LiveNexWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    var pullToRefreshEnabled: Boolean = true
    var onPullProgress: ((Float) -> Unit)? = null
    var onPullRefreshTriggered: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val refreshThresholdPx = (88f * resources.displayMetrics.density)
    private var startX = 0f
    private var startY = 0f
    private var canStartPull = false
    private var isPulling = false
    private var currentProgress = 0f

    init {
        configureCinemaSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureCinemaSettings() {
        setBackgroundColor(Color.parseColor("#0A0B0F"))
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Security hardening: disallow local file/content URI access and geolocation
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pullToRefreshEnabled) {
            if (isPulling) {
                resetPull()
            }
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                canStartPull = scrollY == 0
                isPulling = false
                currentProgress = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (isPulling) {
                    resetPull()
                }
                canStartPull = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    if (isPulling) resetPull()
                    canStartPull = false
                    return super.onTouchEvent(event)
                }

                val dx = event.x - startX
                val dy = event.y - startY

                if (!isPulling && canStartPull) {
                    if (scrollY > 0 || dy < -touchSlop) {
                        canStartPull = false
                    } else if (dy > touchSlop && dy > abs(dx) * 1.4f) {
                        isPulling = true
                        val cancelEvent = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                        super.onTouchEvent(cancelEvent)
                        cancelEvent.recycle()
                    }
                }

                if (isPulling) {
                    if (dy <= 0f) {
                        resetPull()
                    } else {
                        // Smooth damped resistance curve up to 1.35x threshold
                        val dampedOffset = dy * 0.48f
                        currentProgress = (dampedOffset / refreshThresholdPx).coerceIn(0f, 1.35f)
                        onPullProgress?.invoke(currentProgress)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isPulling) {
                    val shouldRefresh = currentProgress >= 1.0f
                    resetPull()
                    if (shouldRefresh) {
                        onPullRefreshTriggered?.invoke()
                    }
                    return true
                }
                canStartPull = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isPulling) {
                    resetPull()
                }
                canStartPull = false
            }
        }

        return super.onTouchEvent(event)
    }

    private fun resetPull() {
        isPulling = false
        canStartPull = false
        currentProgress = 0f
        onPullProgress?.invoke(0f)
    }
}
