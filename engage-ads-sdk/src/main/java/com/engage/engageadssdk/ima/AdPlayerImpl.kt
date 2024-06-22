package com.engage.engageadssdk.ima

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import androidx.media3.ui.PlayerView
import com.engage.engageadssdk.data.EMVASTAd

internal class AdPlayerImpl(
    context: Context,
    private val playerView: PlayerView,
    private val progressBar: ProgressBar,
    private val imaAdsLoader: ImaAdsLoader = ImaAdsLoader.Builder(playerView.context).build(),
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).run {
        val mediaSourceFactory: MediaSource.Factory =
            DefaultMediaSourceFactory(context).setLocalAdInsertionComponents({
                imaAdsLoader
            }, playerView)
        setMediaSourceFactory(mediaSourceFactory)
        build()
    }

) : EMAdPlayer, LifecycleEventObserver {
    private var emContentPlaybackListener: EMContentPlaybackListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = Runnable { updateProgress() }

    init {
        playerView.player = exoPlayer
        playerView.useController = false
    }

    private fun updateProgress() {
        if (exoPlayer.isPlaying) {
            val progress = (exoPlayer.currentPosition * 100 / exoPlayer.duration).toInt()
            progressBar.progress = progress
            emContentPlaybackListener?.onProgressUpdate(
                exoPlayer.currentPosition,
                exoPlayer.duration
            )
        }
        handler.postDelayed(updateProgressAction, 1000)
    }

    @OptIn(UnstableApi::class)
    fun prepareMediaSource(emVastAd: EMVASTAd, vastUrl: String): AdsMediaSource {
        imaAdsLoader.setPlayer(playerView.player)
        val adTagDataSpec = DataSpec(Uri.parse(emVastAd.adMediaFiles?.get(0)?.text))
        val dataSourceFactory = DefaultDataSource.Factory(playerView.context)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return AdsMediaSource(
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(emVastAd.adMediaFiles?.get(0)?.text!!)), // Placeholder for content MediaItem
            adTagDataSpec,
            vastUrl,
            mediaSourceFactory,
            imaAdsLoader,
            playerView
        )
    }

    @UnstableApi
    override fun playAd(
        emVastAd: EMVASTAd,
        emContentPlaybackListener: EMContentPlaybackListener,
        vastUrl: String
    ) {
        val adsMediaSource = prepareMediaSource(emVastAd, vastUrl)
        exoPlayer.setMediaSource(adsMediaSource)
        exoPlayer.prepare()
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {

            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == ExoPlayer.STATE_ENDED) {
                    emContentPlaybackListener.onContentEnded()
                } else if (state == ExoPlayer.STATE_READY) {
                    emContentPlaybackListener.onContentStarted()
                }
            }
        })
        exoPlayer.playWhenReady = true
        this.emContentPlaybackListener = emContentPlaybackListener
        updateProgress()
    }

    override fun release() {
        handler.removeCallbacks(updateProgressAction)
        exoPlayer.release()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                exoPlayer.playWhenReady = true
                updateProgress()
            }

            Lifecycle.Event.ON_PAUSE -> {
                exoPlayer.playWhenReady = false
                handler.removeCallbacks(updateProgressAction)
            }

            Lifecycle.Event.ON_DESTROY -> release()
            else -> Unit
        }
    }
}