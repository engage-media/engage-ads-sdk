package com.engage.ads.core

import android.os.Build
import com.engage.ads.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class WireRequest(val requestId: String, val impId: String, val body: String)

internal class OpenRtbRequestBuilder(
    private val configuration: EngageConfiguration,
    private val userAgent: String,
    private val measurement: OpenMeasurementCoordinator = OpenMeasurementCoordinator.disabled(configuration.diagnostics),
) {
    fun build(request: AdRequest, privacy: PrivacySettings): WireRequest {
        validate(request)
        val requestId = UUID.randomUUID().toString()
        val impId = UUID.randomUUID().toString()
        val imp = JSONObject().put("id", impId).put("tagid", request.placementId).put("secure", 1)
        when (request.format) {
            AdFormat.BANNER -> imp.put("banner", banner(request))
            AdFormat.INTERSTITIAL -> {
                if (request.video != null) imp.put("video", video(request, placement = 3)) else imp.put("banner", banner(request))
                imp.put("instl", 1)
            }
            AdFormat.REWARDED -> imp.put("video", video(request, placement = 3)).put("rwdd", 1).put("instl", 1)
            AdFormat.INSTREAM -> imp.put("video", video(request, placement = 1))
            AdFormat.NATIVE -> imp.put("native", native(request.native!!))
        }
        if (request.ext.isNotEmpty()) imp.put("ext", JSONObject().also { it.putMap(request.ext) })

        val root = JSONObject()
            .put("id", requestId)
            .put("at", 1)
            .put("tmax", configuration.requestTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .put("imp", JSONArray().put(imp))
            .put("app", app(configuration.app, request.content))
            .put("device", device(configuration.deviceCategory, privacy))
        privacy.consentString?.let { root.put("user", JSONObject().put("consent", it)) }
        regulations(privacy)?.let { root.put("regs", it) }
        customMeasurementPartner(request)?.let { partner ->
            root.put("source", JSONObject().put("ext", JSONObject()
                .put("omidpn", partner.name)
                .put("omidpv", partner.version)))
        }
        return WireRequest(requestId, impId, root.toString())
    }

    private fun validate(request: AdRequest) {
        if (configuration.deviceCategory == DeviceCategory.TV && request.format != AdFormat.INSTREAM) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "TV supports instream requests only")
        }
        if ((request.format == AdFormat.BANNER || (request.format == AdFormat.INTERSTITIAL && request.video == null)) && request.size == null) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "display size is required")
        }
        val reserved = setOf("id", "tagid", "secure", "w", "h", "banner", "video", "native", "instl", "rwdd", "ext")
        val collision = request.ext.keys.firstOrNull { it in reserved }
        if (collision != null) throw EngageError(EngageError.Code.INVALID_REQUEST, "request ext cannot overwrite '$collision'")
    }

    private fun banner(request: AdRequest) = JSONObject().apply {
        request.size?.let { put("w", it.width).put("h", it.height) }
        put("mimes", JSONArray(listOf("text/html", "application/xhtml+xml")))
        put("api", JSONArray(buildList {
            add(6)
            if (measurement.supports(OpenMeasurementCreativeType.HTML)) add(7)
        }))
    }

    private fun video(request: AdRequest, placement: Int) = JSONObject().apply {
        val constraints = request.video ?: VideoConstraints()
        put("mimes", JSONArray(constraints.mimes))
        put("protocols", JSONArray(constraints.protocols))
        put("linearity", 1)
        put("plcmt", placement)
        put("api", JSONArray(listOf(7)))
        put("startdelay", constraints.startDelaySeconds)
        request.size?.let { put("w", it.width).put("h", it.height) }
        constraints.minDurationSeconds?.let { put("minduration", it) }
        constraints.maxDurationSeconds?.let { put("maxduration", it) }
        constraints.maxPodDurationSeconds?.let { put("poddur", it) }
        constraints.maxAdsInPod?.let { put("maxseq", it) }
    }

    private fun native(request: NativeRequest): JSONObject {
        val assets = JSONArray()
        request.effectiveAssets().forEach { asset ->
            val json = JSONObject().put("id", asset.id).put("required", if (asset.required) 1 else 0)
            when (asset.type) {
                NativeAssetType.TITLE -> json.put("title", JSONObject().put("len", asset.titleLength ?: 100))
                NativeAssetType.IMAGE -> json.put("img", JSONObject().put("type", asset.imageType ?: 3).apply {
                    asset.width?.let { put("wmin", it) }; asset.height?.let { put("hmin", it) }
                })
                NativeAssetType.VIDEO -> json.put("video", JSONObject()
                    .put("mimes", JSONArray(listOf("video/mp4", "application/x-mpegURL")))
                    .put("protocols", JSONArray(listOf(2, 3, 5, 6, 7, 8, 11, 12, 13, 14)))
                    .put("minduration", 0).put("maxduration", 120)
                    .put("linearity", 1).put("plcmt", 4)
                    .put("api", JSONArray(listOf(7))))
                NativeAssetType.DATA -> json.put("data", JSONObject().put("type", asset.dataType ?: 2).put("len", asset.titleLength ?: 140))
            }
            assets.put(json)
        }
        val eventTrackers = request.eventTypes.mapTo(mutableListOf()) {
            JSONObject().put("event", it).put("methods", JSONArray(listOf(1)))
        }
        val payload = JSONObject().put("ver", "1.2").put("context", 1).put("plcmttype", 1).put("assets", assets)
        if (nativeCustomMeasurementEnabled(request)) {
            eventTrackers += JSONObject().put("event", 555).put("methods", JSONArray(listOf(2)))
        }
        payload.put("eventtrackers", JSONArray(eventTrackers))
        return JSONObject().put("request", payload.toString()).put("ver", "1.2").apply {
            if (nativeCustomMeasurementEnabled(request)) put("api", JSONArray(listOf(7)))
        }
    }

    private fun customMeasurementPartner(request: AdRequest): OpenMeasurementPartner? = when (request.format) {
        AdFormat.BANNER -> measurement.partnerFor(OpenMeasurementCreativeType.HTML)
        AdFormat.INTERSTITIAL -> if (request.video == null) measurement.partnerFor(OpenMeasurementCreativeType.HTML) else null
        AdFormat.NATIVE -> measurement.partnerFor(OpenMeasurementCreativeType.NATIVE)
            ?.takeIf { nativeCustomMeasurementEnabled(request.native!!) }
        AdFormat.INSTREAM, AdFormat.REWARDED -> null
    }

    private fun nativeCustomMeasurementEnabled(request: NativeRequest): Boolean =
        measurement.supports(OpenMeasurementCreativeType.NATIVE) && request.effectiveAssets().none { it.type == NativeAssetType.VIDEO }

    private fun app(metadata: AppMetadata, content: ContentMetadata?) = JSONObject()
        .put("id", metadata.bundle)
        .put("bundle", metadata.bundle)
        .put("name", metadata.name)
        .apply {
            metadata.storeUrl?.let { put("storeurl", it) }
            metadata.publisherId?.let { put("publisher", JSONObject().put("id", it)) }
            content?.let {
                put("content", JSONObject().apply {
                    it.id?.let { value -> put("id", value) }
                    it.title?.let { value -> put("title", value) }
                    it.url?.let { value -> put("url", value) }
                    it.category?.let { value -> put("cat", JSONArray().put(value)) }
                    it.liveStream?.let { value -> put("livestream", if (value) 1 else 0) }
                    if (it.keywords.isNotEmpty()) put("keywords", it.keywords.joinToString(","))
                })
            }
        }

    private fun device(category: DeviceCategory, privacy: PrivacySettings) = JSONObject()
        .put("ua", userAgent)
        .put("os", "Android")
        .put("osv", Build.VERSION.RELEASE)
        .put("model", Build.MODEL)
        .put("make", Build.MANUFACTURER)
        .put("devicetype", if (category == DeviceCategory.TV) 3 else 4)
        .put("js", 1)
        .apply {
            privacy.limitAdTracking?.let { put("lmt", if (it) 1 else 0) }
            privacy.advertisingId?.takeUnless { privacy.limitAdTracking == true }?.let { put("ifa", it) }
        }

    private fun regulations(privacy: PrivacySettings): JSONObject? {
        if (privacy.gdprApplies == null && privacy.usPrivacy == null && privacy.gpp == null && privacy.gppSid.isEmpty() && privacy.childDirected == null) return null
        return JSONObject().apply {
            privacy.gdprApplies?.let { put("gdpr", if (it) 1 else 0) }
            privacy.childDirected?.let { put("coppa", if (it) 1 else 0) }
            privacy.usPrivacy?.let { put("us_privacy", it) }
            privacy.gpp?.let { put("gpp", it) }
            if (privacy.gppSid.isNotEmpty()) put("gpp_sid", JSONArray(privacy.gppSid))
        }
    }
}
