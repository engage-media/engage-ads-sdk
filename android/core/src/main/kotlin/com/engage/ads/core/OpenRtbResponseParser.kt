package com.engage.ads.core

import com.engage.ads.*
import org.json.JSONObject
import java.time.Clock
import java.time.Instant

internal sealed interface AuctionResult {
    data object NoFill : AuctionResult
    data class MarkupRequired(val bid: ParsedBid, val url: String) : AuctionResult
    data class Ready(val creative: LoadedCreative) : AuctionResult
}

internal data class ParsedBid(
    val requestId: String,
    val bidId: String,
    val impId: String,
    val nurl: String?,
    val burl: String?,
    val expiresAt: Instant?,
    val adm: String?,
    val requiredApis: Set<Int> = emptySet(),
)

internal class OpenRtbResponseParser(
    private val diagnostics: DiagnosticListener,
    private val clock: Clock = Clock.systemUTC(),
    private val measurement: OpenMeasurementCoordinator = OpenMeasurementCoordinator.disabled(diagnostics),
) {
    fun parse(body: String, expectedRequestId: String, expectedImpId: String, request: AdRequest): AuctionResult {
        if (body.exceedsUtf8Bytes(ResourceLimits.MAX_OPENRTB_RESPONSE_BYTES)) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "OpenRTB response exceeds the size limit")
        }
        requireJsonDepth(body)
        val root = try { JSONObject(body) } catch (error: Exception) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "response is not valid JSON", error)
        }
        val responseId = root.optionalString("id")
            ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "response id is missing")
        if (responseId != expectedRequestId) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "response id does not match request")
        val seatBid = root.optJSONArray("seatbid") ?: if (root.has("seatbid") && !root.isNull("seatbid")) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "seatbid is not an array")
        } else return AuctionResult.NoFill
        if (seatBid.length() == 0) return AuctionResult.NoFill

        var totalBids = 0
        val matching = buildList {
            for (seatIndex in 0 until seatBid.length()) {
                val seat = seatBid.optJSONObject(seatIndex)
                    ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "seatbid[$seatIndex] is not an object")
                val bids = seat.optJSONArray("bid")
                    ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "seatbid[$seatIndex].bid is missing")
                for (bidIndex in 0 until bids.length()) {
                    val bid = bids.optJSONObject(bidIndex)
                        ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid is not an object")
                    totalBids++
                    if (bid.optString("impid") == expectedImpId) add(bid)
                }
            }
        }
        if (totalBids == 0) return AuctionResult.NoFill
        if (matching.isEmpty()) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "no bid matched the request impression")
        if (matching.size != totalBids) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "response contains a bid for an unknown impression")
        if (matching.size != 1) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "ambiguous response: ${matching.size} bids matched the impression")

        val bid = matching.single()
        val bidId = bid.optionalString("id")
            ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid id is missing")
        if (!bid.has("price") || bid.isNull("price")) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid price is missing")
        val rawPrice = bid.opt("price")
        if (rawPrice !is Number) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid price is not numeric")
        val price = rawPrice.toDouble()
            ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid price is invalid")
        if (!price.isFinite() || price < 0) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid price must be finite and nonnegative")

        val nurl = checkedNotice(bid.optionalString("nurl"), "nurl")
        val burl = checkedNotice(bid.optionalString("burl"), "burl")
        val adm = bid.optionalString("adm")
        if (adm == null && nurl == null) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid has neither adm nor nurl")
        val expiresAt = if (bid.has("exp")) {
            val rawExpiry = bid.opt("exp")
            if (rawExpiry !is Byte && rawExpiry !is Short && rawExpiry !is Int && rawExpiry !is Long) {
                throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid expiry is not an integer")
            }
            val seconds = (rawExpiry as Number).toLong()
                ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid expiry is invalid")
            if (seconds <= 0) throw EngageError(EngageError.Code.EXPIRED, "bid is already expired")
            clock.instant().plusSeconds(seconds)
        } else null

        val parsed = ParsedBid(responseId, bidId, expectedImpId, nurl, burl, expiresAt, adm, parseRequiredApis(bid))
        return if (adm != null) AuctionResult.Ready(normalize(parsed, adm, request, winNotice = nurl))
        else AuctionResult.MarkupRequired(parsed, nurl!!)
    }

    fun normalizeFetchedMarkup(parsed: ParsedBid, markup: String, request: AdRequest): LoadedCreative =
        normalize(parsed, markup, request, winNotice = null)

    private fun normalize(parsed: ParsedBid, markup: String, request: AdRequest, winNotice: String?): LoadedCreative {
        if (markup.isBlank()) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "creative markup is empty")
        val kind = when (request.format) {
            AdFormat.NATIVE -> CreativeKind.NATIVE
            AdFormat.INSTREAM, AdFormat.REWARDED -> CreativeKind.VAST
            AdFormat.INTERSTITIAL -> if (request.video != null) CreativeKind.VAST else {
                if (looksLikeVast(markup)) throw EngageError(EngageError.Code.UNSUPPORTED_CREATIVE, "HTML interstitial request received VAST")
                CreativeKind.HTML
            }
            AdFormat.BANNER -> {
                if (looksLikeVast(markup)) throw EngageError(EngageError.Code.UNSUPPORTED_CREATIVE, "banner request received VAST")
                CreativeKind.HTML
            }
        }
        if (kind == CreativeKind.VAST && !looksLikeVast(markup)) {
            throw EngageError(EngageError.Code.UNSUPPORTED_CREATIVE, "video response does not contain VAST")
        }
        if (kind == CreativeKind.VAST) validateVastMarkup(markup)
        val native = if (kind == CreativeKind.NATIVE) parseNative(markup, request.native!!) else null
        validateRequiredApis(parsed.requiredApis, kind, native)
        return LoadedCreative(markup, kind, parsed.requestId, parsed.bidId, parsed.impId, parsed.burl, parsed.expiresAt, native, winNotice)
    }

    private fun parseNative(markup: String, request: NativeRequest): NativeCreative {
        if (markup.exceedsUtf8Bytes(ResourceLimits.MAX_OPENRTB_RESPONSE_BYTES)) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native adm exceeds the size limit")
        }
        requireJsonDepth(markup)
        val outer = try { JSONObject(markup) } catch (error: Exception) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native adm is not valid JSON", error)
        }
        val root = outer.optJSONObject("native") ?: outer
        val assetsJson = root.optJSONArray("assets")
            ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native assets are missing")
        if (assetsJson.length() > ResourceLimits.MAX_NATIVE_ASSETS) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native response contains too many assets")
        }
        val assets = buildList<NativeAsset> {
            for (index in 0 until assetsJson.length()) {
                val item = assetsJson.optJSONObject(index)
                    ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native asset is not an object")
                val rawId = item.opt("id")
                if (rawId !is Byte && rawId !is Short && rawId !is Int && rawId !is Long) {
                    throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native asset id is not an integer")
                }
                val id = (rawId as Number).toLong()
                if (id !in 0..Int.MAX_VALUE) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native asset id is invalid")
                val assetId = id.toInt()
                if (any { it.id == assetId }) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native asset id is duplicated")
                val requested = request.effectiveAssets().firstOrNull { it.id == assetId }
                    ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native response contains an unrequested asset")
                val title = item.optJSONObject("title")?.optionalString("text")
                val data = item.optJSONObject("data")?.optionalString("value")
                val image = item.optJSONObject("img")?.optionalString("url")?.also { checkedHttpUrl(it, "native image URL") }
                val video = item.optJSONObject("video")?.optionalString("vasttag")
                if (listOf(title, data, image, video).count { it != null } != 1) {
                    throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native asset has unsupported or ambiguous content")
                }
                val actualType = when {
                    title != null -> NativeAssetType.TITLE
                    image != null -> NativeAssetType.IMAGE
                    video != null -> NativeAssetType.VIDEO
                    else -> NativeAssetType.DATA
                }
                if (video != null) validateVastMarkup(video)
                if (actualType != requested.type) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native asset type does not match request")
                add(NativeAsset(assetId, title, data, image, video))
            }
        }
        val link = root.optJSONObject("link")
        val clickUrl = link?.optionalString("url")?.also(::checkedClickUrl)
            ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native click link is missing")
        val clickTrackerJson = link?.optJSONArray("clicktrackers")
        val impressionTrackerJson = root.optJSONArray("imptrackers")
        if ((clickTrackerJson?.length() ?: 0) > ResourceLimits.MAX_NATIVE_TRACKERS || (impressionTrackerJson?.length() ?: 0) > ResourceLimits.MAX_NATIVE_TRACKERS) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native response contains too many trackers")
        }
        val clickTrackers = clickTrackerJson?.stringList().orEmpty().onEach { checkedHttpUrl(it, "native click tracker") }
        val impressionTrackers = impressionTrackerJson?.stringList().orEmpty().onEach { checkedHttpUrl(it, "native impression tracker") }
        val eventTrackers = mutableMapOf<Int, MutableList<String>>()
        val verificationResources = mutableListOf<OpenMeasurementVerificationResource>()
        val nativeEventTrackers = root.optJSONArray("eventtrackers")
        if (root.has("eventtrackers") && !root.isNull("eventtrackers") && nativeEventTrackers == null) {
            diagnostics.emitSafely(DiagnosticEvent(
                DiagnosticEvent.Level.WARNING,
                "native_measurement_metadata_invalid",
                "Ignored invalid native Open Measurement metadata",
                mapOf("reason" to "invalid_tracker_array"),
            ))
        }
        nativeEventTrackers?.let { trackers ->
            if (trackers.length() > ResourceLimits.MAX_NATIVE_TRACKERS) {
                throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native response contains too many event trackers")
            }
            var verificationLimitDiagnosed = false
            for (index in 0 until trackers.length()) {
                val tracker = trackers.optJSONObject(index)
                if (tracker == null) {
                    diagnostics.emitSafely(DiagnosticEvent(DiagnosticEvent.Level.INFO, "native_tracker_unsupported", "Ignored unsupported native event tracker"))
                    continue
                }
                val event = tracker.strictInt("event")
                val method = tracker.strictInt("method")
                val url = tracker.optionalString("url")
                if (event == 1 && method == 1 && url != null) {
                    checkedHttpUrl(url, "native event tracker")
                    eventTrackers.getOrPut(event) { mutableListOf() }.add(url)
                }
                else if (event == NATIVE_OM_EVENT && method == NATIVE_OM_METHOD) {
                    parseVerificationResource(tracker)?.let { resource ->
                        if (verificationResources.size < ResourceLimits.MAX_MEASUREMENT_VERIFICATION_RESOURCES) {
                            verificationResources += resource
                        } else if (!verificationLimitDiagnosed) {
                            verificationLimitDiagnosed = true
                            diagnoseInvalidVerification("resource_limit")
                        }
                    }
                }
                else diagnostics.emitSafely(DiagnosticEvent(DiagnosticEvent.Level.INFO, "native_tracker_unsupported", "Ignored unsupported native event tracker"))
            }
        }
        request.effectiveAssets().filter { it.required }.forEach { required ->
            if (assets.none { it.id == required.id }) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "required native asset ${required.id} is missing")
        }
        return NativeCreative(assets, clickUrl, clickTrackers, impressionTrackers, eventTrackers, verificationResources.toList())
    }

    private fun parseRequiredApis(bid: JSONObject): Set<Int> = buildSet {
        if (bid.has("api") && !bid.isNull("api")) {
            val api = bid.strictInt("api")
                ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid api is not an integer")
            if (api < 0) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid api is negative")
            add(api)
        }
        if (bid.has("apis") && !bid.isNull("apis")) {
            val values = bid.optJSONArray("apis")
                ?: throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid apis is not an array")
            if (values.length() > 64) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid apis contains too many values")
            for (index in 0 until values.length()) {
                val raw = values.opt(index)
                if (raw !is Byte && raw !is Short && raw !is Int && raw !is Long) {
                    throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid apis contains a non-integer value")
                }
                val value = (raw as Number).toLong()
                if (value !in 0..Int.MAX_VALUE) {
                    throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "bid apis contains an invalid value")
                }
                add(value.toInt())
            }
        }
    }

    private fun validateRequiredApis(required: Set<Int>, kind: CreativeKind, native: NativeCreative?) {
        if (required.isEmpty()) return
        val supported = when (kind) {
            CreativeKind.HTML -> buildSet {
                add(6)
                if (measurement.supports(OpenMeasurementCreativeType.HTML)) add(7)
            }
            CreativeKind.VAST -> setOf(7)
            CreativeKind.NATIVE -> if (native?.assets?.any { it.videoVast != null } == true || measurement.supports(OpenMeasurementCreativeType.NATIVE)) setOf(7) else emptySet()
        }
        val unsupported = required - supported
        if (unsupported.isNotEmpty()) {
            throw EngageError(EngageError.Code.UNSUPPORTED_CREATIVE, "bid requires an unsupported API framework")
        }
    }

    private fun parseVerificationResource(tracker: JSONObject): OpenMeasurementVerificationResource? {
        val url = tracker.optionalString("url")
        val ext = tracker.optJSONObject("ext")
        val vendorKey = ext?.optionalString("vendorKey")
        val parameters = ext?.optionalString("verification_parameters")
        val vendorFieldValid = ext == null || !ext.has("vendorKey") || ext.isNull("vendorKey") ||
            (ext.opt("vendorKey") is String && (ext.opt("vendorKey") as String).isNotBlank())
        val parametersFieldValid = ext == null || !ext.has("verification_parameters") || ext.isNull("verification_parameters") ||
            (ext.opt("verification_parameters") is String && (ext.opt("verification_parameters") as String).isNotBlank())
        val valid = url != null && !url.exceedsUtf8Bytes(ResourceLimits.MAX_MEASUREMENT_URL_BYTES) && isVerificationUrl(url) &&
            (vendorKey == null || !vendorKey.exceedsUtf8Bytes(ResourceLimits.MAX_MEASUREMENT_VENDOR_KEY_BYTES)) &&
            (parameters == null || !parameters.exceedsUtf8Bytes(ResourceLimits.MAX_MEASUREMENT_PARAMETERS_BYTES)) &&
            (!tracker.has("ext") || tracker.isNull("ext") || ext != null) &&
            vendorFieldValid && parametersFieldValid
        if (!valid) {
            diagnoseInvalidVerification("invalid_metadata")
            return null
        }
        return OpenMeasurementVerificationResource(url, vendorKey, parameters)
    }

    private fun isVerificationUrl(value: String): Boolean = try {
        val uri = android.net.Uri.parse(value)
        when (uri.scheme?.lowercase()) {
            "https" -> !uri.host.isNullOrBlank()
            "http" -> uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            else -> false
        }
    } catch (_: Exception) { false }

    private fun diagnoseInvalidVerification(reason: String) {
        diagnostics.emitSafely(DiagnosticEvent(
            DiagnosticEvent.Level.WARNING,
            "native_measurement_metadata_invalid",
            "Ignored invalid native Open Measurement metadata",
            mapOf("reason" to reason),
        ))
    }

    private fun checkedNotice(url: String?, name: String): String? {
        if (url == null) return null
        if (UNRESOLVED_MACRO.containsMatchIn(url)) {
            diagnostics.emitSafely(DiagnosticEvent(DiagnosticEvent.Level.ERROR, "unresolved_notice_macro", "Rejected $name with unresolved macro", mapOf("url" to Redactor.url(url))))
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "$name contains an unresolved macro")
        }
        try { requireHttpUrl(url) } catch (error: IllegalArgumentException) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "$name is not an HTTP(S) URL", error)
        }
        return url
    }

    private fun checkedHttpUrl(url: String, name: String) {
        try { requireHttpUrl(url) } catch (error: IllegalArgumentException) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "$name is not an HTTP(S) URL", error)
        }
    }

    private fun checkedClickUrl(url: String) {
        val uri = android.net.Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme.isNullOrBlank() || scheme in setOf("javascript", "data", "file", "about") ||
            (scheme in setOf("http", "https") && uri.host.isNullOrBlank())) {
            throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "native click URL uses an unsafe or invalid scheme")
        }
    }

    private fun looksLikeVast(markup: String): Boolean = Regex("<\\s*VAST(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(markup)

    private companion object {
        val UNRESOLVED_MACRO = Regex("\\$\\{[^}]+\\}")
        const val NATIVE_OM_EVENT = 555
        const val NATIVE_OM_METHOD = 2
    }

    private fun requireJsonDepth(json: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in json) {
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
                '{', '[' -> {
                    depth += 1
                    if (depth > ResourceLimits.MAX_JSON_DEPTH) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "OpenRTB response nesting exceeds the limit")
                }
                '}', ']' -> depth -= 1
            }
        }
    }
}

private fun JSONObject.strictInt(name: String): Int? {
    val raw = opt(name)
    if (raw !is Byte && raw !is Short && raw !is Int && raw !is Long) return null
    val value = (raw as Number).toLong()
    return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

internal fun NativeRequest.effectiveAssets(): List<NativeAssetRequest> =
    if (supportsVideo && assets.none { it.type == NativeAssetType.VIDEO }) assets + NativeAssetRequest(4, NativeAssetType.VIDEO)
    else assets
