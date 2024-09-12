package com.engage.engageadssdk.ima

import com.engage.engageadssdk.EMVideoPlayerListener
import com.engage.engageadssdk.ui.EmClientContentController

internal interface EMAdPlayer {
    fun playAd()
    fun release()
    fun requestAds()

    var listener: EMVideoPlayerListener?
    var controller: EmClientContentController?
}