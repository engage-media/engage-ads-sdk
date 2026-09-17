package com.engage.ads.render.mraid

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.engage.ads.*
import com.engage.ads.render.CreativeRenderer
import java.util.concurrent.atomic.AtomicBoolean
import android.annotation.SuppressLint

internal class HtmlRenderer(
    private val context: Context,
    private val timeoutMillis: Long,
    private val diagnostics: DiagnosticListener,
    private val measurement: OpenMeasurementCoordinator = OpenMeasurementCoordinator.disabled(diagnostics),
    private val friendlyObstructions: List<FriendlyObstruction> = emptyList(),
) : CreativeRenderer {
    private var webView: WebView? = null
    private var rootView: View? = null
    private var bridge: MraidBridge? = null
    private val displayed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var visibilityListener: ViewTreeObserver.OnPreDrawListener? = null
    private var destroyed = false
    private var measurementSession: SafeOpenMeasurementSession? = null
    private val measurementSessionAttempted = AtomicBoolean(false)
    private var measurementHtmlPrepared = false
    private var sessionObstructions: List<FriendlyObstruction> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun display(creative: LoadedCreative, request: AdRequest, container: ViewGroup, callback: CreativeRenderer.Callback) {
        val view = WebView(container.context).also { webView = it }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        val mraid = MraidBridge(
            container.context,
            view,
            request.format != AdFormat.BANNER,
            diagnostics,
            onClose = { callback.dismissed() },
            onClick = callback::clicked,
            onFailure = callback::failed,
            allowTwoPartExpand = { measurementSession == null },
            onFriendlyObstructionAdded = { view ->
                measurementSession?.registerFriendlyObstruction(
                    FriendlyObstruction(view, FriendlyObstructionPurpose.CLOSE_AD, "Close ad"),
                )
            },
            onFriendlyObstructionRemoved = { view -> measurementSession?.unregisterFriendlyObstruction(view) },
        ).also { bridge = it }
        view.addJavascriptInterface(mraid.javascriptApi, "EngageMraidNative")
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit
            override fun onPageFinished(web: WebView, url: String?) {
                if (!destroyed) {
                    if (measurementHtmlPrepared && measurementSessionAttempted.compareAndSet(false, true)) {
                        val candidateSession = measurement.createSession(
                            OpenMeasurementSessionContext(
                                creativeType = OpenMeasurementCreativeType.HTML,
                                format = request.format,
                                adView = web,
                                webView = web,
                            ),
                            sessionObstructions,
                        )
                        if (destroyed) candidateSession?.finish() else measurementSession = candidateSession
                    }
                    measurementSession?.loaded()
                    waitUntilVisible(web, mraid, callback)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (!destroyed && request.isForMainFrame && request.hasGesture() && (scheme == "http" || scheme == "https")) {
                    try {
                        container.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {}
                    callback.clicked(request.url.toString())
                }
                return true
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!destroyed) callback.failed(EngageError(EngageError.Code.RENDER, "HTML renderer process exited"))
                destroy(renderProcessGone = true)
                return true
            }
        }
        container.removeAllViews()
        var nativeClose: View? = null
        val content = if (request.format == AdFormat.INTERSTITIAL) {
            FrameLayout(container.context).also { frame ->
                frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                frame.addView(TextView(frame.context).apply {
                    text = "×"
                    textSize = 32f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(0x99000000.toInt())
                    contentDescription = "Close ad"
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { if (!destroyed) callback.dismissed() }
                    nativeClose = this
                }, FrameLayout.LayoutParams(
                    (48 * frame.resources.displayMetrics.density).toInt(),
                    (48 * frame.resources.displayMetrics.density).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.END,
                ))
            }
        } else {
            view
        }
        rootView = content
        container.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sessionObstructions = buildList {
            addAll(friendlyObstructions)
            nativeClose?.let { add(FriendlyObstruction(it, FriendlyObstructionPurpose.CLOSE_AD, "Close ad")) }
        }
        val script = javaClass.classLoader?.getResourceAsStream("mraid.js")?.bufferedReader()?.use { it.readText() }
        if (script == null) {
            callback.failed(EngageError(EngageError.Code.RENDER, "canonical mraid.js resource is missing"))
            destroy()
            return
        }
        val rawDocument = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><script>$script</script></head><body style=\"margin:0\">${creative.markup}</body></html>"
        val preparedDocument = measurement.prepareHtml(rawDocument)
        if (destroyed) return
        measurementHtmlPrepared = preparedDocument != null
        val document = preparedDocument ?: rawDocument
        view.loadDataWithBaseURL("https://engage.invalid/", document, "text/html", "UTF-8", null)
        timeout = Runnable {
            if (!destroyed && displayed.compareAndSet(false, true)) {
                callback.failed(EngageError(EngageError.Code.TIMEOUT, "HTML creative did not become visible"))
                destroy()
            }
        }.also { handler.postDelayed(it, timeoutMillis) }
    }

    private fun waitUntilVisible(view: WebView, mraid: MraidBridge, callback: CreativeRenderer.Callback) {
        if (destroyed) return
        fun ready(): Boolean {
            val rect = android.graphics.Rect()
            return view.width > 0 && view.height > 0 && view.isActuallyVisible() && view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0
        }
        if (ready()) {
            removeVisibilityListener()
            if (displayed.compareAndSet(false, true)) {
                timeout?.let(handler::removeCallbacks)
                mraid.sendReady()
                measurementSession?.impression()
                if (destroyed) return
                callback.displayed()
            }
            return
        }
        if (visibilityListener != null) return
        visibilityListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (destroyed) { removeVisibilityListener(); return true }
                if (!view.viewTreeObserver.isAlive || !ready()) return true
                removeVisibilityListener()
                waitUntilVisible(view, mraid, callback)
                return true
            }
        }.also { view.viewTreeObserver.addOnPreDrawListener(it) }
    }

    private fun removeVisibilityListener() {
        val listener = visibilityListener ?: return
        webView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(listener) }
        visibilityListener = null
    }

    override fun destroy() = destroy(renderProcessGone = false)

    private fun destroy(renderProcessGone: Boolean) {
        if (destroyed) return
        destroyed = true
        timeout?.let(handler::removeCallbacks)
        removeVisibilityListener()
        measurementSession?.finish()
        measurementSession = null
        sessionObstructions = emptyList()
        try { bridge?.destroy() } catch (_: Exception) {} finally { bridge = null }
        rootView?.let { root -> (root.parent as? ViewGroup)?.removeView(root) }
        rootView = null
        webView?.let { view ->
            try { view.removeJavascriptInterface("EngageMraidNative") } catch (_: Exception) {}
            if (!renderProcessGone) {
                try { view.stopLoading() } catch (_: Exception) {}
                try { view.loadUrl("about:blank") } catch (_: Exception) {}
            }
            (view.parent as? ViewGroup)?.removeView(view)
            try { view.destroy() } catch (_: Exception) {}
        }
        webView = null
    }
}
