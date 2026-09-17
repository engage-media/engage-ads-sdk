package com.engage.ads.sample.mobile

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
import com.engage.ads.mobile.EngageMobile

class MainActivity : Activity() {
    private lateinit var auctionClient: EngageClient
    private lateinit var videoClient: EngageClient
    private var bannerAd: BannerAd? = null
    private var videoAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var nativeAd: NativeAd? = null
    private lateinit var slot: FrameLayout
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val app = AppMetadata(packageName, "Engage Mobile Sample")
        auctionClient = EngageMobile.create(this, EngageConfiguration(Endpoint.OpenRTB26(BuildConfig.ENGAGE_OPENRTB_ENDPOINT), app))
        videoClient = EngageMobile.create(this, EngageConfiguration(sampleEndpoint(BuildConfig.ENGAGE_VIDEO_PROTOCOL, BuildConfig.ENGAGE_VIDEO_ENDPOINT), app))
        val root = buildUi()
        applySystemInsets(root)
        setContentView(root)
    }

    private fun buildUi(): LinearLayout {
        val density = resources.displayMetrics.density
        status = TextView(this).apply { text = "Choose a local mock opportunity"; gravity = Gravity.CENTER }
        slot = FrameLayout(this).apply { setBackgroundColor(0xff202020.toInt()) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            addView(Button(context).apply { text = "Load banner"; setOnClickListener { loadBanner() } }, ViewGroup.LayoutParams(-1, -2))
            addView(Button(context).apply { text = "Load video"; setOnClickListener { loadVideo() } }, ViewGroup.LayoutParams(-1, -2))
            addView(Button(context).apply { text = "Load rewarded"; setOnClickListener { loadRewarded() } }, ViewGroup.LayoutParams(-1, -2))
            addView(Button(context).apply { text = "Load native"; setOnClickListener { loadNative(supportsVideo = false) } }, ViewGroup.LayoutParams(-1, -2))
            addView(Button(context).apply { text = "Load native video"; setOnClickListener { loadNative(supportsVideo = true) } }, ViewGroup.LayoutParams(-1, -2))
            addView(status, ViewGroup.LayoutParams(-1, -2))
            addView(slot, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun resetOpportunity() {
        bannerAd?.destroy(); bannerAd = null
        videoAd?.destroy(); videoAd = null
        rewardedAd?.destroy(); rewardedAd = null
        nativeAd?.destroy(); nativeAd = null
        slot.removeAllViews()
    }

    private fun loadBanner() {
        resetOpportunity(); updateStatus("Loading banner")
        var created: BannerAd? = null
        created = auctionClient.createBannerAd(AdRequest("sample-banner", AdFormat.BANNER, AdSize(320, 50))) { event ->
            updateStatus(event.safeLabel())
            if (event == AdEvent.Loaded) created?.display(slot)
        }
        bannerAd = requireNotNull(created).also { it.load() }
    }

    private fun loadVideo() {
        resetOpportunity(); updateStatus("Loading video")
        var created: InterstitialAd? = null
        created = videoClient.createInterstitialAd(
            AdRequest("sample-video", AdFormat.INTERSTITIAL, AdSize(640, 360), video = VideoConstraints()),
        ) { event ->
            updateStatus(event.safeLabel())
            if (event == AdEvent.Loaded) created?.display(slot)
        }
        videoAd = requireNotNull(created).also { it.load() }
    }

    private fun loadRewarded() {
        resetOpportunity(); updateStatus("Loading rewarded")
        var created: RewardedAd? = null
        created = auctionClient.createRewardedAd(
            AdRequest("sample-rewarded", AdFormat.REWARDED, AdSize(640, 360), video = VideoConstraints()),
        ) { event ->
            updateStatus(event.safeLabel())
            if (event == AdEvent.Loaded) created?.display(slot)
        }
        rewardedAd = requireNotNull(created).also { it.load() }
    }

    private fun loadNative(supportsVideo: Boolean) {
        resetOpportunity(); updateStatus(if (supportsVideo) "Loading native video" else "Loading native")
        var created: NativeAd? = null
        created = auctionClient.createNativeAd(
            AdRequest(if (supportsVideo) "sample-native-video" else "sample-native", AdFormat.NATIVE, native = NativeRequest(supportsVideo = supportsVideo)),
        ) { event ->
            updateStatus(event.safeLabel())
            if (event == AdEvent.Loaded) created?.display(slot)
        }
        nativeAd = requireNotNull(created).also { it.load() }
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
        bannerAd?.destroy(); videoAd?.destroy(); rewardedAd?.destroy(); nativeAd?.destroy()
        auctionClient.destroy(); videoClient.destroy()
        super.onDestroy()
    }

    private fun sampleEndpoint(protocol: String, url: String): Endpoint = when (protocol.lowercase()) {
        "openrtb26" -> Endpoint.OpenRTB26(url)
        "vast" -> Endpoint.VastTag(url)
        else -> error("engageSampleVideoProtocol must be openrtb26 or vast")
    }

    private fun AdEvent.safeLabel() = when (this) {
        is AdEvent.Error -> "Error: ${error.code}"
        is AdEvent.Clicked -> "Clicked"
        else -> javaClass.simpleName
    }
}
