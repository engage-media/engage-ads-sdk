package com.engage.ads

import android.net.Uri
import java.time.Instant

sealed interface Endpoint {
    data class OpenRTB26(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : Endpoint

    data class VastTag(
        val url: String,
        val parameters: Map<String, String> = emptyMap(),
    ) : Endpoint
}

data class AppMetadata(
    val bundle: String,
    val name: String,
    val storeUrl: String? = null,
    val publisherId: String? = null,
) {
    init {
        require(bundle.isNotBlank()) { "app.bundle must not be blank" }
        require(name.isNotBlank()) { "app.name must not be blank" }
    }
}

enum class DeviceCategory { MOBILE, TV }

data class EngageConfiguration(
    val endpoint: Endpoint,
    val app: AppMetadata,
    val deviceCategory: DeviceCategory = DeviceCategory.MOBILE,
    val requestTimeoutMillis: Long = 5_000,
    val creativeTimeoutMillis: Long = 15_000,
    val diagnostics: DiagnosticListener = DiagnosticListener.NONE,
    val measurementBackend: OpenMeasurementBackend? = null,
) {
    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(creativeTimeoutMillis > 0) { "creativeTimeoutMillis must be positive" }
        when (endpoint) {
            is Endpoint.OpenRTB26 -> requireHttpUrl(endpoint.url)
            is Endpoint.VastTag -> requireHttpUrl(endpoint.url)
        }
    }
}

data class PrivacySettings(
    val gdprApplies: Boolean? = null,
    val consentString: String? = null,
    val usPrivacy: String? = null,
    val gpp: String? = null,
    val gppSid: List<Int> = emptyList(),
    val childDirected: Boolean? = null,
    val limitAdTracking: Boolean? = null,
    val advertisingId: String? = null,
) {
    init {
        require(advertisingId == null || advertisingId.isNotBlank())
        require(gppSid.all { it >= 0 })
    }
}

enum class AdFormat { BANNER, INTERSTITIAL, REWARDED, NATIVE, INSTREAM }

data class AdSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

data class ContentMetadata(
    val id: String? = null,
    val title: String? = null,
    val url: String? = null,
    val keywords: List<String> = emptyList(),
    val category: String? = null,
    val liveStream: Boolean? = null,
)

data class VideoConstraints(
    val minDurationSeconds: Int? = 0,
    val maxDurationSeconds: Int? = 120,
    val mimes: List<String> = listOf("video/mp4", "video/webm", "application/x-mpegURL"),
    val protocols: List<Int> = listOf(2, 3, 5, 6, 7, 8, 11, 12, 13, 14),
    val startDelaySeconds: Int = 0,
    val maxPodDurationSeconds: Int? = null,
    val maxAdsInPod: Int? = null,
) {
    init {
        require(minDurationSeconds == null || minDurationSeconds >= 0)
        require(maxDurationSeconds == null || maxDurationSeconds >= 0)
        require(minDurationSeconds == null || maxDurationSeconds == null || minDurationSeconds <= maxDurationSeconds)
        require(mimes.isNotEmpty())
        require(protocols.isNotEmpty())
        require(startDelaySeconds >= -2) { "startDelaySeconds must be -2 (post-roll), -1 (mid-roll), or a nonnegative number of seconds" }
        require(maxPodDurationSeconds == null || maxPodDurationSeconds > 0)
        require(maxAdsInPod == null || maxAdsInPod > 0)
    }
}

enum class NativeAssetType { TITLE, IMAGE, VIDEO, DATA }

data class NativeAssetRequest(
    val id: Int,
    val type: NativeAssetType,
    val required: Boolean = false,
    val dataType: Int? = null,
    val imageType: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val titleLength: Int? = null,
) {
    init { require(id >= 0) }
}

data class NativeRequest(
    val assets: List<NativeAssetRequest> = listOf(
        NativeAssetRequest(1, NativeAssetType.TITLE, required = true, titleLength = 90),
        NativeAssetRequest(2, NativeAssetType.IMAGE, required = true, imageType = 3, width = 1200, height = 627),
        NativeAssetRequest(3, NativeAssetType.DATA, required = false, dataType = 2, titleLength = 140),
    ),
    val supportsVideo: Boolean = false,
    val eventTypes: List<Int> = listOf(1),
) {
    init {
        require(assets.isNotEmpty())
        require(assets.size <= ResourceLimits.MAX_NATIVE_ASSETS) { "native requests support at most ${ResourceLimits.MAX_NATIVE_ASSETS} assets" }
        require(assets.map { it.id }.distinct().size == assets.size) { "native asset ids must be unique" }
        require(supportsVideo || assets.none { it.type == NativeAssetType.VIDEO })
        require(!supportsVideo || assets.any { it.type == NativeAssetType.VIDEO } || assets.none { it.id == 4 }) {
            "native asset id 4 is reserved for the default video asset when supportsVideo is true"
        }
        require(eventTypes.isNotEmpty() && eventTypes.all { it == 1 }) { "only native impression event type 1 is supported" }
        require(eventTypes.distinct().size == eventTypes.size) { "native event types must be unique" }
    }
}

data class AdRequest(
    val placementId: String,
    val format: AdFormat,
    val size: AdSize? = null,
    val content: ContentMetadata? = null,
    val video: VideoConstraints? = null,
    val native: NativeRequest? = null,
    val ext: Map<String, Any?> = emptyMap(),
) {
    init {
        require(placementId.isNotBlank())
        require(format != AdFormat.NATIVE || native != null) { "native request details are required" }
        require(format == AdFormat.NATIVE || native == null) { "native details are only valid for native format" }
    }
}

enum class AdState { IDLE, LOADING, READY, DISPLAYING, FINISHED, FAILED, DESTROYED }

sealed interface AdEvent {
    data object Loaded : AdEvent
    data object Displayed : AdEvent
    data object AdCompleted : AdEvent
    data object BreakCompleted : AdEvent
    data object Dismissed : AdEvent
    data object NoFill : AdEvent
    data object RewardEarned : AdEvent
    data class Clicked(val url: String? = null) : AdEvent
    data class Error(val error: EngageError) : AdEvent
}

class EngageError(
    val code: Code,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Code { INVALID_REQUEST, NETWORK, TIMEOUT, NO_FILL, MALFORMED_RESPONSE, UNSUPPORTED_CREATIVE, EXPIRED, RENDER, INVALID_STATE, DESTROYED }
}

enum class CreativeKind { HTML, VAST, NATIVE }

data class NativeAsset(
    val id: Int,
    val title: String? = null,
    val text: String? = null,
    val imageUrl: String? = null,
    val videoVast: String? = null,
)

data class NativeCreative(
    val assets: List<NativeAsset>,
    val clickUrl: String?,
    val clickTrackers: List<String>,
    val impressionTrackers: List<String>,
    val eventTrackers: Map<Int, List<String>>,
    val verificationResources: List<OpenMeasurementVerificationResource> = emptyList(),
)

@ConsistentCopyVisibility
data class LoadedCreative internal constructor(
    val markup: String,
    val kind: CreativeKind,
    val requestId: String,
    val bidId: String?,
    val impId: String,
    val burl: String?,
    val expiresAt: Instant?,
    val native: NativeCreative? = null,
    internal val winNotice: String? = null,
)

internal fun requireHttpUrl(value: String) {
    val uri = Uri.parse(value)
    require((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) { "HTTP(S) URL required" }
}
