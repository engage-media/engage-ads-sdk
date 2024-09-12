package com.engage.engageadssdk.ima

import com.google.ads.interactivemedia.v3.api.AdDisplayContainer
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory

internal class AdRequestManager(
    private val imaSdkFactory: ImaSdkFactory,
    private val adsLoader: AdsLoader,
    private val adDisplayContainer: AdDisplayContainer
) {

    fun requestAds(adTagUrl: String) {
        val adsRequest: AdsRequest = imaSdkFactory.createAdsRequest().apply {
            this.adTagUrl = adTagUrl
        }
        adsLoader.requestAds(adsRequest)
    }
}