package com.engage.ads.render.nativead

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.engage.ads.AdFormat
import com.engage.ads.AdRequest
import com.engage.ads.CreativeKind
import com.engage.ads.DiagnosticListener
import com.engage.ads.LoadedCreative
import com.engage.ads.NativeAsset
import com.engage.ads.NativeCreative
import com.engage.ads.NativeRequest
import com.engage.ads.NativeAssetRequest
import com.engage.ads.NativeAssetType
import com.engage.ads.ResourceLimits
import com.engage.ads.EngageError
import com.engage.ads.network.HttpTransport
import com.engage.ads.render.CreativeRenderer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NativeRendererTest {
    @Test fun oversizedImageIsRejectedBeforeDecode() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", ResourceLimits.MAX_NATIVE_IMAGE_BYTES + 1))
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val container = NativeAdView(activity).apply {
                val image = ImageView(activity)
                addView(image)
                registerAssetView(2, image)
            }
            activity.setContentView(container)
            val callback = FailureCallback()
            val renderer = NativeRenderer(activity, HttpTransport(1_000, DiagnosticListener.NONE), 1_000)
            val native = NativeCreative(
                assets = listOf(NativeAsset(id = 2, imageUrl = server.url("/large.png").toString())),
                clickUrl = null,
                clickTrackers = emptyList(),
                impressionTrackers = emptyList(),
                eventTrackers = emptyMap(),
            )
            renderer.display(
                LoadedCreative("", CreativeKind.NATIVE, "request", "bid", "imp", null, null, native),
                AdRequest("native", AdFormat.NATIVE, native = NativeRequest(assets = listOf(NativeAssetRequest(2, NativeAssetType.IMAGE, required = true)))),
                container,
                callback,
            )

            val deadline = System.currentTimeMillis() + 2_000
            while (callback.failure == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(EngageError.Code.RENDER, (callback.failure as EngageError).code)
            renderer.destroy()
        }
    }

    @Test fun defaultCardClickFiresTrackerAndDestroyDisablesInteraction() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val container = FrameLayout(activity)
            activity.setContentView(container)
            container.measure(exactly(1080), exactly(800))
            container.layout(0, 0, 1080, 800)
            val callback = RecordingCallback()
            val renderer = NativeRenderer(activity, HttpTransport(1_000, DiagnosticListener.NONE), 1_000)
            val native = NativeCreative(
                assets = listOf(NativeAsset(id = 1, title = "Deterministic native fixture")),
                clickUrl = null,
                clickTrackers = listOf(server.url("/track/click/bid-native-1").toString()),
                impressionTrackers = emptyList(),
                eventTrackers = emptyMap(),
            )

            renderer.display(
                LoadedCreative("", CreativeKind.NATIVE, "request", "bid", "imp", null, null, native),
                AdRequest("native", AdFormat.NATIVE, native = NativeRequest()),
                container,
                callback,
            )
            shadowOf(Looper.getMainLooper()).idle()

            val card = collectViews(container).filterIsInstance<NativeAdView>().single()
            assertTrue(card.performClick())
            assertEquals("/track/click/bid-native-1", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertEquals(1, callback.clickCount)
            renderer.destroy()
            assertFalse(card.performClick())
            assertEquals(1, callback.clickCount)
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun collectViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(collectViews(root.getChildAt(index)))
    }

    private class RecordingCallback : CreativeRenderer.Callback {
        var clickCount = 0
        override fun displayed() = Unit
        override fun clicked(url: String?) { clickCount += 1 }
        override fun adCompleted() = Unit
        override fun breakCompleted() = Unit
        override fun dismissed() = Unit
        override fun rewardEarned() = Unit
        override fun failed(error: Throwable) = throw AssertionError(error)
    }

    private class FailureCallback : CreativeRenderer.Callback {
        @Volatile var failure: Throwable? = null
        override fun displayed() = Unit
        override fun clicked(url: String?) = Unit
        override fun adCompleted() = Unit
        override fun breakCompleted() = Unit
        override fun dismissed() = Unit
        override fun rewardEarned() = Unit
        override fun failed(error: Throwable) { failure = error }
    }
}
