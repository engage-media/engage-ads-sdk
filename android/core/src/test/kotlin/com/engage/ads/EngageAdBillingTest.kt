package com.engage.ads

import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.engage.ads.render.CreativeRenderer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class EngageAdBillingTest {
    @Test fun preloadNeverDispatchesBillingNotice() {
        auctionFixture().use { fixture ->
            val renderer = CapturingRenderer()
            val client = client(fixture, renderer)
            val ad = client.createBannerAd(bannerRequest()) { }

            ad.load()
            awaitState(ad.stateProvider(), AdState.READY)
            Thread.sleep(50)

            assertEquals(0, fixture.burlRequests.get())
            client.destroy()
        }
    }

    @Test fun duplicateDisplayedCallbackBillsAndEmitsDisplayedExactlyOnce() {
        auctionFixture().use { fixture ->
            val renderer = CapturingRenderer()
            val events = CopyOnWriteArrayList<AdEvent>()
            val client = client(fixture, renderer)
            val ad = client.createBannerAd(bannerRequest(), events::add)
            ad.load(); awaitState(ad.stateProvider(), AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitRenderer(renderer)

            renderer.callback!!.displayed()
            renderer.callback!!.displayed()
            awaitCondition { fixture.burlRequests.get() == 1 }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)

            assertEquals(1, fixture.burlRequests.get())
            assertEquals(1, events.count { it == AdEvent.Displayed })
            client.destroy()
        }
    }

    @Test fun staleDisplayedCallbackAfterDestroyCannotBill() {
        auctionFixture().use { fixture ->
            val renderer = CapturingRenderer()
            val client = client(fixture, renderer)
            val ad = client.createBannerAd(bannerRequest()) { }
            ad.load(); awaitState(ad.stateProvider(), AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitRenderer(renderer)

            ad.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            renderer.callback!!.displayed()
            Thread.sleep(75)

            assertEquals(0, fixture.burlRequests.get())
            client.destroy()
        }
    }

    @Test fun failedBillingNotificationIsAttemptedOnceAndDiagnosedOnce() {
        val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
        auctionFixture(burlStatus = 500).use { fixture ->
            val renderer = CapturingRenderer()
            val client = client(fixture, renderer, DiagnosticListener(diagnostics::add))
            val ad = client.createBannerAd(bannerRequest()) { }
            ad.load(); awaitState(ad.stateProvider(), AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitRenderer(renderer)

            renderer.callback!!.displayed()
            renderer.callback!!.displayed()
            awaitCondition { diagnostics.count { it.code == "notification_failed" } == 1 }
            Thread.sleep(75)

            assertEquals(1, fixture.burlRequests.get())
            assertEquals(1, diagnostics.count { it.code == "notification_failed" })
            client.destroy()
        }
    }

    @Test fun rewardedCompletionAndRewardRequireDisplayAndIgnoreDuplicates() {
        val vast = """<VAST version="4.2"><Ad id="one"><InLine><AdSystem>fixture</AdSystem><AdTitle>fixture</AdTitle><Impression>https://track.example/imp</Impression><Creatives/></InLine></Ad></VAST>"""
        auctionFixture(markup = vast).use { fixture ->
            val renderer = CapturingRenderer()
            val events = CopyOnWriteArrayList<AdEvent>()
            val client = client(fixture, renderer)
            val request = AdRequest("reward", AdFormat.REWARDED, AdSize(640, 360), video = VideoConstraints())
            val ad = client.createRewardedAd(request, events::add)
            ad.load(); awaitState(ad.stateProvider(), AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitRenderer(renderer)

            renderer.callback!!.adCompleted()
            renderer.callback!!.rewardEarned()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(events.none { it == AdEvent.AdCompleted || it == AdEvent.RewardEarned })

            renderer.callback!!.displayed()
            renderer.callback!!.rewardEarned()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(events.none { it == AdEvent.RewardEarned })
            renderer.callback!!.adCompleted()
            renderer.callback!!.adCompleted()
            renderer.callback!!.rewardEarned()
            renderer.callback!!.rewardEarned()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, events.count { it == AdEvent.AdCompleted })
            assertEquals(1, events.count { it == AdEvent.RewardEarned })
            client.destroy()
        }
    }

    @Test fun staleRewardAfterRendererFailureIsIgnored() {
        val vast = """<VAST version="4.2"><Ad id="one"><InLine><AdSystem>fixture</AdSystem><AdTitle>fixture</AdTitle><Impression>https://track.example/imp</Impression><Creatives/></InLine></Ad></VAST>"""
        auctionFixture(markup = vast).use { fixture ->
            val renderer = CapturingRenderer()
            val events = CopyOnWriteArrayList<AdEvent>()
            val client = client(fixture, renderer)
            val ad = client.createRewardedAd(
                AdRequest("reward", AdFormat.REWARDED, AdSize(640, 360), video = VideoConstraints()),
                events::add,
            )
            ad.load(); awaitState(ad.stateProvider(), AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitRenderer(renderer)

            renderer.callback!!.displayed()
            renderer.callback!!.adCompleted()
            renderer.callback!!.failed(EngageError(EngageError.Code.RENDER, "fixture failure"))
            shadowOf(Looper.getMainLooper()).idle()
            renderer.callback!!.rewardEarned()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(AdState.FAILED, ad.state)
            assertEquals(0, events.count { it == AdEvent.RewardEarned })
            client.destroy()
        }
    }

    private fun client(fixture: AuctionFixture, renderer: CapturingRenderer, diagnostics: DiagnosticListener = DiagnosticListener.NONE): EngageClient =
        EngageClient(
            RuntimeEnvironment.getApplication(),
            EngageConfiguration(
                Endpoint.OpenRTB26(fixture.server.url("/auction").toString()),
                AppMetadata("com.example.billing", "Billing test"),
                diagnostics = diagnostics,
            ),
        ).also { it.rendererFactoryForTesting = { _, _ -> renderer } }

    private fun bannerRequest() = AdRequest("banner", AdFormat.BANNER, AdSize(320, 50))

    private fun auctionFixture(burlStatus: Int = 200, markup: String = "<div>fixture</div>"): AuctionFixture {
        val server = MockWebServer()
        server.start()
        val burlRequests = AtomicInteger()
        val burl = server.url("/burl").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/burl") {
                    burlRequests.incrementAndGet()
                    return MockResponse().setResponseCode(burlStatus)
                }
                if (request.method != "POST") return MockResponse().setResponseCode(404)
                val bidRequest = JSONObject(request.body.readUtf8())
                val impId = bidRequest.getJSONArray("imp").getJSONObject(0).getString("id")
                val bid = JSONObject()
                    .put("id", "bid")
                    .put("impid", impId)
                    .put("price", 1.0)
                    .put("adm", markup)
                    .put("burl", burl)
                val response = JSONObject()
                    .put("id", bidRequest.getString("id"))
                    .put("seatbid", JSONArray().put(JSONObject().put("bid", JSONArray().put(bid))))
                return MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(response.toString())
            }
        }
        return AuctionFixture(server, burlRequests)
    }

    private fun awaitRenderer(renderer: CapturingRenderer) = awaitCondition { renderer.callback != null }

    private fun awaitState(state: () -> AdState, expected: AdState) = awaitCondition { state() == expected }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition was not met before timeout", condition())
    }

    private fun BannerAd.stateProvider(): () -> AdState = { state }
    private fun RewardedAd.stateProvider(): () -> AdState = { state }

    private class AuctionFixture(val server: MockWebServer, val burlRequests: AtomicInteger) : AutoCloseable {
        override fun close() = server.close()
    }

    private class CapturingRenderer : CreativeRenderer {
        @Volatile var callback: CreativeRenderer.Callback? = null
        override fun display(creative: LoadedCreative, request: AdRequest, container: ViewGroup, callback: CreativeRenderer.Callback) {
            this.callback = callback
        }
        override fun destroy() = Unit
    }
}
