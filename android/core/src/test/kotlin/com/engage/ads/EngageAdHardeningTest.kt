package com.engage.ads

import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.engage.ads.render.ContentController
import com.engage.ads.render.CreativeRenderer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class EngageAdHardeningTest {
    @Test fun throwingHostEventAndDiagnosticCallbacksDoNotEscapeMainLooper() {
        vastFixture { server ->
            val client = client(server, DiagnosticListener { throw IllegalStateException("host diagnostic failure") })
            val renderer = CapturingRenderer()
            client.rendererFactoryForTesting = { _, _ -> renderer }
            val ad = client.createAd(AdRequest("slot", AdFormat.INSTREAM)) { throw IllegalStateException("host event failure") }

            ad.load(); awaitState(ad, AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitCallback(renderer)
            renderer.callback!!.displayed()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(AdState.DISPLAYING, ad.state)
            client.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun rendererConstructionFailureBecomesTypedAdFailure() {
        vastFixture { server ->
            val events = CopyOnWriteArrayList<AdEvent>()
            val client = client(server)
            client.rendererFactoryForTesting = { _, _ -> throw IllegalStateException("renderer init failure") }
            val ad = client.createAd(AdRequest("slot", AdFormat.INSTREAM), events::add)

            ad.load(); awaitState(ad, AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitState(ad, AdState.FAILED)

            val error = events.filterIsInstance<AdEvent.Error>().single().error
            assertEquals(EngageError.Code.RENDER, error.code)
            client.destroy()
        }
    }

    @Test fun throwingRendererDestroyAndOffMainDestroyAreContained() {
        vastFixture { server ->
            val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
            val renderer = CapturingRenderer(throwOnDestroy = true)
            val client = client(server, DiagnosticListener(diagnostics::add))
            client.rendererFactoryForTesting = { _, _ -> renderer }
            val ad = client.createAd(AdRequest("slot", AdFormat.INSTREAM)) {}
            ad.load(); awaitState(ad, AdState.READY)
            ad.display(FrameLayout(RuntimeEnvironment.getApplication()))
            awaitCallback(renderer)

            Thread(ad::destroy).also { it.start(); it.join() }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(AdState.DESTROYED, ad.state)
            assertEquals(1, diagnostics.count { it.code == "renderer_destroy_failed" })
            client.destroy()
        }
    }

    @Test fun throwingContentResumeCallbackDoesNotBlockTerminalCleanup() {
        vastFixture { server ->
            val diagnostics = CopyOnWriteArrayList<DiagnosticEvent>()
            val renderer = CapturingRenderer()
            val client = client(server, DiagnosticListener(diagnostics::add))
            client.rendererFactoryForTesting = { _, _ -> renderer }
            val ad = client.createAd(AdRequest("slot", AdFormat.INSTREAM)) {}
            ad.load(); awaitState(ad, AdState.READY)
            ad.display(
                FrameLayout(RuntimeEnvironment.getApplication()),
                object : ContentController {
                    override fun pauseContent() = Unit
                    override fun resumeContent() { throw IllegalStateException("host resume failure") }
                },
            )
            awaitCallback(renderer)

            ad.markContentPaused()
            renderer.callback!!.dismissed()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(AdState.FINISHED, ad.state)
            assertTrue(diagnostics.any { it.code == "host_content_callback_failed" && it.metadata["operation"] == "resume" })
            client.destroy()
        }
    }

    private fun client(server: MockWebServer, diagnostics: DiagnosticListener = DiagnosticListener.NONE) = EngageClient(
        RuntimeEnvironment.getApplication(),
        EngageConfiguration(
            Endpoint.VastTag(server.url("/vast").toString()),
            AppMetadata("com.example.hardening", "Hardening test"),
            diagnostics = diagnostics,
        ),
    )

    private fun vastFixture(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("<VAST version=\"4.2\"><Ad><InLine/></Ad></VAST>"))
            block(server)
        }
    }

    private fun awaitState(ad: EngageAd, expected: AdState) = await { ad.state == expected }
    private fun awaitCallback(renderer: CapturingRenderer) = await { renderer.callback != null }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition was not met before timeout", condition())
    }

    private class CapturingRenderer(private val throwOnDestroy: Boolean = false) : CreativeRenderer {
        @Volatile var callback: CreativeRenderer.Callback? = null
        override fun display(creative: LoadedCreative, request: AdRequest, container: ViewGroup, callback: CreativeRenderer.Callback) {
            this.callback = callback
        }
        override fun destroy() {
            if (throwOnDestroy) throw IllegalStateException("renderer destroy failure")
        }
    }
}
