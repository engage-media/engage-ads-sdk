package com.engage.engageadssdk

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engageadssdk.ima.AdPlayerImpl
import com.engage.engageadssdk.ima.EMAdPlayer
import com.engage.engageadssdk.ima.EMContentPlaybackListener
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.ui.EMViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@UnstableApi
class EMAdView
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var gestureDetector: GestureDetector
    private var isShowAdCalled: Boolean = false
    private val viewModel: EMViewModel = EMViewModel()
    private val playerView: PlayerView
    private val progressBar: ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val adLoadingProgressBar: ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleLarge)
    private val adPlayer: EMAdPlayer
    private var emAdEMClientListener: EMClientListener? = null

    fun setClientListener(listener: EMClientListener) {
        emAdEMClientListener = listener
    }

    init {
        val vastUrl: String = fetchMetaData()
        with(PlayerView(context)) {
            playerView = this
            playerView.useController = false
            playerView.useController = false
            playerView.controllerAutoShow = false
            playerView.controllerShowTimeoutMs = 5000
            playerView.setControllerVisibilityListener(ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    playerView.hideController()
                    Log.d("PlayerViewConfig", "Hiding controller programmatically")
                }
            })
            playerView.id = "emPlayerView".hashCode()
            this@EMAdView.addView(playerView)
        }
        with(progressBar) {
            layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM
                }

            this@EMAdView.addView(progressBar)
        }
        with(adLoadingProgressBar) {
            layoutParams =
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                }
            isVisible = false
            this@EMAdView.addView(adLoadingProgressBar)
        }

        adPlayer = AdPlayerImpl(context, playerView, progressBar)
        bindCollectorsToViewModel(viewModel, viewModel.scope)
        viewModel.initialize(vastUrl, playerView.context)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val ad = adPlayer.emVastAd
                emAdEMClientListener?.onAdTapped(ad)
                return true
            }
        })

    }

    private fun bindCollectorsToViewModel(viewModel: EMViewModel, viewModelScope: CoroutineScope) {
        viewModelScope.launch {
            viewModel.onAdDataReceived.collect {
                emAdEMClientListener?.onAdsLoaded()
                showAd(it)
                isShowAdCalled = false
            }
        }
        viewModelScope.launch {
            viewModel.onNoAdsReceived.collect {
                emAdEMClientListener?.onNoAdsLoaded()
            }
        }
        viewModelScope.launch {
            viewModel.isLoadingAd.collect {
                adLoadingProgressBar.isVisible = it
            }
        }
    }

    fun loadAd() {
        viewModel.loadAd()
    }

    fun showAd() {
        try {
            viewModel.showAd()
        } catch (e: IllegalStateException) {
            // wait until ad is loaded then showAd
            isShowAdCalled = true
        }
    }

    fun setAdEventListener(listener: EMVideoPlayerListener) {
        viewModel.eventListener = listener
    }

    private fun fetchMetaData(): String {
        val metaData = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA
        ).metaData
        val areChannelOrPublisherIdSet = metaData.containsKey("com.engage.channelId") ||
                metaData.containsKey("com.engage.publisherId") ||
                EMAdsModule.getInstance().publisherId.isNotEmpty() ||
                EMAdsModule.getInstance().channelId.isNotEmpty()
        if (areChannelOrPublisherIdSet) {
            val url = Uri.parse("http://vast.engagemediatv.com/").buildUpon().apply {
                val channelId =
                    metaData.getString("com.engage.channelId", EMAdsModule.getInstance().channelId)
                if (!channelId.isNullOrEmpty()) {
                    appendQueryParameter("channel", channelId)
                }
                val publisherId = metaData.getString(
                    "com.engage.publisherId",
                    EMAdsModule.getInstance().publisherId
                )
                if (!publisherId.isNullOrEmpty()) {
                    appendQueryParameter("publisher", publisherId)
                }
            }
            return url.toString()
        } else {
            val vastUrl = metaData.getString("com.engage.vastUrl", null) ?: run {
                throw IllegalStateException("Vast URL is not set")
            }
            return vastUrl
        }
    }


    private fun showAd(emVastAd: EMVASTAd) {
        if (viewModel.isLoadingAd.value) {
            throw IllegalStateException("Ad is still loading")
        }

        adPlayer.playAd(
            emVastAd,
            object : EMContentPlaybackListener {
                override fun onProgressUpdate(currentTime: Long, totalTime: Long) {
                    progressBar.progress = (currentTime * 100 / totalTime).toInt()
                }

                override fun onContentEnded() {
                    emAdEMClientListener?.onAdCompleted()
                }

                override fun onContentStarted() {
                    emAdEMClientListener?.onAdStarted()
                }
            },
            emVastAd.vastUrl,
        )
    }

    override fun onDetachedFromWindow() {
        viewModel.release()
        adPlayer.release()
        super.onDetachedFromWindow()
    }
}