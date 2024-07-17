package com.engage.engageadssdk

import com.engage.engageadssdk.data.EMVASTAd

interface EMClientListener {
    fun onAdsLoaded()
    fun onAdsLoadFailed()
    fun onAdStarted()
    fun onAdCompleted()
    fun onAdTapped(ad: EMVASTAd?)
    fun onNoAdsLoaded()
}