package com.engage.engageadssdk

interface EMVideoPlayerListener {
    fun onAdStarted()
    fun onAdLoading()
    fun onAdsLoaded()
    // notify the client to prepare for an adStart call
    fun onAdEnded()
    fun onAdPaused()
    fun onAdResumed()

    fun onAdLoadError(message: String) {
        // Default implementation that does nothing
    }
    fun onAdTapped() {
        // Default implementation that does nothing
    }
}