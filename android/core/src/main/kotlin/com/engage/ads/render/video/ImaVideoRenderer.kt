package com.engage.ads.render.video

import android.content.Context
import android.annotation.SuppressLint
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import com.engage.ads.*
import com.engage.ads.render.ContentController
import com.engage.ads.render.CreativeRenderer
import com.engage.ads.render.mraid.isActuallyVisible
import com.google.ads.interactivemedia.v3.api.*
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal class ImaVideoRenderer(
    private val context: Context,
    private val format: AdFormat,
    private val contentController: ContentController?,
    private val creativeTimeoutMillis: Long,
    private val friendlyObstructions: List<com.engage.ads.FriendlyObstruction> = emptyList(),
    private val diagnostics: DiagnosticListener = DiagnosticListener.NONE,
) : CreativeRenderer, VideoAdPlayer {
    private val factory = ImaSdkFactory.getInstance()
    private val callbacks = CopyOnWriteArraySet<VideoAdPlayer.VideoAdPlayerCallback>()
    private val handler = Handler(Looper.getMainLooper())
    private val rewardSent = AtomicBoolean(false)
    private val displayedSent = AtomicBoolean(false)
    private val breakSent = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private var callback: CreativeRenderer.Callback? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var adsLoader: AdsLoader? = null
    private var adsManager: AdsManager? = null
    private var displayContainer: AdDisplayContainer? = null
    private var currentMedia: AdMediaInfo? = null
    private var pausedContent = false
    private var destroyed = false
    private var started = false
    private var currentCreativeStarted = false
    private var currentCreativeCompleted = false
    private var previousFocus: View? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var visibilityListener: ViewTreeObserver.OnPreDrawListener? = null
    private val creativeTimeout = Runnable { fail(EngageError(EngageError.Code.TIMEOUT, "video creative did not start in time")) }

    @SuppressLint("UnsafeOptInUsageError")
    override fun display(creative: LoadedCreative, request: AdRequest, container: ViewGroup, callback: CreativeRenderer.Callback) {
        this.callback = callback
        previousFocus = container.rootView.findFocus()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MILLIS,
                MAX_BUFFER_MILLIS,
                PLAYBACK_START_BUFFER_MILLIS,
                REBUFFER_START_BUFFER_MILLIS,
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val exo = ExoPlayer.Builder(container.context).setLoadControl(loadControl).build().also { player = it }
        val view = PlayerView(container.context).apply { useController = false; isFocusable = true; player = exo }.also { playerView = it }
        container.removeAllViews()
        container.addView(view, ViewGroup.LayoutParams(-1, -1))
        view.requestFocus()
        focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!started || terminal.get()) return@OnWindowFocusChangeListener
            guardLifecycle("IMA focus update") { if (hasFocus) adsManager?.resume() else adsManager?.pause() }
        }.also { view.viewTreeObserver.addOnWindowFocusChangeListener(it) }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val media = currentMedia ?: return
                if (state == Player.STATE_READY) notifyImaCallbacks("loaded") { it.onLoaded(media) }
                if (state == Player.STATE_BUFFERING) notifyImaCallbacks("buffering") { it.onBuffering(media) }
                if (state == Player.STATE_ENDED) notifyImaCallbacks("ended") { it.onEnded(media) }
            }
            override fun onPlayerError(error: PlaybackException) {
                currentMedia?.let { media -> notifyImaCallbacks("error") { it.onError(media) } }
                fail(EngageError(EngageError.Code.RENDER, "video playback failed", error))
            }
        })
        val imaDisplayContainer = ImaSdkFactory.createAdDisplayContainer(container, this).also { displayContainer = it }
        friendlyObstructions.forEach { obstruction ->
            try {
                imaDisplayContainer.registerFriendlyObstruction(factory.createFriendlyObstruction(
                    obstruction.view,
                    obstruction.purpose.toImaPurpose(),
                    obstruction.detailedReason,
                ))
            } catch (_: Exception) {
                diagnostics.emitSafely(DiagnosticEvent(
                    DiagnosticEvent.Level.WARNING,
                    "measurement_obstruction_failed",
                    "IMA friendly obstruction registration failed",
                    mapOf("purpose" to obstruction.purpose.name.lowercase()),
                ))
            }
        }
        val loader = factory.createAdsLoader(context, factory.createImaSdkSettings(), imaDisplayContainer).also { adsLoader = it }
        loader.addAdErrorListener { fail(EngageError(EngageError.Code.RENDER, "IMA error: ${it.error.message}")) }
        loader.addAdsLoadedListener { event ->
            try {
                val manager = event.adsManager ?: return@addAdsLoadedListener fail(EngageError(EngageError.Code.RENDER, "IMA returned no ads manager"))
                if (destroyed) { manager.destroy(); return@addAdsLoadedListener }
                adsManager = manager.apply {
                    addAdErrorListener { fail(EngageError(EngageError.Code.RENDER, "IMA manager error: ${it.error.message}")) }
                    addAdEventListener { adEvent -> guardLifecycle("IMA ad event") { onAdEvent(adEvent) } }
                    init(factory.createAdsRenderingSettings())
                }
            } catch (error: Exception) {
                fail(EngageError(EngageError.Code.RENDER, "IMA initialization failed", error))
            }
        }
        val adsRequest = factory.createAdsRequest().apply {
            if (creative.markup.trimStart().startsWith("<")) setAdsResponse(creative.markup) else setAdTagUrl(creative.markup)
        }
        loader.requestAds(adsRequest)
        handler.post(progressTick)
        handler.postDelayed(creativeTimeout, creativeTimeoutMillis)
    }

    private fun onAdEvent(event: com.google.ads.interactivemedia.v3.api.AdEvent) {
        if (destroyed || terminal.get()) return
        when (event.type) {
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.LOADED -> {
                if (format == AdFormat.REWARDED && event.ad?.adPodInfo?.totalAds != 1) {
                    fail(EngageError(EngageError.Code.UNSUPPORTED_CREATIVE, "rewarded placements require exactly one creative")); return
                }
                currentCreativeStarted = false
                currentCreativeCompleted = false
                adsManager?.start()
            }
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CONTENT_PAUSE_REQUESTED -> if (!pausedContent) {
                pausedContent = true; contentController?.pauseContent()
            }
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CONTENT_RESUME_REQUESTED -> resumeContent()
            // IMA 3.40 does not expose a separate IMPRESSION enum. STARTED is emitted
            // when IMA has started the creative and dispatched its VAST impression.
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.STARTED -> {
                started = true
                currentCreativeStarted = true
                dispatchDisplayedWhenVisible()
            }
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CLICKED, com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.TAPPED -> callback?.clicked(null)
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.COMPLETED -> {
                if (!currentCreativeStarted || currentCreativeCompleted || !displayedSent.get()) return
                currentCreativeCompleted = true
                callback?.adCompleted()
                if (format == AdFormat.REWARDED && rewardSent.compareAndSet(false, true)) callback?.rewardEarned()
            }
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.SKIPPED -> {
                val soleCreative = event.ad?.adPodInfo?.totalAds == 1
                if ((format == AdFormat.REWARDED || soleCreative) && terminal.compareAndSet(false, true)) {
                    handler.removeCallbacks(creativeTimeout); removeVisibilityListener(); resumeContent(); restoreFocus()
                    callback?.dismissed(); destroy()
                }
            }
            com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_ENDED, com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.ALL_ADS_COMPLETED -> if (breakSent.compareAndSet(false, true)) {
                terminal.set(true); handler.removeCallbacks(creativeTimeout); removeVisibilityListener(); resumeContent(); restoreFocus()
                callback?.breakCompleted(); destroy()
            }
            else -> Unit
        }
    }

    override fun loadAd(media: AdMediaInfo, podInfo: AdPodInfo) {
        guardLifecycle("video load") {
            currentMedia = media
            player?.apply { setMediaItem(MediaItem.fromUri(Uri.parse(media.url))); prepare() }
        }
    }

    override fun playAd(media: AdMediaInfo) {
        guardLifecycle("video play") {
            val exo = player ?: return@guardLifecycle
            val wasPaused = !exo.playWhenReady && exo.currentPosition > 0
            exo.play()
            notifyImaCallbacks(if (wasPaused) "resume" else "play") { if (wasPaused) it.onResume(media) else it.onPlay(media) }
        }
    }

    override fun pauseAd(media: AdMediaInfo) = guardLifecycle("video pause") {
        player?.pause(); notifyImaCallbacks("pause") { it.onPause(media) }
    }
    override fun stopAd(media: AdMediaInfo) = guardLifecycle("video stop") { player?.stop() }
    override fun getAdProgress(): VideoProgressUpdate {
        return try {
            val exo = player ?: return VideoProgressUpdate.VIDEO_TIME_NOT_READY
            val duration = exo.duration
            if (duration <= 0) VideoProgressUpdate.VIDEO_TIME_NOT_READY else VideoProgressUpdate(exo.currentPosition, duration)
        } catch (_: Exception) {
            VideoProgressUpdate.VIDEO_TIME_NOT_READY
        }
    }
    override fun getVolume(): Int {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }
    override fun addCallback(callback: VideoAdPlayer.VideoAdPlayerCallback) { callbacks += callback }
    override fun removeCallback(callback: VideoAdPlayer.VideoAdPlayerCallback) { callbacks -= callback }
    override fun release() { destroy() }

    private val progressTick = object : Runnable {
        override fun run() {
            val media = currentMedia
            if (!destroyed && media != null) notifyImaCallbacks("progress") { it.onAdProgress(media, adProgress) }
            if (!destroyed) handler.postDelayed(this, 250)
        }
    }

    private fun fail(error: EngageError) {
        if (destroyed || !terminal.compareAndSet(false, true)) return
        handler.removeCallbacks(creativeTimeout)
        removeVisibilityListener()
        resumeContent()
        callback?.failed(error)
        destroy()
    }

    private fun resumeContent() {
        if (pausedContent) { pausedContent = false; contentController?.resumeContent() }
    }

    private fun dispatchDisplayedWhenVisible() {
        val view = playerView ?: return
        fun isVisible(): Boolean {
            val rect = android.graphics.Rect()
            return view.width > 0 && view.height > 0 && view.isActuallyVisible() &&
                view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0
        }
        if (isVisible()) {
            removeVisibilityListener()
            handler.removeCallbacks(creativeTimeout)
            if (displayedSent.compareAndSet(false, true)) callback?.displayed()
        } else if (visibilityListener == null) {
            visibilityListener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (destroyed || terminal.get()) { removeVisibilityListener(); return true }
                    if (!isVisible()) return true
                    removeVisibilityListener()
                    dispatchDisplayedWhenVisible()
                    return true
                }
            }.also { view.viewTreeObserver.addOnPreDrawListener(it) }
        }
    }

    private fun removeVisibilityListener() {
        val listener = visibilityListener ?: return
        playerView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(listener) }
        visibilityListener = null
    }

    private fun restoreFocus() { previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus(); previousFocus = null }

    private inline fun guardLifecycle(operation: String, action: () -> Unit) {
        if (destroyed || terminal.get()) return
        try {
            action()
        } catch (error: Exception) {
            fail(EngageError(EngageError.Code.RENDER, "$operation failed", error))
        }
    }

    private inline fun notifyImaCallbacks(operation: String, action: (VideoAdPlayer.VideoAdPlayerCallback) -> Unit) {
        for (imaCallback in callbacks) {
            try {
                action(imaCallback)
            } catch (error: Exception) {
                fail(EngageError(EngageError.Code.RENDER, "IMA video callback $operation failed", error))
                return
            }
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(creativeTimeout)
        removeVisibilityListener()
        resumeContent()
        try { adsManager?.destroy() } catch (_: Exception) {} finally { adsManager = null }
        try { adsLoader?.release() } catch (_: Exception) {} finally { adsLoader = null }
        displayContainer?.let { value ->
            try { value.unregisterAllFriendlyObstructions() } catch (_: Exception) {
                diagnostics.emitSafely(DiagnosticEvent(
                    DiagnosticEvent.Level.WARNING,
                    "measurement_obstruction_failed",
                    "IMA friendly obstruction cleanup failed",
                    mapOf("operation" to "unregister"),
                ))
            }
            try { value.destroy() } catch (_: Exception) {}
        }
        displayContainer = null
        playerView?.let { view ->
            try { focusListener?.let { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(it) } } catch (_: Exception) {}
            try { view.player = null } catch (_: Exception) {}
        }
        playerView = null; focusListener = null
        try { player?.release() } catch (_: Exception) {} finally { player = null }
        try { restoreFocus() } catch (_: Exception) {}
        callbacks.clear()
        callback = null
    }

    private companion object {
        const val MIN_BUFFER_MILLIS = 2_500
        const val MAX_BUFFER_MILLIS = 10_000
        const val PLAYBACK_START_BUFFER_MILLIS = 500
        const val REBUFFER_START_BUFFER_MILLIS = 1_000
        const val TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    }
}

private fun com.engage.ads.FriendlyObstructionPurpose.toImaPurpose(): com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose = when (this) {
    com.engage.ads.FriendlyObstructionPurpose.VIDEO_CONTROLS -> com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose.VIDEO_CONTROLS
    com.engage.ads.FriendlyObstructionPurpose.CLOSE_AD -> com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose.CLOSE_AD
    com.engage.ads.FriendlyObstructionPurpose.NOT_VISIBLE -> com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose.NOT_VISIBLE
    com.engage.ads.FriendlyObstructionPurpose.OTHER -> com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose.OTHER
}
