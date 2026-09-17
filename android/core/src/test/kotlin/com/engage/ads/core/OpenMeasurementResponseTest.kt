package com.engage.ads.core

import com.engage.ads.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenMeasurementResponseTest {
    private val nativeRequest = AdRequest("native", AdFormat.NATIVE, native = NativeRequest())

    @Test fun nativeVerificationMetadataIsBoundedValidatedAndSeparatedFromTrackers() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val parser = OpenRtbResponseParser(DiagnosticListener(diagnostics::add))
        val eventTrackers = JSONArray()
            .put(JSONObject().put("event", 1).put("method", 1).put("url", "https://track.example/impression"))
            .put(JSONObject().put("event", 555).put("method", 2).put("url", "https://verify.example/omid.js")
                .put("ext", JSONObject().put("vendorKey", "vendor").put("verification_parameters", "opaque-data")))
            .put(JSONObject().put("event", 555).put("method", 2).put("url", "http://remote.example/not-allowed.js"))
            .put(JSONObject().put("event", 555).put("method", 2).put("url", "http://127.0.0.1/fixture.js"))
        val creative = parseNative(parser, nativeAdm(eventTrackers)).native!!

        assertEquals(listOf("https://track.example/impression"), creative.eventTrackers[1])
        assertEquals(2, creative.verificationResources.size)
        assertEquals("vendor", creative.verificationResources.first().vendorKey)
        assertEquals("opaque-data", creative.verificationResources.first().parameters)
        assertEquals("http://127.0.0.1/fixture.js", creative.verificationResources.last().url)
        assertTrue(diagnostics.any { it.code == "native_measurement_metadata_invalid" && it.metadata == mapOf("reason" to "invalid_metadata") })
        assertTrue(diagnostics.none { it.metadata.values.any { value -> value.contains("verify.example") || value.contains("opaque-data") } })
    }

    @Test fun excessNativeVerificationResourcesKeepBoundedPrefixWithoutFailingCreative() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val parser = OpenRtbResponseParser(DiagnosticListener(diagnostics::add))
        val trackers = JSONArray()
        repeat(ResourceLimits.MAX_MEASUREMENT_VERIFICATION_RESOURCES + 1) { index ->
            trackers.put(JSONObject().put("event", 555).put("method", 2).put("url", "https://verify.example/$index.js"))
        }

        val creative = parseNative(parser, nativeAdm(trackers)).native!!

        assertEquals(ResourceLimits.MAX_MEASUREMENT_VERIFICATION_RESOURCES, creative.verificationResources.size)
        assertTrue(diagnostics.any { it.code == "native_measurement_metadata_invalid" && it.metadata["reason"] == "resource_limit" })
    }

    @Test fun malformedOptionalEventTrackerContainerIsIgnoredAndDiagnosed() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val parser = OpenRtbResponseParser(DiagnosticListener(diagnostics::add))
        val adm = JSONObject(nativeAdm(JSONArray())).apply {
            getJSONObject("native").put("eventtrackers", JSONObject().put("bad", true))
        }.toString()

        assertTrue(parseNative(parser, adm).native!!.verificationResources.isEmpty())
        assertTrue(diagnostics.any {
            it.code == "native_measurement_metadata_invalid" && it.metadata["reason"] == "invalid_tracker_array"
        })
    }

    @Test fun invalidVerificationEntriesDoNotConsumeValidResourceBudget() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val parser = OpenRtbResponseParser(DiagnosticListener(diagnostics::add))
        val trackers = JSONArray()
        repeat(ResourceLimits.MAX_MEASUREMENT_VERIFICATION_RESOURCES) {
            trackers.put(JSONObject().put("event", 555).put("method", 2).put("url", "http://remote.example/invalid-$it.js"))
        }
        trackers.put(JSONObject().put("event", 555).put("method", 2).put("url", "https://verify.example/valid.js"))

        val resources = parseNative(parser, nativeAdm(trackers)).native!!.verificationResources
        assertEquals(listOf("https://verify.example/valid.js"), resources.map { it.url })
        assertTrue(diagnostics.none { it.metadata["reason"] == "resource_limit" })
    }

    @Test fun bidApiAndApisAreStrictAndRejectedWhenRendererCannotHonorThem() {
        val disabled = OpenRtbResponseParser(DiagnosticListener.NONE)
        assertCode(EngageError.Code.UNSUPPORTED_CREATIVE) {
            disabled.parse(response("<div>html</div>", api = 7), "request", "imp", AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)))
        }
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            disabled.parse(response("<div>html</div>", rawApi = "\"7\""), "request", "imp", AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)))
        }
        assertCode(EngageError.Code.UNSUPPORTED_CREATIVE) {
            disabled.parse(response("<div>html</div>", apis = listOf(6, 99)), "request", "imp", AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)))
        }

        val enabled = OpenRtbResponseParser(DiagnosticListener.NONE, measurement = coordinator(setOf(OpenMeasurementCreativeType.HTML)))
        val accepted = enabled.parse(response("<div>html</div>", api = 7, apis = listOf(6, 7)), "request", "imp", AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)))
        assertTrue(accepted is AuctionResult.Ready)
    }

    @Test fun bidApisAcceptsSixtyFourEntriesButRejectsLargerAndNegativeIds() {
        val parser = OpenRtbResponseParser(DiagnosticListener.NONE)
        val banner = AdRequest("banner", AdFormat.BANNER, AdSize(320, 50))
        assertTrue(parser.parse(response("<div>html</div>", apis = List(64) { 6 }), "request", "imp", banner) is AuctionResult.Ready)
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse(response("<div>html</div>", apis = List(65) { 6 }), "request", "imp", banner)
        }
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse(response("<div>html</div>", api = -1), "request", "imp", banner)
        }
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse(response("<div>html</div>", apis = listOf(6, -1)), "request", "imp", banner)
        }
    }

    @Test fun nativeVideoUsesImaApiSevenButStaticNativeRequiresCustomBackend() {
        val disabled = OpenRtbResponseParser(DiagnosticListener.NONE)
        assertCode(EngageError.Code.UNSUPPORTED_CREATIVE) {
            disabled.parse(response(nativeAdm(JSONArray()), api = 7), "request", "imp", nativeRequest)
        }
        val videoRequest = AdRequest("native-video", AdFormat.NATIVE, native = NativeRequest(supportsVideo = true))
        val withVideo = nativeAdm(JSONArray(), videoVast = "<VAST version=\"4.2\"><Ad><InLine/></Ad></VAST>")
        assertTrue(disabled.parse(response(withVideo, api = 7), "request", "imp", videoRequest) is AuctionResult.Ready)
    }

    @Test fun vastAdVerificationsMarkupIsPreservedExactlyForIma() {
        val markup = """<VAST version="4.2"><Ad><InLine><AdVerifications><Verification vendor="vendor"><JavaScriptResource apiFramework="omid"><![CDATA[https://verify.example/omid.js]]></JavaScriptResource><VerificationParameters><![CDATA[opaque]]></VerificationParameters></Verification></AdVerifications></InLine></Ad></VAST>"""
        val result = OpenRtbResponseParser(DiagnosticListener.NONE).parse(
            response(markup, api = 7), "request", "imp", AdRequest("video", AdFormat.INSTREAM),
        ) as AuctionResult.Ready
        assertEquals(markup, result.creative.markup)
    }

    private fun parseNative(parser: OpenRtbResponseParser, adm: String): LoadedCreative =
        (parser.parse(response(adm), "request", "imp", nativeRequest) as AuctionResult.Ready).creative

    private fun nativeAdm(trackers: JSONArray, videoVast: String? = null): String {
        val assets = JSONArray()
            .put(JSONObject().put("id", 1).put("title", JSONObject().put("text", "Title")))
            .put(JSONObject().put("id", 2).put("img", JSONObject().put("url", "https://cdn.example/image.png")))
        videoVast?.let { assets.put(JSONObject().put("id", 4).put("video", JSONObject().put("vasttag", it))) }
        return JSONObject().put("native", JSONObject()
            .put("assets", assets)
            .put("link", JSONObject().put("url", "https://click.example"))
            .put("eventtrackers", trackers)).toString()
    }

    private fun response(adm: String, api: Int? = null, apis: List<Int>? = null, rawApi: String? = null): String {
        val bid = JSONObject().put("id", "bid").put("impid", "imp").put("price", 1).put("adm", adm)
        api?.let { bid.put("api", it) }
        apis?.let { bid.put("apis", JSONArray(it)) }
        val body = JSONObject().put("id", "request").put("seatbid", JSONArray().put(JSONObject().put("bid", JSONArray().put(bid))))
        if (rawApi != null) return body.toString().replace("\"adm\":", "\"api\":$rawApi,\"adm\":")
        return body.toString()
    }

    private fun coordinator(types: Set<OpenMeasurementCreativeType>) = OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
        override fun capability() = OpenMeasurementCapability(OpenMeasurementPartner("partner", "1"), types)
        override fun prepareHtml(document: String) = document
        override fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession = error("unused")
    }, DiagnosticListener.NONE)

    private fun assertCode(expected: EngageError.Code, block: () -> Unit) {
        assertEquals(expected, assertThrows(EngageError::class.java, block).code)
    }
}
