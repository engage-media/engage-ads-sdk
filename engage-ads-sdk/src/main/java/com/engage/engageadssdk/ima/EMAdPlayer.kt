package com.engage.engageadssdk.ima

import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.data.EMVASTAd

internal interface EMAdPlayer {
    @UnstableApi
    fun playAd(
        emVastAd: EMVASTAd,
        emContentPlaybackListener: EMContentPlaybackListener,
        vastUrl: String,
    )

    fun release()
    val emVastAd: EMVASTAd?
}