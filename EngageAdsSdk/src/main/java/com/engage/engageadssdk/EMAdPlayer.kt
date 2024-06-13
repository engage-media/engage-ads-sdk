package com.engage.engageadssdk

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import com.engage.engageadssdk.data.EMVASTAd

internal interface EMAdPlayer {
    @UnstableApi
    fun playAd(
        emVastAd: EMVASTAd,
        emContentPlaybackListener: EMContentPlaybackListener,
        vastUrl: String,
    )

    fun release()
}