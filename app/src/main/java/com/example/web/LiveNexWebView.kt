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
 * detection and PiP video-focus helpers so inline, iframe, and fullscreen streams fill the
 * Picture-in-Picture window cleanly.
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

    /**
     * Expands the currently playing inline <video> or player <iframe> to fill 100% of the
     * Picture-in-Picture window, bypassing ancestor CSS stacking contexts without reloading.
     */
    fun enterPipVideoFocus() {
        evaluateJavascript(JS_ENTER_PIP_FOCUS, null)
    }

    /**
     * Restores the original page layout cleanly when leaving Picture-in-Picture mode.
     */
    fun exitPipVideoFocus() {
        evaluateJavascript(JS_EXIT_PIP_FOCUS, null)
    }

    /**
     * Toggles play/pause on the active video element when the user taps the PiP control button.
     */
    fun togglePipPlayback(onIsPlayingResult: (Boolean) -> Unit) {
        evaluateJavascript(JS_TOGGLE_PIP_PLAYBACK) { result ->
            val playing = result?.contains("playing") == true
            onIsPlayingResult(playing)
        }
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

    companion object {
        private const val JS_ENTER_PIP_FOCUS = """
            (function() {
              try {
                var existing = document.getElementById('__livenex_pip_style');
                if (!existing) {
                  var style = document.createElement('style');
                  style.id = '__livenex_pip_style';
                  style.textContent =
                    'html.__livenex_pip_active, body.__livenex_pip_active { overflow: hidden !important; background: #000 !important; }' +
                    '.__livenex_pip_ancestor { position: static !important; transform: none !important; filter: none !important; contain: none !important; overflow: visible !important; z-index: auto !important; max-width: none !important; max-height: none !important; }' +
                    '.__livenex_pip_target { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; max-width: none !important; max-height: none !important; min-width: 100vw !important; min-height: 100vh !important; z-index: 2147483647 !important; background: #000 !important; object-fit: contain !important; border: 0 !important; margin: 0 !important; padding: 0 !important; }';
                  (document.head || document.documentElement).appendChild(style);
                }

                var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                var target = null;
                var bestArea = 0;
                for (var i = 0; i < videos.length; i++) {
                  var v = videos[i];
                  var rect = v.getBoundingClientRect();
                  var area = rect.width * rect.height;
                  var isPlaying = !v.paused && !v.ended && v.readyState > 1;
                  if (isPlaying && area > 0) {
                    target = v;
                    break;
                  }
                  if (area > bestArea) {
                    bestArea = area;
                    target = v;
                  }
                }

                if (!target) {
                  var iframes = Array.prototype.slice.call(document.querySelectorAll('iframe'));
                  for (var j = 0; j < iframes.length; j++) {
                    var f = iframes[j];
                    var fRect = f.getBoundingClientRect();
                    var fArea = fRect.width * fRect.height;
                    if (fRect.width >= 180 && fRect.height >= 100 && fArea > bestArea) {
                      bestArea = fArea;
                      target = f;
                    }
                  }
                }

                if (target) {
                  document.documentElement.classList.add('__livenex_pip_active');
                  if (document.body) document.body.classList.add('__livenex_pip_active');
                  target.classList.add('__livenex_pip_target');
                  var parent = target.parentElement;
                  while (parent && parent !== document.body && parent !== document.documentElement) {
                    parent.classList.add('__livenex_pip_ancestor');
                    parent = parent.parentElement;
                  }
                  if (target.tagName === 'VIDEO' && target.paused && target.readyState >= 2) {
                    var playPromise = target.play();
                    if (playPromise && playPromise.catch) { playPromise.catch(function(){}); }
                  }
                }
              } catch (e) {}
            })();
        """

        private const val JS_EXIT_PIP_FOCUS = """
            (function() {
              try {
                var style = document.getElementById('__livenex_pip_style');
                if (style && style.parentNode) style.parentNode.removeChild(style);
                document.documentElement.classList.remove('__livenex_pip_active');
                if (document.body) document.body.classList.remove('__livenex_pip_active');
                var targets = document.querySelectorAll('.__livenex_pip_target');
                for (var i = 0; i < targets.length; i++) {
                  targets[i].classList.remove('__livenex_pip_target');
                }
                var ancestors = document.querySelectorAll('.__livenex_pip_ancestor');
                for (var j = 0; j < ancestors.length; j++) {
                  ancestors[j].classList.remove('__livenex_pip_ancestor');
                }
              } catch (e) {}
            })();
        """

        private const val JS_TOGGLE_PIP_PLAYBACK = """
            (function() {
              try {
                var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                var v = document.querySelector('.__livenex_pip_target');
                if (!v || v.tagName !== 'VIDEO') {
                  v = videos.find(function(item) { return !item.paused; }) || videos[0];
                }
                if (!v) return 'playing';
                if (v.paused) {
                  var p = v.play();
                  if (p && p.catch) { p.catch(function(){}); }
                  return 'playing';
                } else {
                  v.pause();
                  return 'paused';
                }
              } catch (e) {
                return 'playing';
              }
            })();
        """
    }
}
