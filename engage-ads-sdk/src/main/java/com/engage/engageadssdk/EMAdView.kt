package com.engage.engageadssdk

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.core.view.isVisible
import com.engage.engageadssdk.ima.AdPlayerImpl
import com.engage.engageadssdk.ima.EMAdPlayer
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.ui.EmClientContentController

class EMAdView
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var isShowAdCalled: Boolean = false
    private val adPlayer: EMAdPlayer

    init {
        val vastUrl: String = fetchMetaData()
        // initialize the mediaPlayer
        val mediaPlayerView = VideoView(context).apply {
            id = "emMediaPlayerView".hashCode()
        }

        this@EMAdView.addView(mediaPlayerView)

        // use MediaPlayer instead of playerView

        adPlayer = AdPlayerImpl(context, mediaPlayerView, vastUrl)
    }

    fun loadAd() {
        adPlayer.requestAds()
    }

    fun showAd() {
        try {
            adPlayer.playAd()
        } catch (e: IllegalStateException) {
            // wait until ad is loaded then showAd
            isShowAdCalled = true
        }
    }

    fun setAdEventListener(listener: EMVideoPlayerListener) {
        adPlayer.listener = listener
    }

    private fun fetchMetaData(): String {
        val url: Uri = Uri.parse(if (EMAdsModule.getInstance().isDebug) {
            "https://s.adtelligent.com/demo"
        } else if (EMAdsModule.getInstance().baseUrl.isNotEmpty()) {
            EMAdsModule.getInstance().baseUrl
        }  else {
            "https://vast.engagemediatv.com"
        })
        return url.buildUpon().apply {
            if (EMAdsModule.getInstance().publisherId.isNotEmpty()) {
                appendQueryParameter("publisherId", EMAdsModule.getInstance().publisherId)
            }

            if (EMAdsModule.getInstance().channelId.isNotEmpty()) {
                appendQueryParameter("channelId", EMAdsModule.getInstance().channelId)
            }
        }.toString()
    }


    fun setContentController(emClientContentController: EmClientContentController) {
        adPlayer.controller = emClientContentController
    }
}