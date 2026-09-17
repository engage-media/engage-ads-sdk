package com.engage.ads.sample.tv

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.engage.ads.*
import com.engage.ads.tv.EngageTv

class MainActivity : Activity() {
    private lateinit var client: EngageClient
    private var ad: InStreamAd? = null
    private lateinit var slot: FrameLayout
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        client = EngageTv.create(this, EngageConfiguration(
            endpoint = sampleEndpoint(BuildConfig.ENGAGE_POD_PROTOCOL, BuildConfig.ENGAGE_POD_ENDPOINT),
            app = AppMetadata(packageName, "Engage TV Sample"),
            deviceCategory = DeviceCategory.TV,
        ))
        status = TextView(this).apply { text = "Press Load ad pod"; gravity = Gravity.CENTER }
        slot = FrameLayout(this).apply { setBackgroundColor(0xff202020.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                text = "Load ad pod"
                isFocusable = true
                setOnClickListener { loadPod() }
            }, ViewGroup.LayoutParams(-1, -2))
            addView(status, ViewGroup.LayoutParams(-1, -2))
            addView(slot, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        applySystemInsets(root)
        setContentView(root)
    }

    private fun loadPod() {
        ad?.destroy(); slot.removeAllViews(); updateStatus("Loading pod")
        var created: InStreamAd? = null
        created = client.createInStreamAd(AdRequest(
            "sample-tv-pod",
            AdFormat.INSTREAM,
            AdSize(1920, 1080),
            video = VideoConstraints(maxPodDurationSeconds = 240, maxAdsInPod = 3),
        )) { event ->
            updateStatus(event.safeLabel())
            if (event == AdEvent.Loaded) created?.display(slot)
        }
        ad = requireNotNull(created).also { it.load() }
    }

    private fun updateStatus(value: String) { status.text = value; Log.i("EngageSample", value) }

    private fun applySystemInsets(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { target, windowInsets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                left = windowInsets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = windowInsets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = windowInsets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = windowInsets.systemWindowInsetBottom
            }
            target.setPadding(initialLeft + left, initialTop + top, initialRight + right, initialBottom + bottom)
            windowInsets
        }
        view.requestApplyInsets()
    }

    override fun onDestroy() {
        ad?.destroy(); client.destroy(); super.onDestroy()
    }

    private fun sampleEndpoint(protocol: String, url: String): Endpoint = when (protocol.lowercase()) {
        "openrtb26" -> Endpoint.OpenRTB26(url)
        "vast" -> Endpoint.VastTag(url)
        else -> error("engageSamplePodProtocol must be openrtb26 or vast")
    }

    private fun AdEvent.safeLabel() = when (this) {
        is AdEvent.Error -> "Error: ${error.code}"
        is AdEvent.Clicked -> "Clicked"
        else -> javaClass.simpleName
    }
}
