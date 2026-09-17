package com.engage.ads.core

import com.engage.ads.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenMeasurementWireTest {
    private val configuration = EngageConfiguration(
        Endpoint.OpenRTB26("https://ads.example/bid"),
        AppMetadata("com.example", "Example"),
    )

    @Test fun unavailableCustomBackendIsNotAdvertisedWhileImaVideoIs() {
        val builder = OpenRtbRequestBuilder(configuration, "agent")
        val banner = imp(builder, AdRequest("banner", AdFormat.BANNER, AdSize(320, 50))).getJSONObject("banner")
        assertEquals(listOf(6), banner.getJSONArray("api").toIntList())

        val video = imp(builder, AdRequest("video", AdFormat.INSTREAM, video = VideoConstraints())).getJSONObject("video")
        assertEquals(listOf(7), video.getJSONArray("api").toIntList())

        val native = imp(builder, AdRequest("native", AdFormat.NATIVE, native = NativeRequest(supportsVideo = true))).getJSONObject("native")
        val payload = JSONObject(native.getString("request"))
        assertFalse(payload.has("api"))
        assertFalse(payload.getJSONArray("eventtrackers").toObjectList().any { it.getInt("event") == 555 })
        val nativeVideo = payload.getJSONArray("assets").getJSONObject(3).getJSONObject("video")
        assertEquals(listOf(7), nativeVideo.getJSONArray("api").toIntList())
        assertFalse(JSONObject(builder.build(AdRequest("video", AdFormat.INSTREAM), PrivacySettings()).body).has("source"))
    }

    @Test fun readyCustomBackendAdvertisesOnlyItsPathsAndRealIdentity() {
        val measurement = coordinator(setOf(OpenMeasurementCreativeType.HTML, OpenMeasurementCreativeType.NATIVE))
        val builder = OpenRtbRequestBuilder(configuration, "agent", measurement)
        val bannerRoot = JSONObject(builder.build(AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)), PrivacySettings()).body)
        assertEquals(listOf(6, 7), bannerRoot.getJSONArray("imp").getJSONObject(0).getJSONObject("banner").getJSONArray("api").toIntList())
        assertEquals("licensed-partner", bannerRoot.getJSONObject("source").getJSONObject("ext").getString("omidpn"))

        val nativeRoot = JSONObject(builder.build(AdRequest("native", AdFormat.NATIVE, native = NativeRequest()), PrivacySettings()).body)
        val payload = JSONObject(nativeRoot.getJSONArray("imp").getJSONObject(0).getJSONObject("native").getString("request"))
        assertEquals(listOf(7), nativeRoot.getJSONArray("imp").getJSONObject(0).getJSONObject("native").getJSONArray("api").toIntList())
        assertFalse(payload.has("api"))
        val om = payload.getJSONArray("eventtrackers").toObjectList().single { it.getInt("event") == 555 }
        assertEquals(listOf(2), om.getJSONArray("methods").toIntList())
        assertEquals("2.3", nativeRoot.getJSONObject("source").getJSONObject("ext").getString("omidpv"))

        val videoRoot = JSONObject(builder.build(AdRequest("video", AdFormat.REWARDED), PrivacySettings()).body)
        assertFalse(videoRoot.has("source"))
    }

    @Test fun capabilityFailureDoesNotAdvertiseCustomMeasurement() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val unavailable = OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
            override fun capability(): OpenMeasurementCapability? = error("unavailable")
            override fun prepareHtml(document: String) = document
            override fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession = error("unused")
        }, DiagnosticListener(diagnostics::add))
        val root = JSONObject(OpenRtbRequestBuilder(configuration, "agent", unavailable)
            .build(AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)), PrivacySettings()).body)
        assertEquals(listOf(6), root.getJSONArray("imp").getJSONObject(0).getJSONObject("banner").getJSONArray("api").toIntList())
        assertFalse(root.has("source"))
        assertTrue(diagnostics.any { it.code == "measurement_backend_unavailable" })
    }

    @Test fun nativeVideoOnlyRequestDoesNotClaimCustomBackendIdentity() {
        val builder = OpenRtbRequestBuilder(
            configuration,
            "agent",
            coordinator(setOf(OpenMeasurementCreativeType.NATIVE)),
        )
        val request = AdRequest(
            "native-video-only",
            AdFormat.NATIVE,
            native = NativeRequest(
                assets = listOf(NativeAssetRequest(4, NativeAssetType.VIDEO, required = true)),
                supportsVideo = true,
            ),
        )
        val root = JSONObject(builder.build(request, PrivacySettings()).body)
        val payload = JSONObject(root.getJSONArray("imp").getJSONObject(0).getJSONObject("native").getString("request"))
        assertFalse(root.has("source"))
        assertFalse(payload.has("api"))
        assertFalse(payload.getJSONArray("eventtrackers").toObjectList().any { it.getInt("event") == 555 })
        assertEquals(listOf(7), payload.getJSONArray("assets").getJSONObject(0).getJSONObject("video").getJSONArray("api").toIntList())
    }

    @Test fun mixedNativeVideoRequestDoesNotAdvertiseCustomDisplayMeasurement() {
        val builder = OpenRtbRequestBuilder(configuration, "agent", coordinator(setOf(OpenMeasurementCreativeType.NATIVE)))
        val root = JSONObject(builder.build(
            AdRequest("native-mixed", AdFormat.NATIVE, native = NativeRequest(supportsVideo = true)),
            PrivacySettings(),
        ).body)
        val native = root.getJSONArray("imp").getJSONObject(0).getJSONObject("native")
        val payload = JSONObject(native.getString("request"))
        assertFalse(root.has("source"))
        assertFalse(native.has("api"))
        assertFalse(payload.getJSONArray("eventtrackers").toObjectList().any { it.getInt("event") == 555 })
        assertEquals(listOf(7), payload.getJSONArray("assets").getJSONObject(3).getJSONObject("video").getJSONArray("api").toIntList())
    }

    private fun imp(builder: OpenRtbRequestBuilder, request: AdRequest) =
        JSONObject(builder.build(request, PrivacySettings()).body).getJSONArray("imp").getJSONObject(0)

    private fun coordinator(types: Set<OpenMeasurementCreativeType>) = OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
        override fun capability() = OpenMeasurementCapability(OpenMeasurementPartner("licensed-partner", "2.3"), types)
        override fun prepareHtml(document: String) = document
        override fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession = error("not rendered")
    }, DiagnosticListener.NONE)
}

private fun org.json.JSONArray.toIntList() = (0 until length()).map(::getInt)
private fun org.json.JSONArray.toObjectList() = (0 until length()).map(::getJSONObject)
