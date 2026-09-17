package com.engage.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.engage.ads.core.*
import com.engage.ads.network.HttpTransport
import com.engage.ads.network.VastUrlBuilder
import com.engage.ads.render.ContentController
import com.engage.ads.render.CreativeRenderer
import com.engage.ads.render.mraid.HtmlRenderer
import com.engage.ads.render.nativead.NativeRenderer
import com.engage.ads.render.video.ImaVideoRenderer
import kotlinx.coroutines.*
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

class EngageClient(
    context: Context,
    configuration: EngageConfiguration,
) {
    private val context = context.applicationContext
    val configuration: EngageConfiguration = configuration.deepCopy()
    private val measurement = OpenMeasurementCoordinator.snapshot(this.configuration.measurementBackend, this.configuration.diagnostics)
    private val privacy = AtomicReference(PrivacySettings())
    private val transport = HttpTransport(this.configuration.requestTimeoutMillis, this.configuration.diagnostics)
    private val requestBuilder = OpenRtbRequestBuilder(this.configuration, System.getProperty("http.agent").orEmpty(), measurement)
    private val responseParser = OpenRtbResponseParser(this.configuration.diagnostics, measurement = measurement)
    private val vastUrlBuilder = VastUrlBuilder()
    private val ads = ConcurrentHashMap.newKeySet<EngageAd>()
    private val lifecycleLock = Any()
    private val noticeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val destroyed = AtomicBoolean(false)
    internal var rendererFactoryForTesting: ((LoadedCreative, AdRequest) -> CreativeRenderer)? = null

    fun updatePrivacy(settings: PrivacySettings) {
        val snapshot = settings.deepCopy()
        synchronized(lifecycleLock) {
            check(!destroyed.get()) { "client is destroyed" }
            privacy.set(snapshot)
        }
    }

    fun createAd(request: AdRequest, listener: (AdEvent) -> Unit): EngageAd {
        check(!destroyed.get()) { "client is destroyed" }
        validateEndpoint(request)
        val ad = EngageAd(context, request, listener, configuration, { privacy.get().deepCopy() }, transport, requestBuilder, responseParser, vastUrlBuilder, noticeScope, measurement, rendererFactoryForTesting, onDestroyed = { ads.remove(it) })
        val registered = synchronized(lifecycleLock) {
            if (destroyed.get()) false else ads.add(ad)
        }
        if (!registered) {
            ad.destroy()
            throw IllegalStateException("client is destroyed")
        }
        return ad
    }

    fun createBannerAd(request: AdRequest, listener: (AdEvent) -> Unit): BannerAd =
        BannerAd(createFormatAd(request, AdFormat.BANNER, listener))

    fun createInterstitialAd(request: AdRequest, listener: (AdEvent) -> Unit): InterstitialAd =
        InterstitialAd(createFormatAd(request, AdFormat.INTERSTITIAL, listener))

    fun createRewardedAd(request: AdRequest, listener: (AdEvent) -> Unit): RewardedAd =
        RewardedAd(createFormatAd(request, AdFormat.REWARDED, listener))

    fun createNativeAd(request: AdRequest, listener: (AdEvent) -> Unit): NativeAd =
        NativeAd(createFormatAd(request, AdFormat.NATIVE, listener))

    fun createInStreamAd(request: AdRequest, listener: (AdEvent) -> Unit): InStreamAd =
        InStreamAd(createFormatAd(request, AdFormat.INSTREAM, listener))

    private fun createFormatAd(request: AdRequest, format: AdFormat, listener: (AdEvent) -> Unit): EngageAd {
        if (request.format != format) throw EngageError(EngageError.Code.INVALID_REQUEST, "request format must be $format")
        return createAd(request, listener)
    }

    fun destroy() {
        val ownedAds = synchronized(lifecycleLock) {
            if (!destroyed.compareAndSet(false, true)) return
            ads.toList().also { ads.clear() }
        }
        ownedAds.forEach(EngageAd::destroy)
        noticeScope.cancel()
    }

    private fun validateEndpoint(request: AdRequest) {
        if (configuration.deviceCategory == DeviceCategory.TV && request.format != AdFormat.INSTREAM) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "TV clients accept instream requests only")
        }
        if (configuration.endpoint is Endpoint.VastTag && request.format !in setOf(AdFormat.INSTREAM, AdFormat.REWARDED, AdFormat.INTERSTITIAL)) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "direct VAST supports instream, rewarded, and video interstitial only")
        }
        if (configuration.endpoint is Endpoint.VastTag && request.format == AdFormat.INTERSTITIAL && request.video == null) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "direct VAST interstitial requests must declare video constraints")
        }
    }
}

