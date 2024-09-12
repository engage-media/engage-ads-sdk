package com.engage.engageadssdk.ima

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.ViewGroup
import android.widget.VideoView
import com.engage.engageadssdk.EMVideoPlayerListener
import com.engage.engageadssdk.ui.EmClientContentController
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer
import com.google.ads.interactivemedia.v3.api.AdPodInfo
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsManager
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate


internal class AdPlayerImpl(
    context: Context,
    private val videoView: VideoView,
    private val vastUrl: String,
) : EMAdPlayer, VideoAdPlayer {

    private val imaSdkFactory: ImaSdkFactory = ImaSdkFactory.getInstance()
    private val adDisplayContainer: AdDisplayContainer =
        ImaSdkFactory.createAdDisplayContainer(videoView.parent as ViewGroup, this)
    private val adsLoader: AdsLoader = imaSdkFactory.createAdsLoader(
        context, imaSdkFactory.createImaSdkSettings(), adDisplayContainer
    )
    var adsManager: AdsManager? = null

    private val adProgressTracker =
        AdProgressTracker(videoView, { adMediaInfo, progress ->
            adEventHandler.onAdProgress(adMediaInfo, progress)
        }) { adMediaInfo ->
            adEventHandler.onComplete(adMediaInfo)
        }
    private val adEventHandler = AdEventHandler(this)
    private val adRequestManager = AdRequestManager(imaSdkFactory, adsLoader, adDisplayContainer)

    init {
        adsLoader.addAdsLoadedListener { adsManagerLoadedEvent ->
            adsManager = adsManagerLoadedEvent.adsManager
            adsManager?.addAdEventListener(adEventHandler)
            adsManager?.init(imaSdkFactory.createAdsRenderingSettings())
        }
        adsLoader.addAdErrorListener { adErrorEvent ->
            Log.e("AdPlayerImpl", "Ad error: ${adErrorEvent.error.message}")
            listener?.onAdLoadError(adErrorEvent.error.message)
        }
    }

    val adMediaInfo: AdMediaInfo?
        get() = adProgressTracker.loadedAdMediaInfo

    override fun requestAds() {
        adRequestManager.requestAds(vastUrl)
        listener?.onAdLoading()
    }

    override var listener: EMVideoPlayerListener? = null
    override var controller: EmClientContentController? = null
    override fun playAd() {
        adsManager?.start()
        if (adsManager == null) {
            listener?.onAdLoadError("AdsManager is null")
        }
    }

    override fun getAdProgress(): VideoProgressUpdate {
        Log.d("AdPlayerImpl", "getAdProgress")
        return adProgressTracker.getAdProgress()
    }

    override fun getVolume(): Int {
        val audioManager = videoView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / audioManager.getStreamMaxVolume(
            AudioManager.STREAM_MUSIC
        ))
    }

    override fun addCallback(p0: VideoAdPlayer.VideoAdPlayerCallback) {
        adEventHandler.addCallback(p0)
    }

    override fun removeCallback(p0: VideoAdPlayer.VideoAdPlayerCallback) {
        Log.d("AdPlayerImpl", "removeCallback: $p0")
        adEventHandler.removeCallback(p0)
    }

    override fun loadAd(p0: AdMediaInfo, p1: AdPodInfo) {
        Log.d("AdPlayerImpl", "loadAd: $p0")
        adProgressTracker.loadAd(p0)
    }

    override fun playAd(p0: AdMediaInfo) {
        Log.d("AdPlayerImpl", "playAd: $p0")
        videoView.start()
        listener?.onAdStarted()
    }

    override fun pauseAd(p0: AdMediaInfo) {
        Log.d("AdPlayerImpl", "pauseAd: $p0")
        videoView.pause()
        listener?.onAdPaused()
    }

    override fun stopAd(p0: AdMediaInfo) {
        Log.d("AdPlayerImpl", "stopAd: $p0")
        videoView.stopPlayback()
        adProgressTracker.stopAdTracking()
    }

    override fun release() {
        Log.d("AdPlayerImpl", "release")
        videoView.stopPlayback()
    }

    fun onAdTapped() {
        Log.d("AdPlayerImpl", "onAdTapped")
        listener?.onAdTapped()
    }

    fun destroyAdManager() {
        adsManager?.destroy()
        adsManager = null
    }
}