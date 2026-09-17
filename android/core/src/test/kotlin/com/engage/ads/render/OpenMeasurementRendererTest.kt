package com.engage.ads.render

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import com.engage.ads.*
import com.engage.ads.network.HttpTransport
import com.engage.ads.render.mraid.HtmlRenderer
import com.engage.ads.render.nativead.NativeAdView
import com.engage.ads.render.nativead.NativeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OpenMeasurementRendererTest {
    @Test fun htmlInjectsBeforeLoadThenStartsOnPageFinishedAndCleansUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        layout(container)
        val provider = RecordingBackend(setOf(OpenMeasurementCreativeType.HTML))
        val renderer = HtmlRenderer(activity, 15_000, DiagnosticListener.NONE, coordinator(provider))

        renderer.display(htmlCreative(), AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)), container, RecordingCallback())

        assertEquals(listOf("prepare"), provider.calls)
        val webView = collectViews(container).filterIsInstance<WebView>().single()
        layout(webView)
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://engage.invalid/")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(provider.calls.indexOf("prepare") < provider.calls.indexOf("create"))
        assertTrue(provider.calls.indexOf("create") < provider.calls.indexOf("loaded"))
        assertSame(webView, provider.context!!.adView)
        assertSame(webView, provider.context!!.webView)
        renderer.destroy()
        assertTrue(provider.calls.containsAll(listOf("unregister", "finish")))
    }

    @Test fun reentrantDestroyDuringHtmlSessionCreationFinishesReturnedSession() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        layout(container)
        lateinit var renderer: HtmlRenderer
        val provider = RecordingBackend(setOf(OpenMeasurementCreativeType.HTML), onCreate = { renderer.destroy() })
        renderer = HtmlRenderer(activity, 15_000, DiagnosticListener.NONE, coordinator(provider))
        renderer.display(htmlCreative(), AdRequest("banner", AdFormat.BANNER, AdSize(320, 50)), container, RecordingCallback())
        val webView = collectViews(container).filterIsInstance<WebView>().single()

        shadowOf(webView).webViewClient.onPageFinished(webView, "https://engage.invalid/")

        assertTrue(provider.calls.containsAll(listOf("create", "unregister", "finish")))
        assertTrue("loaded must not be sent after reentrant destruction", "loaded" !in provider.calls)
    }

    @Test fun staticNativePublishesMeasurementAtBindAndVisibilityAndCleansUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val provider = RecordingBackend(setOf(OpenMeasurementCreativeType.NATIVE))
        val nativeView = NativeAdView(activity).apply {
            val title = TextView(activity)
            addView(title)
            registerAssetView(1, title)
        }
        activity.setContentView(nativeView)
        layout(nativeView)
        val renderer = NativeRenderer(activity, HttpTransport(1_000, DiagnosticListener.NONE), 1_000, coordinator(provider))
        val creative = NativeCreative(
            assets = listOf(NativeAsset(1, title = "Title")),
            clickUrl = "https://click.example",
            clickTrackers = emptyList(),
            impressionTrackers = emptyList(),
            eventTrackers = emptyMap(),
            verificationResources = listOf(OpenMeasurementVerificationResource("https://verify.example/omid.js", "vendor", "parameters")),
        )
        renderer.display(
            LoadedCreative("", CreativeKind.NATIVE, "request", "bid", "imp", null, null, creative),
            AdRequest("native", AdFormat.NATIVE, native = NativeRequest(assets = listOf(NativeAssetRequest(1, NativeAssetType.TITLE, required = true)))),
            nativeView,
            RecordingCallback(),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(provider.calls.indexOf("create") < provider.calls.indexOf("loaded"))
        assertEquals(creative.verificationResources, provider.context!!.verificationResources)
        renderer.destroy()
        assertTrue(provider.calls.containsAll(listOf("unregister", "finish")))
    }

    private fun coordinator(provider: RecordingBackend) = OpenMeasurementCoordinator.snapshot(provider, DiagnosticListener.NONE)
    private fun htmlCreative() = LoadedCreative("<div>creative</div>", CreativeKind.HTML, "request", "bid", "imp", null, null)

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 640, 360)
    }

    private fun collectViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(collectViews(root.getChildAt(index)))
    }

    private class RecordingBackend(
        private val types: Set<OpenMeasurementCreativeType>,
        private val onCreate: () -> Unit = {},
    ) : OpenMeasurementBackend, OpenMeasurementSession {
        val calls = mutableListOf<String>()
        var context: OpenMeasurementSessionContext? = null
        override fun capability() = OpenMeasurementCapability(OpenMeasurementPartner("partner", "1"), types)
        override fun prepareHtml(document: String): String { calls += "prepare"; return document.replace("<head>", "<head><script id=\"omid-service\"></script>") }
        override fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession {
            calls += "create"; this.context = context; onCreate(); return this
        }
        override fun registerFriendlyObstruction(obstruction: FriendlyObstruction) { calls += "register" }
        override fun unregisterFriendlyObstruction(view: View) { calls += "unregister_one" }
        override fun unregisterAllFriendlyObstructions() { calls += "unregister" }
        override fun loaded() { calls += "loaded" }
        override fun impression() { calls += "impression" }
        override fun finish() { calls += "finish" }
    }

    private class RecordingCallback : CreativeRenderer.Callback {
        override fun displayed() = Unit
        override fun clicked(url: String?) = Unit
        override fun adCompleted() = Unit
        override fun breakCompleted() = Unit
        override fun dismissed() = Unit
        override fun rewardEarned() = Unit
        override fun failed(error: Throwable) = throw AssertionError(error)
    }
}
