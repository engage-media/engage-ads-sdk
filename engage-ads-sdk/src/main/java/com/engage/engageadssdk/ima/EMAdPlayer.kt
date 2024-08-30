package com.engage.engageadssdk.ima

import com.engage.engageadssdk.data.EMVASTAd

internal interface EMAdPlayer {
    fun playAd(
        emVastAd: EMVASTAd,
        emContentPlaybackListener: EMContentPlaybackListener,
        vastUrl: String,
    )

    fun release()
    val emVastAd: EMVASTAd?
}