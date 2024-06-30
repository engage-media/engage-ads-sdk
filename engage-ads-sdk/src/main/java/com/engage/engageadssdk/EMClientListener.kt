package com.engage.engageadssdk

interface EMClientListener {
    fun onAdsLoaded()
    fun onAdsLoadFailed()
    fun onAdStarted()
    fun onAdCompleted()
}