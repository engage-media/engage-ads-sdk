package com.engage.ads

import android.os.Looper
import com.engage.ads.render.ContentController
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class EngageClientLifecycleTest {
    @Test fun createCannotRegisterAnAdAfterConcurrentClientDestroy() {
        MockWebServer().use { server ->
            val client = client(server.url("/vast").toString())
            val enteredCopy = CountDownLatch(1)
            val continueCopy = CountDownLatch(1)
            val blockingValue = Iterable<Any?> {
                object : Iterator<Any?> {
                    override fun hasNext(): Boolean {
                        enteredCopy.countDown()
                        check(continueCopy.await(2, TimeUnit.SECONDS))
                        return false
                    }
                    override fun next(): Any? = error("empty iterator")
                }
            }
            val created = AtomicReference<EngageAd?>()
            val failure = AtomicReference<Throwable?>()
            val creator = Thread {
                try {
                    created.set(client.createAd(AdRequest("slot", AdFormat.INSTREAM, ext = mapOf("gate" to blockingValue))) {})
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

            creator.start()
            assertTrue(enteredCopy.await(2, TimeUnit.SECONDS))
            client.destroy()
            continueCopy.countDown()
            creator.join(2_000)

            assertFalse(creator.isAlive)
            assertNull(created.get())
            assertTrue(failure.get() is IllegalStateException)
        }
    }

    @Test fun privacyIsSnapshottedWhenLoadBegins() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("<VAST version=\"4.2\"><Ad><InLine/></Ad></VAST>"))
            val client = client(server.url("/vast").toString() + "?ifa=[IFA]")
            val events = CopyOnWriteArrayList<AdEvent>()
            val ad = client.createAd(AdRequest("slot", AdFormat.INSTREAM), events::add)
            client.updatePrivacy(PrivacySettings(limitAdTracking = true, advertisingId = "withdrawn-id"))
            ad.load()
            awaitState(ad, AdState.READY)
            awaitEvents(events, 1)
            assertNull(server.takeRequest().requestUrl?.queryParameter("ifa"))
            assertEquals(listOf(AdEvent.Loaded), events)
            client.destroy()
        }
    }

    @Test fun directVast204IsNoFill() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val events = CopyOnWriteArrayList<AdEvent>()
            val ad = client(server.url("/vast").toString()).createAd(AdRequest("slot", AdFormat.INSTREAM), events::add)
            ad.load(); awaitState(ad, AdState.FINISHED)
            awaitEvents(events, 1)
            assertEquals(listOf(AdEvent.NoFill), events)
        }
    }

    @Test fun noFillAndCancellationEmitSafeStructuredDiagnostics() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
            val configuration = EngageConfiguration(
                Endpoint.VastTag(server.url("/vast?secret=value").toString()),
                AppMetadata("com.example", "Example"),
                diagnostics = DiagnosticListener(diagnostics::add),
            )
            val client = EngageClient(RuntimeEnvironment.getApplication(), configuration)
            val ad = client.createInStreamAd(AdRequest("private-placement", AdFormat.INSTREAM)) { }
            ad.load()
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && ad.state != AdState.FINISHED) Thread.sleep(5)
            assertEquals("ad_load_no_fill", diagnostics.single { it.code == "ad_load_no_fill" }.code)
            assertTrue(diagnostics.all { event -> event.metadata.values.none { it.contains("private-placement") || it.contains("secret") } })
            client.destroy()
        }
    }

    @Test fun formatFacadeRejectsMismatchedRequest() {
        MockWebServer().use { server ->
            val client = client(server.url("/vast").toString())
            val error = assertThrows(EngageError::class.java) {
                client.createBannerAd(AdRequest("slot", AdFormat.INSTREAM)) { }
            }
            assertEquals(EngageError.Code.INVALID_REQUEST, error.code)
            client.destroy()
        }
    }

    @Test fun destroyCancelsInFlightLoadAndCallbacks() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val events = CopyOnWriteArrayList<AdEvent>()
            val client = client(server.url("/vast").toString())
            val ad = client.createAd(AdRequest("slot", AdFormat.INSTREAM), events::add)
            ad.load()
            while (server.requestCount == 0) Thread.sleep(5)
            ad.destroy()
            Thread.sleep(30); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AdState.DESTROYED, ad.state); assertTrue(events.isEmpty())
            client.destroy()
        }
    }

    private fun client(url: String) = EngageClient(
        RuntimeEnvironment.getApplication(),
        EngageConfiguration(Endpoint.VastTag(url), AppMetadata("com.example", "Example"), requestTimeoutMillis = 500),
    )

    private fun awaitState(ad: EngageAd, expected: AdState) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && ad.state != expected) {
            Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, ad.state)
    }

    private fun awaitEvents(events: List<AdEvent>, count: Int) {
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline && events.size < count) {
            Thread.sleep(2); shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