class EngageAd internal constructor(
    private val context: Context,
    request: AdRequest,
    private val listener: (AdEvent) -> Unit,
    private val configuration: EngageConfiguration,
    private val privacyProvider: () -> PrivacySettings,
    private val transport: HttpTransport,
    private val requestBuilder: OpenRtbRequestBuilder,
    private val responseParser: OpenRtbResponseParser,
    private val vastUrlBuilder: VastUrlBuilder,
    private val noticeScope: CoroutineScope,
    private val measurement: OpenMeasurementCoordinator = OpenMeasurementCoordinator.disabled(configuration.diagnostics),
    private val rendererFactoryForTesting: ((LoadedCreative, AdRequest) -> CreativeRenderer)? = null,
    private val onDestroyed: (EngageAd) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutableState = AtomicReference(AdState.IDLE)
    private val generation = AtomicLong(0)
    private val burlSent = AtomicBoolean(false)
    private val displayedSent = AtomicBoolean(false)
    private val terminalSent = AtomicBoolean(false)
    private val loadCancellationDiagnosed = AtomicBoolean(false)
    private val rewardSent = AtomicBoolean(false)
    private val singleCreativeCompletionSent = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var creative: LoadedCreative? = null
    private var renderer: CreativeRenderer? = null
    private var contentController: ContentController? = null
    private var contentPaused = false
    private var requestSnapshot: AdRequest? = null

    val request: AdRequest = request.deepCopyAndValidate()

    val state: AdState get() = mutableState.get()

    fun load() {
        if (!mutableState.compareAndSet(AdState.IDLE, AdState.LOADING)) {
            deliver(AdEvent.Error(EngageError(EngageError.Code.INVALID_STATE, "load is valid only in idle state")))
            return
        }
        val currentGeneration = generation.get()
        val snapshot = request.deepCopyAndValidate().also { requestSnapshot = it }
        val privacySnapshot = privacyProvider()
        scope.launch {
            try {
                val loaded = loadCreative(snapshot, privacySnapshot)
                if (!isCurrent(currentGeneration)) return@launch
                creative = loaded
                mutableState.set(AdState.READY)
                deliver(AdEvent.Loaded, currentGeneration)
            } catch (noFill: NoFillSignal) {
                if (!isCurrent(currentGeneration)) return@launch
                mutableState.set(AdState.FINISHED)
                diagnose(DiagnosticEvent.Level.INFO, "ad_load_no_fill", mapOf("format" to snapshot.format.name.lowercase()))
                deliver(AdEvent.NoFill, currentGeneration)
            } catch (cancelled: CancellationException) {
                diagnoseLoadCancellation(snapshot.format)
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(currentGeneration)) return@launch
                if ((error as? EngageError)?.code == EngageError.Code.NO_FILL) {
                    mutableState.set(AdState.FINISHED)
                    diagnose(DiagnosticEvent.Level.INFO, "ad_load_no_fill", mapOf("format" to snapshot.format.name.lowercase()))
                    deliver(AdEvent.NoFill, currentGeneration)
                } else {
                    val engageError = error.asEngageError()
                    mutableState.set(AdState.FAILED)
                    diagnose(DiagnosticEvent.Level.ERROR, "ad_load_failed", mapOf("format" to snapshot.format.name.lowercase(), "error_code" to engageError.code.name.lowercase()))
                    deliver(AdEvent.Error(engageError), currentGeneration)
                }
            }
        }
    }

    fun display(
        container: ViewGroup,
        contentController: ContentController? = null,
        friendlyObstructions: List<FriendlyObstruction> = emptyList(),
    ) {
        val maximumHostObstructions = if (request.format == AdFormat.BANNER ||
            (request.format == AdFormat.INTERSTITIAL && request.video == null)) {
            ResourceLimits.MAX_FRIENDLY_OBSTRUCTIONS - 1
        } else ResourceLimits.MAX_FRIENDLY_OBSTRUCTIONS
        require(friendlyObstructions.size <= maximumHostObstructions) { "too many friendly obstructions" }
        require(friendlyObstructions.map { it.view }.distinct().size == friendlyObstructions.size) { "friendly obstruction views must be unique" }
        val loaded = creative
        val loadedRequest = requestSnapshot ?: request
        if (state != AdState.READY || loaded == null) {
            diagnose(DiagnosticEvent.Level.WARNING, "ad_display_failed", mapOf("format" to request.format.name.lowercase(), "error_code" to EngageError.Code.INVALID_STATE.name.lowercase()))
            deliver(AdEvent.Error(EngageError(EngageError.Code.INVALID_STATE, "display is valid only in ready state")))
            return
        }
        if (loaded.expiresAt?.let { !it.isAfter(clock.instant()) } == true) {
            mutableState.set(AdState.FAILED)
            diagnose(DiagnosticEvent.Level.WARNING, "ad_display_failed", mapOf("format" to request.format.name.lowercase(), "error_code" to EngageError.Code.EXPIRED.name.lowercase()))
            deliver(AdEvent.Error(EngageError(EngageError.Code.EXPIRED, "bid expired before display")))
            return
        }
        if (!mutableState.compareAndSet(AdState.READY, AdState.DISPLAYING)) return
        this.contentController = contentController
        val obstructionSnapshot = friendlyObstructions.toList()
        val currentGeneration = generation.get()
        main.post {
            if (!isCurrent(currentGeneration)) return@post
            var selected: CreativeRenderer? = null
            try {
                val created = rendererFactoryForTesting?.invoke(loaded, loadedRequest) ?: when (loaded.kind) {
                    CreativeKind.HTML -> HtmlRenderer(context, configuration.creativeTimeoutMillis, configuration.diagnostics, measurement, obstructionSnapshot)
                    CreativeKind.NATIVE -> NativeRenderer(context, transport, configuration.creativeTimeoutMillis, measurement, obstructionSnapshot, configuration.diagnostics)
                    CreativeKind.VAST -> ImaVideoRenderer(context, loadedRequest.format, trackingContentController(contentController), configuration.creativeTimeoutMillis, obstructionSnapshot, configuration.diagnostics)
                }
                selected = created
                renderer = created
                created.display(loaded, loadedRequest, container, callback(currentGeneration, loaded))
            } catch (error: Exception) {
                selected?.let(::destroyRendererSafely)
                if (renderer === selected) renderer = null
                callback(currentGeneration, loaded).failed(error)
            }
        }
    }

    fun destroy() {
        val previousState = mutableState.getAndSet(AdState.DESTROYED)
        if (previousState == AdState.DESTROYED) return
        if (previousState == AdState.LOADING) diagnoseLoadCancellation(request.format)
        generation.incrementAndGet()
        scope.cancel()
        val release = {
            renderer?.let(::destroyRendererSafely)
            renderer = null
            resumeContentOnMain()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else main.post(release)
        creative = null
        onDestroyed(this)
    }

    private suspend fun loadCreative(request: AdRequest, privacySnapshot: PrivacySettings): LoadedCreative = when (val endpoint = configuration.endpoint) {
        is Endpoint.VastTag -> {
            val response = transport.get(vastUrlBuilder.build(endpoint, privacySnapshot, configuration, System.getProperty("http.agent").orEmpty()))
            if (response.code == 204 || (response.code in 200..299 && response.body.isBlank())) throw NoFillSignal()
            if (response.code !in 200..299) throw EngageError(EngageError.Code.NETWORK, "VAST endpoint returned HTTP ${response.code}")
            validateVastMarkup(response.body)
            LoadedCreative(response.body, CreativeKind.VAST, UUID.randomUUID().toString(), null, UUID.randomUUID().toString(), null, null)
        }
        is Endpoint.OpenRTB26 -> {
            val wire = requestBuilder.build(request, privacySnapshot)
            val response = transport.postJson(endpoint.url, endpoint.headers, wire.body)
            if (response.code == 204) throw NoFillSignal()
            if (response.code !in 200..299) throw EngageError(EngageError.Code.NETWORK, "OpenRTB endpoint returned HTTP ${response.code}")
            if (response.body.isBlank()) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "OpenRTB response body is empty")
            when (val result = responseParser.parse(response.body, wire.requestId, wire.impId, request)) {
                AuctionResult.NoFill -> throw NoFillSignal()
                is AuctionResult.MarkupRequired -> {
                    val markup = transport.get(result.url)
                    if (markup.code !in 200..299 || markup.body.isBlank()) throw EngageError(EngageError.Code.NETWORK, "nurl did not return creative markup")
                    responseParser.normalizeFetchedMarkup(result.bid, markup.body, request)
                }
                is AuctionResult.Ready -> {
                    result.creative.winNotice?.let { noticeScope.launch { transport.notify(it) } }
                    result.creative
                }
            }
        }
    }

    private fun callback(expectedGeneration: Long, loaded: LoadedCreative) = object : CreativeRenderer.Callback {
        override fun displayed() {
            if (!isDisplaying(expectedGeneration) || !displayedSent.compareAndSet(false, true)) return
            loaded.burl?.takeIf { burlSent.compareAndSet(false, true) }?.let { noticeScope.launch { transport.notify(it) } }
            deliver(AdEvent.Displayed, expectedGeneration)
        }
        override fun clicked(url: String?) { if (isDisplaying(expectedGeneration)) deliver(AdEvent.Clicked(url), expectedGeneration) }
        override fun adCompleted() {
            if (!isDisplaying(expectedGeneration) || !displayedSent.get()) return
            if (request.format != AdFormat.INSTREAM && !singleCreativeCompletionSent.compareAndSet(false, true)) return
            deliver(AdEvent.AdCompleted, expectedGeneration)
        }
        override fun breakCompleted() { finish(AdEvent.BreakCompleted, expectedGeneration) }
        override fun dismissed() { finish(AdEvent.Dismissed, expectedGeneration) }
        override fun rewardEarned() {
            if (isDisplaying(expectedGeneration) && displayedSent.get() && singleCreativeCompletionSent.get() &&
                request.format == AdFormat.REWARDED && rewardSent.compareAndSet(false, true)) {
                deliver(AdEvent.RewardEarned, expectedGeneration)
            }
        }
        override fun failed(error: Throwable) {
            if (error is Error) throw error
            if (!isDisplaying(expectedGeneration) || !terminalSent.compareAndSet(false, true)) return
            val engageError = error.asEngageError()
            mutableState.set(AdState.FAILED)
            resumeContent()
            releaseRenderer()
            diagnose(DiagnosticEvent.Level.ERROR, "ad_render_failed", mapOf("format" to request.format.name.lowercase(), "error_code" to engageError.code.name.lowercase()))
            deliver(AdEvent.Error(engageError), expectedGeneration)
        }
    }

    private fun finish(event: AdEvent, expectedGeneration: Long) {
        if (!isDisplaying(expectedGeneration) || !terminalSent.compareAndSet(false, true)) return
        mutableState.set(AdState.FINISHED)
        resumeContent()
        releaseRenderer()
        deliver(event, expectedGeneration)
    }

    internal fun markContentPaused() { contentPaused = true }
    private fun resumeContent() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::resumeContentOnMain)
            return
        }
        resumeContentOnMain()
    }

    private fun resumeContentOnMain() {
        if (contentPaused) {
            contentPaused = false
            try {
                contentController?.resumeContent()
            } catch (_: Exception) {
                diagnose(DiagnosticEvent.Level.WARNING, "host_content_callback_failed", mapOf("operation" to "resume"))
            }
        }
    }

    private fun isCurrent(expected: Long) = generation.get() == expected && state != AdState.DESTROYED
    private fun isDisplaying(expected: Long) = generation.get() == expected && state == AdState.DISPLAYING
    private fun deliver(event: AdEvent, expected: Long = generation.get()) {
        main.post {
            if (!isCurrent(expected)) return@post
            try {
                listener(event)
            } catch (_: Exception) {
                diagnose(DiagnosticEvent.Level.WARNING, "host_event_callback_failed", mapOf("event" to event.javaClass.simpleName))
            }
        }
    }

    private class NoFillSignal : Exception()

    private fun diagnoseLoadCancellation(format: AdFormat) {
        if (loadCancellationDiagnosed.compareAndSet(false, true)) {
            diagnose(DiagnosticEvent.Level.INFO, "ad_load_cancelled", mapOf("format" to format.name.lowercase()))
        }
    }

    private fun diagnose(level: DiagnosticEvent.Level, code: String, metadata: Map<String, String>) {
        configuration.diagnostics.emitSafely(DiagnosticEvent(level, code, code.replace('_', ' '), metadata))
    }

    private fun trackingContentController(delegate: ContentController?): ContentController? = delegate?.let {
        object : ContentController {
            override fun pauseContent() {
                if (contentPaused) return
                contentPaused = true
                try {
                    it.pauseContent()
                } catch (_: Exception) {
                    diagnose(DiagnosticEvent.Level.WARNING, "host_content_callback_failed", mapOf("operation" to "pause"))
                }
            }
            override fun resumeContent() = resumeContentOnMain()
        }
    }

    private fun releaseRenderer() {
        main.post {
            renderer?.let(::destroyRendererSafely)
            renderer = null
        }
    }

    private fun destroyRendererSafely(value: CreativeRenderer) {
        try {
            value.destroy()
        } catch (_: Exception) {
            diagnose(DiagnosticEvent.Level.WARNING, "renderer_destroy_failed", mapOf("format" to request.format.name.lowercase()))
        }
    }
}

