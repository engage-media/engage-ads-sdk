package com.engage.ads.render.mraid

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.media.AudioManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.engage.ads.DiagnosticEvent
import com.engage.ads.DiagnosticListener
import com.engage.ads.Redactor
import com.engage.ads.EngageError
import com.engage.ads.emitSafely
import org.json.JSONObject

internal class MraidBridge(
    private val context: Context,
    private val webView: WebView,
    private val interstitial: Boolean,
    private val diagnostics: DiagnosticListener,
    private val onClose: () -> Unit,
    private val onClick: (String) -> Unit,
    private val onFailure: (EngageError) -> Unit = {},
    private val allowTwoPartExpand: () -> Boolean = { true },
    private val onFriendlyObstructionAdded: (View) -> Unit = {},
    private val onFriendlyObstructionRemoved: (View) -> Unit = {},
) {
    val javascriptApi = JavascriptApi()
    private var expandDialog: Dialog? = null
    private var expandedWebView: WebView? = null
    private var expandedBridge: MraidBridge? = null
    private var resizeDialog: Dialog? = null
    private var videoDialog: Dialog? = null
    private var player: ExoPlayer? = null
    private var originalParent: ViewGroup? = null
    private var originalIndex = -1
    private var originalLayoutParams: ViewGroup.LayoutParams? = null
    private var previousOrientation: Int? = null
    private var defaultPosition: JSONObject? = null
    private var resizeWidth: Int? = null
    private var resizeHeight: Int? = null
    private var resizeTranslationX = 0f
    private var resizeTranslationY = 0f
    @Volatile private var destroyed = false
    private var observing = false
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { sendGeometry(); sendVisibility() }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { sendGeometry(); sendVisibility() }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { sendVisibility() }
    private val audioObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { sendAudio() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val commandRateLock = Any()
    private var commandWindowStartedAt = 0L
    private var commandCount = 0
    private var commandRejectionDiagnosed = false
    private val dynamicCloseObstructions = mutableSetOf<View>()
    private val visibilityPulse = object : Runnable {
        override fun run() { if (observing) { sendVisibility(); handler.postDelayed(this, 250) } }
    }

    inner class JavascriptApi {
        @JavascriptInterface
        fun postMessage(raw: String) {
            if (destroyed) return
            if (!acceptBridgeMessage(raw)) return
            webView.post {
                if (destroyed) return@post
                try {
                    val message = JSONObject(raw)
                    if (!message.has("id") || message.opt("id") !is Number) throw IllegalArgumentException("id must be an integer")
                    val number = message.opt("id") as Number
                    if (number.toDouble() % 1.0 != 0.0) throw IllegalArgumentException("id must be an integer")
                    val command = message.optString("command")
                    val args = message.optJSONObject("args") ?: throw IllegalArgumentException("args must be an object")
                    execute(command, args)
                } catch (error: Exception) {
                    diagnoseCommandRejection(Redactor.text(error.message.orEmpty()))
                    sendError(error.message ?: "invalid command", "postMessage")
                }
            }
        }
    }

    private fun acceptBridgeMessage(raw: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        var diagnostic: String? = null
        val accepted = synchronized(commandRateLock) {
            if (now - commandWindowStartedAt >= COMMAND_WINDOW_MILLIS || now < commandWindowStartedAt) {
                commandWindowStartedAt = now
                commandCount = 0
                commandRejectionDiagnosed = false
            }
            if (commandCount >= MAX_COMMANDS_PER_WINDOW) {
                diagnostic = rejectionDiagnosticLocked("MRAID command rate exceeded")
                false
            } else {
                commandCount += 1
                when {
                    raw.length > MAX_BRIDGE_MESSAGE_CHARS -> {
                        diagnostic = rejectionDiagnosticLocked("MRAID command exceeds the size limit")
                        false
                    }
                    jsonDepthExceeds(raw, MAX_BRIDGE_JSON_DEPTH) -> {
                        diagnostic = rejectionDiagnosticLocked("MRAID command nesting exceeds the limit")
                        false
                    }
                    else -> true
                }
            }
        }
        diagnostic?.let { message -> diagnostics.emitSafely(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "mraid_command_rejected", message)) }
        return accepted
    }

    private fun rejectionDiagnosticLocked(message: String): String? {
        if (commandRejectionDiagnosed) return null
        commandRejectionDiagnosed = true
        return message
    }

    private fun diagnoseCommandRejection(message: String) {
        val diagnostic = synchronized(commandRateLock) { rejectionDiagnosticLocked(message) } ?: return
        diagnostics.emitSafely(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "mraid_command_rejected", diagnostic))
    }

    fun sendReady() {
        if (destroyed) return
        val geometry = geometry()
        defaultPosition = geometry.current
        send(JSONObject().put("type", "ready")
            .put("state", "default")
            .put("placementType", if (interstitial) "interstitial" else "inline")
            .put("screenSize", geometry.screen)
            .put("maxSize", geometry.max)
            .put("currentPosition", geometry.current)
            .put("defaultPosition", geometry.default)
            .put("currentAppOrientation", orientation())
            .put("location", JSONObject.NULL)
            .put("supports", JSONObject()
                .put("sms", false).put("tel", false).put("calendar", false)
                .put("storePicture", false).put("inlineVideo", true).put("location", false)))
        sendGeometry()
        sendVisibility()
        sendAudio()
        if (!observing && webView.viewTreeObserver.isAlive) {
            observing = true
            webView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            webView.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            webView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
            try { context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, audioObserver) } catch (_: Exception) {}
            handler.post(visibilityPulse)
        }
    }

    fun sendExpandedReady() { sendReady(); sendState("expanded") }

    fun sendGeometry() {
        if (destroyed) return
        val value = geometry()
        send(JSONObject().put("type", "geometry").put("screenSize", value.screen).put("maxSize", value.max)
            .put("currentPosition", value.current).put("defaultPosition", value.default).put("currentAppOrientation", orientation()))
    }

    fun sendVisibility() {
        if (destroyed) return
        val rect = Rect()
        val visible = webView.isActuallyVisible() && webView.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0
        val area = (webView.width * webView.height).coerceAtLeast(1)
        val exposed = if (visible) ((rect.width().toLong() * rect.height() * 100L) / area).coerceIn(0, 100) else 0
        send(JSONObject().put("type", "visibility").put("viewable", visible).put("exposedPercentage", exposed)
            .put("visibleRectangle", rectJson(rect.left, rect.top, rect.width(), rect.height())).put("occlusionRectangles", org.json.JSONArray()))
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (observing && webView.viewTreeObserver.isAlive) {
            webView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            webView.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            webView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        }
        observing = false
        handler.removeCallbacks(visibilityPulse)
        try { context.contentResolver.unregisterContentObserver(audioObserver) } catch (_: Exception) {}
        try { restoreResize() } catch (_: Exception) {}
        try { restoreWebView() } catch (_: Exception) {}
        try { videoDialog?.dismiss() } catch (_: Exception) {} finally { videoDialog = null }
        try { player?.release() } catch (_: Exception) {} finally { player = null }
        try { restoreOrientation() } catch (_: Exception) {}
    }

    private fun sendAudio() {
        if (destroyed) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1)
        val volume = if (audio == null || max == null) JSONObject.NULL else audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max
        send(JSONObject().put("type", "audio").put("volume", volume))
    }

    private fun orientation(): JSONObject {
        val portrait = webView.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val requested = webView.context.findActivity()?.requestedOrientation
        val locked = requested != null && requested !in setOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_SENSOR, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
        return JSONObject().put("orientation", if (portrait) "portrait" else "landscape").put("locked", locked)
    }

    private fun execute(command: String, args: JSONObject) {
        if (destroyed) return
        when (command) {
            "open" -> open(args.requiredSafeUrl("url"))
            "close" -> close()
            "expand" -> expand(args.optionalHttpUrl("url"))
            "resize" -> resize(args.getJSONObject("properties"))
            "setOrientationProperties" -> orient(args)
            "playVideo" -> playVideo(args.requiredHttpUrl("url"))
            "storePicture", "createCalendarEvent" -> sendError("$command is unsupported", command)
            "unload" -> { restoreOrientation(); restoreResize(); restoreWebView(); onClose() }
            else -> sendError("unsupported command", command)
        }
    }

    private fun open(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme.isNullOrBlank() || scheme in setOf("javascript", "data", "file", "content", "about")) return sendError("URL scheme is prohibited", "open")
        signalClick(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return sendError("no application can open URL", "open")
        try { context.startActivity(intent) } catch (_: Exception) { sendError("unable to open URL", "open") }
    }

    private fun close() {
        if (expandDialog != null) {
            restoreWebView()
            restoreOrientation()
            sendState("default")
        } else if (resizeWidth != null) {
            restoreResize()
            restoreOrientation()
            sendState("default")
        } else if (interstitial) { restoreOrientation(); onClose() } else {
            restoreOrientation()
            webView.visibility = View.INVISIBLE
            sendState("hidden")
            onClose()
        }
    }

    private fun expand(url: String?) {
        if (interstitial) return sendError("expand is unavailable for interstitial", "expand")
        if (expandDialog != null) return
        if (url != null && !allowTwoPartExpand()) {
            return sendError("two-part expand is unavailable while Open Measurement is active", "expand")
        }
        restoreResize()
        val activity = webView.context.findActivity() ?: return sendError("expand requires an Activity", "expand")
        val frame = FrameLayout(activity)
        if (url == null) {
            rememberAndDetachWebView()
            frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        } else {
            val expanded = WebView(activity).also { expandedWebView = it }
            expanded.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            val childBridge = MraidBridge(activity, expanded, false, diagnostics, onClose = { close() }, onClick = onClick, onFailure = onFailure).also { expandedBridge = it }
            expanded.addJavascriptInterface(childBridge.javascriptApi, "EngageMraidNative")
            expanded.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String?) {
                    if (expandedWebView !== view) return
                    val script = MraidBridge::class.java.classLoader?.getResourceAsStream("mraid.js")?.bufferedReader()?.use { it.readText() }
                    if (script == null) { sendError("canonical mraid.js resource is missing", "expand"); close(); return }
                    view.evaluateJavascript(script) { if (expandedWebView === view) childBridge.sendExpandedReady() }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.hasGesture()) open(request.url.toString())
                    return true
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onFailure(EngageError(EngageError.Code.RENDER, "expanded HTML renderer process exited"))
                    try { restoreWebView() } catch (_: Exception) {}
                    return true
                }
            }
            frame.addView(expanded, FrameLayout.LayoutParams(-1, -1))
            expanded.loadUrl(url)
        }
        addCloseButton(frame, "top-right") { close() }
        expandDialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).also { dialog ->
            dialog.setContentView(frame)
            dialog.setOnDismissListener { restoreWebView(); sendState("default") }
            dialog.show()
        }
        sendState("expanded")
        webView.post { sendGeometry(); sendVisibility() }
    }

    private fun resize(properties: JSONObject) {
        if (interstitial) return sendError("resize is unavailable for interstitial", "resize")
        val density = webView.resources.displayMetrics.density
        val width = (properties.getDouble("width") * density).toInt()
        val height = (properties.getDouble("height") * density).toInt()
        val offsetX = (properties.getDouble("offsetX") * density).toInt()
        val offsetY = (properties.getDouble("offsetY") * density).toInt()
        if (width <= 0 || height <= 0) return sendError("resize dimensions must be positive", "resize")
        val allowOffscreen = properties.optBoolean("allowOffscreen", true)
        val activity = webView.context.findActivity() ?: return sendError("resize requires an Activity", "resize")
        val location = IntArray(2).also(webView::getLocationOnScreen)
        val screenWidth = webView.resources.displayMetrics.widthPixels
        val screenHeight = webView.resources.displayMetrics.heightPixels
        val requestedX = location[0] + offsetX
        val requestedY = location[1] + offsetY
        if (!allowOffscreen && (requestedX < 0 || requestedY < 0 || requestedX + width > screenWidth || requestedY + height > screenHeight)) {
            return sendError("resize would move creative offscreen", "resize")
        }
        restoreResize()
        resizeWidth = webView.layoutParams.width; resizeHeight = webView.layoutParams.height
        resizeTranslationX = webView.translationX; resizeTranslationY = webView.translationY
        rememberAndDetachWebView()

        val closePosition = properties.optString("customClosePosition", "top-right")
        val closeSize = (48 * density).toInt()
        val safe = (8 * density).toInt()
        val closeLocalX = when (closePosition) {
            "top-left", "center-left", "bottom-left" -> 0
            "top-center", "center", "bottom-center" -> (width - closeSize) / 2
            else -> width - closeSize
        }.coerceAtLeast(0)
        val closeLocalY = when (closePosition) {
            "bottom-left", "bottom-center", "bottom-right" -> height - closeSize
            "center-left", "center", "center-right" -> (height - closeSize) / 2
            else -> 0
        }.coerceAtLeast(0)
        // Even with allowOffscreen, the mandatory SDK close control remains inside the safe screen area.
        val x = requestedX.coerceIn(safe - closeLocalX, screenWidth - safe - closeSize - closeLocalX)
        val y = requestedY.coerceIn(safe - closeLocalY, screenHeight - safe - closeSize - closeLocalY)
        val overlay = FrameLayout(activity)
        val creativeFrame = FrameLayout(activity).apply {
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addCloseButton(this, closePosition) { close() }
        }
        overlay.addView(creativeFrame, FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y })
        resizeDialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).also { dialog ->
            dialog.setContentView(overlay)
            dialog.setCancelable(false)
            dialog.setOnDismissListener { restoreResize(); sendState("default") }
            dialog.show()
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        sendState("resized")
        webView.post { sendGeometry(); sendVisibility() }
    }

    private fun orient(args: JSONObject) {
        val activity = webView.context.findActivity() ?: return sendError("orientation requires an Activity", "setOrientationProperties")
        if (previousOrientation == null) previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = when (args.optString("forceOrientation", "none")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "none" -> if (args.optBoolean("allowOrientationChange", true)) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> return sendError("invalid forceOrientation", "setOrientationProperties")
        }
    }

    private fun playVideo(url: String) {
        val activity = webView.context.findActivity() ?: return sendError("playVideo requires an Activity", "playVideo")
        player?.release()
        val exo = ExoPlayer.Builder(activity).build().also { player = it }
        val playerView = PlayerView(activity).apply { player = exo }
        videoDialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).also { dialog ->
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(playerView)
            dialog.setOnDismissListener { exo.release(); if (player === exo) player = null }
            dialog.show()
        }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) videoDialog?.dismiss() }
        })
        exo.setMediaItem(MediaItem.fromUri(url)); exo.prepare(); exo.play()
    }

    private fun restoreWebView() {
        val dialog = expandDialog ?: return
        expandDialog = null
        expandedBridge?.destroy(); expandedBridge = null
        expandedWebView?.let { expanded ->
            expanded.removeJavascriptInterface("EngageMraidNative"); expanded.stopLoading()
            (expanded.parent as? ViewGroup)?.removeView(expanded); expanded.destroy()
        }
        expandedWebView = null
        clearDynamicCloseObstructions()
        if (originalParent != null) restoreOriginalWebView()
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }

    private fun restoreResize() {
        val width = resizeWidth ?: return
        val height = resizeHeight ?: return
        val dialog = resizeDialog
        resizeDialog = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        restoreOriginalWebView()
        webView.layoutParams = webView.layoutParams.apply { this.width = width; this.height = height }
        webView.translationX = resizeTranslationX; webView.translationY = resizeTranslationY
        resizeWidth = null; resizeHeight = null
        clearDynamicCloseObstructions()
        dialog?.setOnDismissListener(null); dialog?.dismiss()
        webView.requestLayout()
    }

    private fun rememberAndDetachWebView() {
        originalParent = webView.parent as? ViewGroup
        originalIndex = originalParent?.indexOfChild(webView) ?: -1
        originalLayoutParams = webView.layoutParams
        originalParent?.removeView(webView)
    }

    private fun restoreOriginalWebView() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        originalParent?.let { parent -> parent.addView(webView, originalIndex.coerceIn(0, parent.childCount), originalLayoutParams) }
        originalParent = null; originalIndex = -1; originalLayoutParams = null
    }

    private fun addCloseButton(frame: FrameLayout, position: String, action: () -> Unit) {
        val gravity = when (position) {
            "top-left" -> android.view.Gravity.TOP or android.view.Gravity.START
            "top-center" -> android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            "center-left" -> android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            "center" -> android.view.Gravity.CENTER
            "center-right" -> android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            "bottom-left" -> android.view.Gravity.BOTTOM or android.view.Gravity.START
            "bottom-center" -> android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            "bottom-right" -> android.view.Gravity.BOTTOM or android.view.Gravity.END
            else -> android.view.Gravity.TOP or android.view.Gravity.END
        }
        val close = TextView(frame.context).apply {
            text = "×"; textSize = 32f; this.gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0x99000000.toInt())
            contentDescription = "Close ad"; isFocusable = true; isClickable = true; setOnClickListener { action() }
        }
        frame.addView(close, FrameLayout.LayoutParams((48 * frame.resources.displayMetrics.density).toInt(), (48 * frame.resources.displayMetrics.density).toInt(), gravity))
        dynamicCloseObstructions += close
        onFriendlyObstructionAdded(close)
    }

    private fun clearDynamicCloseObstructions() {
        dynamicCloseObstructions.toList().forEach(onFriendlyObstructionRemoved)
        dynamicCloseObstructions.clear()
    }

    private fun restoreOrientation() {
        val prior = previousOrientation ?: return
        webView.context.findActivity()?.requestedOrientation = prior
        previousOrientation = null
    }

    private fun sendState(state: String) = send(JSONObject().put("type", "state").put("state", state))
    private fun sendError(message: String, action: String) = send(JSONObject().put("type", "error").put("message", message).put("action", action))
    private fun signalClick(url: String) { if (!destroyed) onClick(url) }
    private fun send(message: JSONObject) {
        if (destroyed) return
        val quoted = JSONObject.quote(message.toString())
        webView.evaluateJavascript("window.__engageMraid&&window.__engageMraid.receive($quoted);", null)
    }

    private data class Geometry(val screen: JSONObject, val max: JSONObject, val current: JSONObject, val default: JSONObject)
    private fun geometry(): Geometry {
        val density = webView.resources.displayMetrics.density
        val metrics = webView.resources.displayMetrics
        val location = IntArray(2); webView.getLocationOnScreen(location)
        fun dp(px: Int) = (px / density).toInt()
        val current = rectJson(dp(location[0]), dp(location[1]), dp(webView.width), dp(webView.height))
        return Geometry(sizeJson(dp(metrics.widthPixels), dp(metrics.heightPixels)), sizeJson(dp(webView.rootView.width), dp(webView.rootView.height)), current, defaultPosition ?: current)
    }

    private fun sizeJson(width: Int, height: Int) = JSONObject().put("width", width).put("height", height)
    private fun rectJson(x: Int, y: Int, width: Int, height: Int) = JSONObject().put("x", x).put("y", y).put("width", width).put("height", height)

    private companion object {
        const val MAX_BRIDGE_MESSAGE_CHARS = 65_536
        const val MAX_BRIDGE_JSON_DEPTH = 8
        const val MAX_COMMANDS_PER_WINDOW = 64
        const val COMMAND_WINDOW_MILLIS = 1_000L

        fun jsonDepthExceeds(value: String, limit: Int): Boolean {
            var depth = 0
            var quoted = false
            var escaped = false
            for (character in value) {
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> quoted = false
                    }
                    continue
                }
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { depth += 1; if (depth > limit) return true }
                    '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
                }
            }
            return false
        }
    }
}

private fun JSONObject.requiredHttpUrl(name: String): String = optionalHttpUrl(name) ?: throw IllegalArgumentException("$name is required")
private fun JSONObject.requiredSafeUrl(name: String): String {
    val value = optString(name).takeIf { has(name) && it.isNotBlank() } ?: throw IllegalArgumentException("$name is required")
    val scheme = Uri.parse(value).scheme?.lowercase()
    if (scheme.isNullOrBlank() || scheme in setOf("javascript", "data", "file", "content", "about")) throw IllegalArgumentException("$name uses a prohibited scheme")
    return value
}
private fun JSONObject.optionalHttpUrl(name: String): String? {
    if (!has(name) || isNull(name) || optString(name).isBlank()) return null
    val value = getString(name)
    val uri = Uri.parse(value)
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw IllegalArgumentException("$name must be HTTP(S)")
    return value
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun View.isActuallyVisible(): Boolean {
    if (!isShown || windowVisibility != View.VISIBLE || !hasWindowFocus() || alpha <= 0f) return false
    var ancestor = parent
    while (ancestor is View) {
        if (ancestor.visibility != View.VISIBLE || ancestor.alpha <= 0f) return false
        ancestor = ancestor.parent
    }
    return true
}
