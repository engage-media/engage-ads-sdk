package com.engage.ads.render.mraid

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.engage.ads.DiagnosticListener
import com.engage.ads.DiagnosticEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class MraidBridgeTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val parent = FrameLayout(activity)
    private val webView = WebView(activity)
    private val clicks = mutableListOf<String>()
    private var closeCount = 0
    private val bridge = MraidBridge(activity, webView, false, DiagnosticListener.NONE, onClose = { closeCount += 1 }, onClick = clicks::add)

    init {
        activity.setContentView(parent)
        parent.addView(View(activity), FrameLayout.LayoutParams(8, 8))
        parent.addView(webView, FrameLayout.LayoutParams(320, 50))
        parent.measure(exactly(1080), exactly(1920))
        parent.layout(0, 0, 1080, 1920)
        webView.layout(0, 100, 320, 150)
    }

    @After fun cleanUp() {
        bridge.destroy()
        ShadowDialog.getShownDialogs().toList().forEach { if (it.isShowing) it.dismiss() }
    }

    @Test fun resizeEscapesParentClippingPositionsNativeCloseAndRestoresLayoutAndOrientation() {
        val originalParams = webView.layoutParams
        val originalIndex = parent.indexOfChild(webView)
        command(1, "setOrientationProperties", """{"allowOrientationChange":false,"forceOrientation":"landscape"}""")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

        command(2, "resize", """{"properties":{"width":200,"height":120,"offsetX":10,"offsetY":20,"customClosePosition":"bottom-left","allowOffscreen":true}}""")
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertNotSame(parent, webView.parent)
        val close = findView(dialog.window!!.decorView) { it.contentDescription == "Close ad" }
        assertNotNull(close)
        val closeParams = close!!.layoutParams as FrameLayout.LayoutParams
        assertEquals(Gravity.BOTTOM or Gravity.START, closeParams.gravity)

        assertTrue(close.performClick())
        idleMain()
        assertFalse(dialog.isShowing)
        assertSame(parent, webView.parent)
        assertEquals(originalIndex, parent.indexOfChild(webView))
        assertSame(originalParams, webView.layoutParams)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test fun twoPartExpandUsesSecondBridgedWebViewAndPreservesOriginalCreative() {
        command(1, "expand", """{"url":"https://creative.test/expanded"}""")
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertSame(parent, webView.parent)
        val expanded = collectViews(dialog.window!!.decorView).filterIsInstance<WebView>().single()
        assertNotSame(webView, expanded)
        assertEquals("https://creative.test/expanded", shadowOf(expanded).lastLoadedUrl)
        assertNotNull(shadowOf(expanded).getJavascriptInterface("EngageMraidNative"))
        assertEquals(null, shadowOf(webView).lastLoadedUrl)

        val close = findView(dialog.window!!.decorView) { it.contentDescription == "Close ad" }!!
        assertTrue(close.performClick())
        idleMain()
        assertFalse(dialog.isShowing)
        assertSame(parent, webView.parent)
    }

    @Test fun onePartExpandRestoresOriginalParentIndexAndLayout() {
        val originalParams = webView.layoutParams
        val originalIndex = parent.indexOfChild(webView)
        command(1, "expand", "{}")
        val dialog = ShadowDialog.getLatestDialog()
        assertNotSame(parent, webView.parent)
        findView(dialog.window!!.decorView) { it.contentDescription == "Close ad" }!!.performClick()
        idleMain()
        assertSame(parent, webView.parent)
        assertEquals(originalIndex, parent.indexOfChild(webView))
        assertSame(originalParams, webView.layoutParams)
    }

    @Test fun activeMeasurementRejectsTwoPartExpandButKeepsOriginalWebView() {
        val guarded = MraidBridge(
            activity,
            webView,
            false,
            DiagnosticListener.NONE,
            onClose = {},
            onClick = {},
            allowTwoPartExpand = { false },
        )
        val before = ShadowDialog.getShownDialogs().count { it.isShowing }
        guarded.javascriptApi.postMessage("""{"id":1,"command":"expand","args":{"url":"https://creative.test/expanded"}}""")
        idleMain()

        assertEquals(before, ShadowDialog.getShownDialogs().count { it.isShowing })
        assertSame(parent, webView.parent)
        guarded.destroy()
    }

    @Test fun resizeCloseObstructionIsRegisteredAndRemovedWithOverlay() {
        val added = mutableListOf<View>()
        val removed = mutableListOf<View>()
        val measured = MraidBridge(
            activity,
            webView,
            false,
            DiagnosticListener.NONE,
            onClose = {},
            onClick = {},
            onFriendlyObstructionAdded = added::add,
            onFriendlyObstructionRemoved = removed::add,
        )
        measured.javascriptApi.postMessage("""{"id":1,"command":"resize","args":{"properties":{"width":200,"height":120,"offsetX":0,"offsetY":0,"customClosePosition":"top-right","allowOffscreen":true}}}""")
        idleMain()
        assertEquals(1, added.size)

        assertTrue(added.single().performClick())
        idleMain()
        assertEquals(added, removed)
        measured.destroy()
    }

    @Test fun clickIsSignaledAndCommandsQueuedAfterDestroyAreIgnored() {
        command(1, "open", """{"url":"https://click.test/path"}""")
        assertEquals(listOf("https://click.test/path"), clicks)

        bridge.destroy()
        command(2, "open", """{"url":"https://click.test/stale"}""")
        command(3, "resize", """{"properties":{"width":200,"height":120,"offsetX":0,"offsetY":0,"customClosePosition":"top-right","allowOffscreen":true}}""")
        assertEquals(listOf("https://click.test/path"), clicks)
        assertSame(parent, webView.parent)
    }

    @Test fun defaultInlineCloseHidesCreativeAndDismissesIt() {
        command(1, "close", "{}")
        assertEquals(View.INVISIBLE, webView.visibility)
        assertEquals(1, closeCount)
    }

    @Test fun oversizedAndDeepBridgeMessagesAreRejectedWithOneDiagnosticPerWindow() {
        val events = mutableListOf<DiagnosticEvent>()
        val guarded = MraidBridge(activity, webView, false, DiagnosticListener(events::add), onClose = {}, onClick = {})
        guarded.javascriptApi.postMessage("x".repeat(65_537))
        val nested = "{\"id\":1,\"command\":\"open\",\"args\":" + "{\"x\":".repeat(9) + "0" + "}".repeat(9) + "}"
        guarded.javascriptApi.postMessage(nested)
        idleMain()

        assertEquals(1, events.count { it.code == "mraid_command_rejected" })
        guarded.destroy()
    }

    @Test fun bridgeAcceptsAtMost64CommandsPerSecond() {
        val events = mutableListOf<DiagnosticEvent>()
        val guarded = MraidBridge(activity, webView, false, DiagnosticListener(events::add), onClose = {}, onClick = {})
        repeat(65) { id ->
            guarded.javascriptApi.postMessage("""{"id":$id,"command":"setOrientationProperties","args":{"forceOrientation":"none"}}""")
        }
        idleMain()

        assertEquals(1, events.count { it.code == "mraid_command_rejected" })
        guarded.destroy()
    }

    private fun command(id: Int, name: String, args: String) {
        bridge.javascriptApi.postMessage("""{"id":$id,"command":"$name","args":$args}""")
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun collectViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(collectViews(root.getChildAt(index)))
    }

    private fun findView(root: View, predicate: (View) -> Boolean): View? =
        collectViews(root).firstOrNull(predicate)
}
