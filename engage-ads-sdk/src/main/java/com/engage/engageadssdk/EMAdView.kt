package com.engage.engageadssdk

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.core.view.isVisible
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engageadssdk.ima.AdPlayerImpl
import com.engage.engageadssdk.ima.EMAdPlayer
import com.engage.engageadssdk.ima.EMContentPlaybackListener
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.ui.EMViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class EMAdView
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var gestureDetector: GestureDetector
    private var isShowAdCalled: Boolean = false
    private val viewModel: EMViewModel = EMViewModel()
    private val mediaPlayer: MediaPlayer
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
        // initialize the mediaPlayer
        mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener { mp ->
                mp.start()
                Log.d("MediaPlayerConfig", "MediaPlayer started")
            }
            setOnCompletionListener {
                Log.d("MediaPlayerConfig", "MediaPlayer completed")
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.d("MediaPlayerConfig", "MediaPlayer rendering started")
                }
                true
            }
        }

// Configure MediaPlayer
//        mediaPlayer.setDataSource(context, Uri.parse("your_video_uri_here"))
//        mediaPlayer.prepareAsync()

// Add MediaPlayer to EMAdView
        val mediaPlayerView = VideoView(context).apply {
//            setMediaController(MediaController(context).apply {
//                setAnchorView(this@apply)
//            })
//            setVideoURI(Uri.parse("your_video_uri_here"))
            setOnPreparedListener { mp ->
                mp.start()
                Log.d("MediaPlayerConfig", "VideoView started")
            }
            setOnCompletionListener {
                Log.d("MediaPlayerConfig", "VideoView completed")
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.d("MediaPlayerConfig", "VideoView rendering started")
                }
                true
            }
            id = "emMediaPlayerView".hashCode()
        }

        this@EMAdView.addView(mediaPlayerView)
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

        // use MediaPlayer instead of playerView

        adPlayer = AdPlayerImpl(context, mediaPlayerView, progressBar)
        bindCollectorsToViewModel(viewModel, viewModel.scope)
        viewModel.initialize(vastUrl, mediaPlayerView.context)
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
            }
        }
        viewModelScope.launch {
            viewModel.nextAdFlow.collect {
                showAd(it)
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
            viewModel.showNextAd()
        } catch (e: IllegalStateException) {
            // wait until ad is loaded then showAd
            isShowAdCalled = true
        }
    }

    fun setAdEventListener(listener: EMVideoPlayerListener) {
        viewModel.eventListener = listener
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
                appendQueryParameter("publisher", EMAdsModule.getInstance().publisherId)
            }

            if (EMAdsModule.getInstance().channelId.isNotEmpty()) {
                appendQueryParameter("channel", EMAdsModule.getInstance().channelId)
            }
        }.toString()
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