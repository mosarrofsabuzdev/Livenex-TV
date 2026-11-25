package com.example.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import kotlin.math.abs

/**
 * Hardened hardware-accelerated [WebView] with top-of-page pull-to-refresh gesture detection
 * and a user-gesture-gated Web Picture-in-Picture bridge that isolates ONLY the active video
 * visual (via native HTML5 video fullscreen extraction with full-DOM sibling-hiding fallback)
 * before entering Android Picture-in-Picture mode.
 */
class LiveNexWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    var pullToRefreshEnabled: Boolean = true
    var onPullProgress: ((Float) -> Unit)? = null
    var onPullRefreshTriggered: (() -> Unit)? = null

    /**
     * Called when the user taps the website video player's PiP icon.
     * [awaitFullscreenView] is true when the video requested native HTML5 fullscreen so Android
     * can wait briefly for [WebChromeClient.onShowCustomView] to extract the pure video surface.
     */
    var onWebPlayerPipRequested: ((videoWidth: Int, videoHeight: Int, awaitFullscreenView: Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val refreshThresholdPx = (88f * resources.displayMetrics.density)
    private var startX = 0f
    private var startY = 0f
    private var canStartPull = false
    private var isPulling = false
    private var currentProgress = 0f

    @Volatile
    private var lastUserTapUptimeMs: Long = 0L

    init {
        configureCinemaSettings()
        addJavascriptInterface(PipBridge(), PIP_BRIDGE_NAME)
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
     * Installs an idempotent Web PiP polyfill so HTML5 players display their PiP icon and
     * tapping it isolates ONLY the video before invoking Android PiP.
     */
    fun installWebPipPolyfill() {
        evaluateJavascript(JS_INSTALL_PIP_POLYFILL, null)
    }

    /**
     * Strictly hides all non-video DOM elements (headers, channel grids, overlay controls)
     * and expands the active <video> to 100vw x 100vh with inline !important rules so ONLY
     * the pure video visual is shown inside PiP, then invokes [onReady] after a paint frame.
     */
    fun isolateVideoForPipAndRun(onReady: (videoWidth: Int, videoHeight: Int) -> Unit) {
        evaluateJavascript(JS_ISOLATE_ACTIVE_VIDEO) { raw ->
            val dims = parseDimensions(raw)
            // Wait briefly for Chromium compositor frame commit before capturing into PiP
            postDelayed({ onReady(dims.first, dims.second) }, 48L)
        }
    }

    /**
     * Restores all hidden DOM elements, removes PiP isolation styles, and dispatches
     * `leavepictureinpicture` cleanly when returning from Android PiP.
     */
    fun restorePageAfterPip(exitTemporaryFullscreen: Boolean) {
        val script = if (exitTemporaryFullscreen) {
            JS_RESTORE_AFTER_PIP_AND_EXIT_FS
        } else {
            JS_RESTORE_AFTER_PIP
        }
        evaluateJavascript(script, null)
    }

    private fun parseDimensions(rawResult: String?): Pair<Int, Int> {
        val clean = rawResult?.trim()?.trim('"').orEmpty()
        val parts = clean.split("x")
        if (parts.size == 2) {
            val w = parts[0].toIntOrNull() ?: 0
            val h = parts[1].toIntOrNull() ?: 0
            if (w > 0 && h > 0) return w to h
        }
        return 16 to 9
    }

    private fun wasRecentlyTapped(): Boolean {
        val elapsed = SystemClock.uptimeMillis() - lastUserTapUptimeMs
        return elapsed in 0L..USER_GESTURE_WINDOW_MS
    }

    private inner class PipBridge {
        @JavascriptInterface
        fun requestPip(videoWidth: Int, videoHeight: Int, awaitFullscreen: Boolean) {
            if (!wasRecentlyTapped()) return
            post {
                onWebPlayerPipRequested?.invoke(videoWidth, videoHeight, awaitFullscreen)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            lastUserTapUptimeMs = SystemClock.uptimeMillis()
        }

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
        private const val PIP_BRIDGE_NAME = "LiveNexPipBridge"
        private const val USER_GESTURE_WINDOW_MS = 2500L

        private const val JS_INSTALL_PIP_POLYFILL = """
            (function() {
              try {
                if (window.__livenexPipPolyfillInstalled) return;
                window.__livenexPipPolyfillInstalled = true;
                window.__livenexPipVideo = null;
                window.__livenexSavedStyles = [];
                window.__livenexHiddenSiblings = [];

                function setImp(el, prop, val) {
                  el.style.setProperty(prop, val, 'important');
                }

                function saveStyle(el) {
                  if (!el || el.dataset.__livenexSaved) return;
                  el.dataset.__livenexSaved = '1';
                  window.__livenexSavedStyles.push({
                    el: el,
                    cssText: el.getAttribute('style')
                  });
                }

                function hideNonAncestorSiblings(targetEl) {
                  var cur = targetEl;
                  while (cur && cur.parentElement) {
                    var parent = cur.parentElement;
                    var children = parent.children;
                    for (var i = 0; i < children.length; i++) {
                      var sib = children[i];
                      if (sib !== cur && sib.tagName !== 'SCRIPT' && sib.tagName !== 'STYLE' && sib.tagName !== 'LINK') {
                        saveStyle(sib);
                        setImp(sib, 'display', 'none');
                        setImp(sib, 'visibility', 'hidden');
                        setImp(sib, 'opacity', '0');
                        window.__livenexHiddenSiblings.push(sib);
                      }
                    }
                    if (parent === document.body) break;
                    cur = parent;
                  }
                }

                function isolateElementStrictly(targetEl) {
                  if (!targetEl) return;
                  window.__livenexRestorePipDom && window.__livenexRestorePipDom(false);
                  window.__livenexPipVideo = targetEl;

                  saveStyle(document.documentElement);
                  setImp(document.documentElement, 'overflow', 'hidden');
                  setImp(document.documentElement, 'background', '#000000');
                  setImp(document.documentElement, 'margin', '0');
                  setImp(document.documentElement, 'padding', '0');
                  setImp(document.documentElement, 'width', '100vw');
                  setImp(document.documentElement, 'height', '100vh');

                  if (document.body) {
                    saveStyle(document.body);
                    setImp(document.body, 'overflow', 'hidden');
                    setImp(document.body, 'background', '#000000');
                    setImp(document.body, 'margin', '0');
                    setImp(document.body, 'padding', '0');
                    setImp(document.body, 'width', '100vw');
                    setImp(document.body, 'height', '100vh');
                  }

                  hideNonAncestorSiblings(targetEl);

                  // Strip every CSS containing-block property on ancestors so fixed viewport fill cannot be trapped
                  var anc = targetEl.parentElement;
                  while (anc && anc !== document.documentElement) {
                    saveStyle(anc);
                    setImp(anc, 'position', 'static');
                    setImp(anc, 'transform', 'none');
                    setImp(anc, 'filter', 'none');
                    setImp(anc, 'backdrop-filter', 'none');
                    setImp(anc, '-webkit-backdrop-filter', 'none');
                    setImp(anc, 'perspective', 'none');
                    setImp(anc, 'will-change', 'auto');
                    setImp(anc, 'contain', 'none');
                    setImp(anc, 'isolation', 'auto');
                    setImp(anc, 'clip-path', 'none');
                    setImp(anc, 'overflow', 'visible');
                    setImp(anc, 'opacity', '1');
                    setImp(anc, 'visibility', 'visible');
                    setImp(anc, 'margin', '0');
                    setImp(anc, 'padding', '0');
                    setImp(anc, 'border', '0');
                    setImp(anc, 'width', '100vw');
                    setImp(anc, 'height', '100vh');
                    setImp(anc, 'max-width', 'none');
                    setImp(anc, 'max-height', 'none');
                    setImp(anc, 'background', '#000000');
                    setImp(anc, 'z-index', 'auto');
                    anc = anc.parentElement;
                  }

                  // Force the video element itself to occupy 100% of the PiP viewport cleanly
                  saveStyle(targetEl);
                  setImp(targetEl, 'position', 'fixed');
                  setImp(targetEl, 'top', '0');
                  setImp(targetEl, 'left', '0');
                  setImp(targetEl, 'right', '0');
                  setImp(targetEl, 'bottom', '0');
                  setImp(targetEl, 'width', '100vw');
                  setImp(targetEl, 'height', '100vh');
                  setImp(targetEl, 'min-width', '100vw');
                  setImp(targetEl, 'min-height', '100vh');
                  setImp(targetEl, 'max-width', 'none');
                  setImp(targetEl, 'max-height', 'none');
                  setImp(targetEl, 'margin', '0');
                  setImp(targetEl, 'padding', '0');
                  setImp(targetEl, 'border', '0');
                  setImp(targetEl, 'object-fit', 'contain');
                  setImp(targetEl, 'background', '#000000');
                  setImp(targetEl, 'display', 'block');
                  setImp(targetEl, 'visibility', 'visible');
                  setImp(targetEl, 'opacity', '1');
                  setImp(targetEl, 'z-index', '2147483647');
                  targetEl.dataset.__livenexControlsBeforePip = targetEl.controls ? '1' : '0';
                  targetEl.controls = false;
                }

                window.__livenexIsolateVideo = function(videoEl) {
                  var target = videoEl || window.__livenexPipVideo;
                  if (!target) {
                    var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                    for (var i = 0; i < videos.length; i++) {
                      if (!videos[i].paused && !videos[i].ended && videos[i].readyState > 1) {
                        target = videos[i];
                        break;
                      }
                    }
                    if (!target && videos.length > 0) {
                      target = videos[0];
                    }
                  }
                  if (target) {
                    isolateElementStrictly(target);
                    return (target.videoWidth || 16) + 'x' + (target.videoHeight || 9);
                  }
                  return '16x9';
                };

                window.__livenexRestorePipDom = function(dispatchLeaveEvent) {
                  try {
                    var vid = window.__livenexPipVideo;
                    if (vid && vid.dataset && vid.dataset.__livenexControlsBeforePip !== undefined) {
                      vid.controls = (vid.dataset.__livenexControlsBeforePip === '1');
                      delete vid.dataset.__livenexControlsBeforePip;
                    }
                    var list = window.__livenexSavedStyles || [];
                    for (var i = list.length - 1; i >= 0; i--) {
                      var item = list[i];
                      if (!item || !item.el) continue;
                      if (item.cssText === null || item.cssText === undefined) {
                        item.el.removeAttribute('style');
                      } else {
                        item.el.setAttribute('style', item.cssText);
                      }
                      if (item.el.dataset) {
                        delete item.el.dataset.__livenexSaved;
                      }
                    }
                    window.__livenexSavedStyles = [];
                    window.__livenexHiddenSiblings = [];
                    if (dispatchLeaveEvent && vid) {
                      window.__livenexPipVideo = null;
                      try {
                        vid.dispatchEvent(new Event('leavepictureinpicture'));
                      } catch (e) {}
                    }
                  } catch (e) {}
                };

                try {
                  Object.defineProperty(document, 'pictureInPictureEnabled', {
                    configurable: true,
                    get: function() { return true; }
                  });
                } catch (e) {}

                try {
                  Object.defineProperty(document, 'pictureInPictureElement', {
                    configurable: true,
                    get: function() { return window.__livenexPipVideo || null; }
                  });
                } catch (e) {}

                if (window.HTMLVideoElement && HTMLVideoElement.prototype) {
                  HTMLVideoElement.prototype.requestPictureInPicture = function() {
                    var self = this;
                    var w = self.videoWidth || 16;
                    var h = self.videoHeight || 9;
                    try {
                      window.__livenexPipVideo = self;
                      var alreadyNativeFs = !!(document.fullscreenElement || document.webkitFullscreenElement);

                      // Always strictly isolate ONLY the <video> immediately so no page chrome can appear
                      isolateElementStrictly(self);

                      // Also promote the <video> element itself to native HTML5 fullscreen if not already in fullscreen
                      // so Chromium extracts the pure hardware video SurfaceView directly.
                      var requestedFs = false;
                      if (!alreadyNativeFs) {
                        var reqFs = self.requestFullscreen || self.webkitRequestFullscreen;
                        if (typeof reqFs === 'function') {
                          try {
                            var fsRes = reqFs.call(self);
                            if (fsRes && fsRes.catch) { fsRes.catch(function() {}); }
                            requestedFs = true;
                          } catch (e) {
                            requestedFs = false;
                          }
                        }
                      }

                      if (window.LiveNexPipBridge && window.LiveNexPipBridge.requestPip) {
                        window.LiveNexPipBridge.requestPip(w, h, requestedFs);
                      }
                    } catch (err) {}

                    return Promise.resolve({
                      width: w,
                      height: h,
                      addEventListener: function() {},
                      removeEventListener: function() {}
                    });
                  };
                }

                document.exitPictureInPicture = function() {
                  window.__livenexRestorePipDom && window.__livenexRestorePipDom(true);
                  return Promise.resolve();
                };
              } catch (e) {}
            })();
        """

        private const val JS_ISOLATE_ACTIVE_VIDEO = """
            (function() {
              try {
                if (window.__livenexIsolateVideo) {
                  return window.__livenexIsolateVideo(null);
                }
              } catch (e) {}
              return '16x9';
            })();
        """

        private const val JS_RESTORE_AFTER_PIP = """
            (function() {
              try {
                if (window.__livenexRestorePipDom) {
                  window.__livenexRestorePipDom(true);
                }
              } catch (e) {}
            })();
        """

        private const val JS_RESTORE_AFTER_PIP_AND_EXIT_FS = """
            (function() {
              try {
                if (document.fullscreenElement && document.exitFullscreen) {
                  var p = document.exitFullscreen();
                  if (p && p.catch) { p.catch(function(){}); }
                } else if (document.webkitFullscreenElement && document.webkitExitFullscreen) {
                  document.webkitExitFullscreen();
                }
              } catch (e) {}
              try {
                if (window.__livenexRestorePipDom) {
                  window.__livenexRestorePipDom(true);
                }
              } catch (e) {}
            })();
        """
    }
}
