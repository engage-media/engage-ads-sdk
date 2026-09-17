package com.engage.ads.render.mraid

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import com.engage.ads.AdFormat
import com.engage.ads.AdRequest
import com.engage.ads.CreativeKind
import com.engage.ads.DiagnosticListener
import com.engage.ads.LoadedCreative
import com.engage.ads.render.CreativeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HtmlRendererTest {
    @Test fun htmlInterstitialAlwaysProvidesReachableNativeClose() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val callback = RecordingCallback()
        val renderer = HtmlRenderer(activity, 15_000, DiagnosticListener.NONE)

        renderer.display(
            LoadedCreative(
                markup = "<div>creative</div>",
                kind = CreativeKind.HTML,
                requestId = "request",
                bidId = "bid",
                impId = "imp",
                burl = null,
                expiresAt = null,
            ),
            AdRequest("interstitial", AdFormat.INTERSTITIAL),
            container,
            callback,
        )

        val views = collectViews(container)
        assertEquals(1, views.filterIsInstance<WebView>().size)
        val close = views.firstOrNull { it.contentDescription == "Close ad" }
        assertNotNull(close)
        assertTrue(close!!.performClick())
        assertEquals(1, callback.dismissedCount)

        renderer.destroy()
        assertEquals(0, container.childCount)
    }

    private fun collectViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(collectViews(root.getChildAt(index)))
    }

    private class RecordingCallback : CreativeRenderer.Callback {
        var dismissedCount = 0
        override fun displayed() = Unit
        override fun clicked(url: String?) = Unit
        override fun adCompleted() = Unit
        override fun breakCompleted() = Unit
        override fun dismissed() { dismissedCount += 1 }
        override fun rewardEarned() = Unit
        override fun failed(error: Throwable) = Unit
    }
}
