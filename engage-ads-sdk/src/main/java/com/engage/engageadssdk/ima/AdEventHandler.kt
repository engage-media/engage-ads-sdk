package com.engage.engageadssdk.ima

import android.util.Log
import com.engage.engageadssdk.module.EMAdsModule
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate

internal class AdEventHandler(
    private val adPlayerImpl: AdPlayerImpl
) : AdEvent.AdEventListener {

    private val callbacks: MutableList<VideoAdPlayer.VideoAdPlayerCallback> = mutableListOf()

    override fun onAdEvent(adEvent: AdEvent) {
        Log.d("AdEventHandler", "onAdEvent: $adEvent")
        when (adEvent.type) {
            AdEvent.AdEventType.CLICKED -> {
                adPlayerImpl.onAdTapped()
            }
            AdEvent.AdEventType.TAPPED -> {
                adPlayerImpl.onAdTapped()
            }
            AdEvent.AdEventType.LOADED -> {
                adPlayerImpl.listener?.onAdsLoaded()
                if (EMAdsModule.getInstance().isAutoPlay) {
                    adPlayerImpl.playAd()
                }
            }
            AdEvent.AdEventType.STARTED -> {
                adPlayerImpl.listener?.onAdStarted()
            }
            AdEvent.AdEventType.AD_BREAK_ENDED -> {
                adPlayerImpl.listener?.onAdEnded()
            }
            AdEvent.AdEventType.COMPLETED -> {
                adPlayerImpl.listener?.onAdEnded()
            }
            AdEvent.AdEventType.PAUSED -> {
                adPlayerImpl.listener?.onAdPaused()
            }
            AdEvent.AdEventType.RESUMED -> {
                adPlayerImpl.listener?.onAdResumed()
            }
            AdEvent.AdEventType.CONTENT_PAUSE_REQUESTED -> adPlayerImpl.controller?.pauseContent()
            AdEvent.AdEventType.CONTENT_RESUME_REQUESTED -> adPlayerImpl.controller?.resumeContent()
            AdEvent.AdEventType.ALL_ADS_COMPLETED -> {
                adPlayerImpl.listener?.onAdEnded()
                adPlayerImpl.destroyAdManager()

            }
            else -> Unit
        }
    }

    fun onAdProgress(mediaInfo: AdMediaInfo, adProgress: VideoProgressUpdate) {
        callbacks.forEach { it.onAdProgress(mediaInfo, adProgress) }
    }

    fun addCallback(callback: VideoAdPlayer.VideoAdPlayerCallback) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: VideoAdPlayer.VideoAdPlayerCallback) {
        callbacks.remove(callback)
    }

    fun onComplete(adMediaInfo: AdMediaInfo) {
        callbacks.forEach { it.onEnded(adMediaInfo) }
    }
}