package com.engage.engageadssdk.ima

import android.content.Context
import com.engage.engageadssdk.network.AdNetworkService
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory

internal class AdRequestManager(
    private val imaSdkFactory: ImaSdkFactory,
    private val adsLoader: AdsLoader,
    context: Context,
    private val adNetworkService: AdNetworkService = AdNetworkService(context = context)
) {

    fun requestAds(adTagUrl: String) {
        val vastAdTagUrl = adNetworkService.aggregateVastDeviceData(adTagUrl)
        val adsRequest: AdsRequest = imaSdkFactory.createAdsRequest().apply {
            this.adTagUrl = vastAdTagUrl
        }
        adsLoader.requestAds(adsRequest)
    }
}