private fun Throwable.asEngageError(): EngageError = this as? EngageError
    ?: EngageError(EngageError.Code.RENDER, message ?: "ad operation failed", this)

private fun EngageConfiguration.deepCopy() = copy(
    endpoint = when (val value = endpoint) {
        is Endpoint.OpenRTB26 -> value.copy(headers = value.headers.toMap())
        is Endpoint.VastTag -> value.copy(parameters = value.parameters.toMap())
    },
    app = app.copy(),
)

private fun PrivacySettings.deepCopy() = copy(
    gppSid = gppSid.toList(),
    advertisingId = advertisingId?.takeUnless { limitAdTracking == true },
)

private fun AdRequest.deepCopyAndValidate(): AdRequest = copy(
    content = content?.copy(keywords = content.keywords.toList()),
    video = video?.copy(mimes = video.mimes.toList(), protocols = video.protocols.toList()),
    native = native?.copy(assets = native.assets.map { it.copy() }, eventTypes = native.eventTypes.toList()),
    ext = ext.mapValues { (_, value) -> freezeJsonValue(value, 1) },
)

private fun freezeJsonValue(value: Any?, depth: Int): Any? {
    require(depth <= ResourceLimits.MAX_JSON_DEPTH) { "ext nesting exceeds ${ResourceLimits.MAX_JSON_DEPTH}" }
    return when (value) {
    null, is String, is Boolean -> value
    is Number -> value.also { require(it.toDouble().isFinite()) { "ext numbers must be finite" } }
    is Map<*, *> -> value.entries.associate { (key, nested) ->
        require(key is String) { "ext object keys must be strings" }; key to freezeJsonValue(nested, depth + 1)
    }
    is Iterable<*> -> value.map { freezeJsonValue(it, depth + 1) }
    is Array<*> -> value.map { freezeJsonValue(it, depth + 1) }
    else -> throw IllegalArgumentException("ext values must be JSON-compatible")
    }
}

typealias PrivacyContext = PrivacySettings
