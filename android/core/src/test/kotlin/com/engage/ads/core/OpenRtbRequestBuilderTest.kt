package com.engage.ads.core

import com.engage.ads.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenRtbRequestBuilderTest {
    private val configuration = EngageConfiguration(Endpoint.OpenRTB26("https://ads.example/bid"), AppMetadata("com.example.app", "Example", "https://store.example/app", "pub"))
    private val builder = OpenRtbRequestBuilder(configuration, "test-agent")

    @Test fun requestUsesRandomIdsAndOneImpression() {
        val first = builder.build(AdRequest("slot", AdFormat.BANNER, AdSize(320, 50)), PrivacySettings())
        val second = builder.build(AdRequest("slot", AdFormat.BANNER, AdSize(320, 50)), PrivacySettings())
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(1, JSONObject(first.body).getJSONArray("imp").length())
    }

    @Test fun bannerAdvertisesOnlyMraidThree() {
        val banner = JSONObject(builder.build(AdRequest("slot", AdFormat.BANNER, AdSize(320, 50)), PrivacySettings()).body)
            .getJSONArray("imp").getJSONObject(0).getJSONObject("banner")
        assertEquals(6, banner.getJSONArray("api").getInt(0))
    }

    @Test fun htmlInterstitialUsesBannerAndInstl() {
        val imp = JSONObject(builder.build(AdRequest("slot", AdFormat.INTERSTITIAL, AdSize(320, 480)), PrivacySettings()).body).getJSONArray("imp").getJSONObject(0)
        assertTrue(imp.has("banner")); assertFalse(imp.has("video")); assertEquals(1, imp.getInt("instl"))
    }

    @Test fun videoUsesOpenRtb26PlacementAndPodFields() {
        val constraints = VideoConstraints(startDelaySeconds = -1, maxPodDurationSeconds = 180, maxAdsInPod = 3)
        val video = JSONObject(builder.build(AdRequest("slot", AdFormat.INSTREAM, AdSize(1920, 1080), video = constraints), PrivacySettings()).body)
            .getJSONArray("imp").getJSONObject(0).getJSONObject("video")
        assertEquals(1, video.getInt("plcmt")); assertEquals(-1, video.getInt("startdelay")); assertEquals(180, video.getInt("poddur")); assertEquals(3, video.getInt("maxseq")); assertEquals(10, video.getJSONArray("protocols").length())
    }

    @Test fun podDurationWithoutMaxAdsOmitsMaxSeq() {
        val video = JSONObject(builder.build(AdRequest("slot", AdFormat.INSTREAM, video = VideoConstraints(maxPodDurationSeconds = 180)), PrivacySettings()).body)
            .getJSONArray("imp").getJSONObject(0).getJSONObject("video")
        assertEquals(180, video.getInt("poddur"))
        assertFalse(video.has("maxseq"))
    }

    @Test fun privacyUsesOpenRtb26FieldsWithoutSynthesizingIdentity() {
        val privacy = PrivacySettings(gdprApplies = true, consentString = "consent", usPrivacy = "1YNN", gpp = "gpp", gppSid = listOf(2, 6), limitAdTracking = true, advertisingId = "must-not-leak")
        val root = JSONObject(builder.build(AdRequest("slot", AdFormat.BANNER, AdSize(320, 50)), privacy).body)
        assertEquals("consent", root.getJSONObject("user").getString("consent"))
        assertEquals("gpp", root.getJSONObject("regs").getString("gpp"))
        assertEquals(1, root.getJSONObject("device").getInt("lmt"))
        assertFalse(root.getJSONObject("device").has("ifa"))
    }

    @Test fun gppSidAloneCreatesRegulationsObject() {
        val root = JSONObject(builder.build(AdRequest("slot", AdFormat.BANNER, AdSize(320, 50)), PrivacySettings(gppSid = listOf(7))).body)
        assertEquals(7, root.getJSONObject("regs").getJSONArray("gpp_sid").getInt(0))
    }

    @Test fun nativeVideoCapabilityAddsOptionalAssetFour() {
        val native = NativeRequest(supportsVideo = true)
        val nativeWire = JSONObject(builder.build(AdRequest("slot", AdFormat.NATIVE, native = native), PrivacySettings()).body)
            .getJSONArray("imp").getJSONObject(0).getJSONObject("native")
        val payload = JSONObject(nativeWire.getString("request"))
        assertEquals(4, payload.getJSONArray("assets").length())
        assertTrue(payload.getJSONArray("assets").getJSONObject(3).has("video"))
        val video = payload.getJSONArray("assets").getJSONObject(3).getJSONObject("video")
        assertEquals(1, video.getInt("linearity")); assertEquals(4, video.getInt("plcmt"))
    }

    @Test fun nativeDefaultVideoIdAndUnsupportedEventsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeRequest(assets = listOf(NativeAssetRequest(4, NativeAssetType.TITLE)), supportsVideo = true)
        }
        assertThrows(IllegalArgumentException::class.java) { NativeRequest(eventTypes = listOf(2)) }
    }

    @Test fun tvRejectsNonInstream() {
        val tv = OpenRtbRequestBuilder(configuration.copy(deviceCategory = DeviceCategory.TV), "agent")
        val error = assertThrows(EngageError::class.java) { tv.build(AdRequest("slot", AdFormat.BANNER, AdSize(1, 1)), PrivacySettings()) }
        assertEquals(EngageError.Code.INVALID_REQUEST, error.code)
    }
}
