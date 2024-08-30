package com.engage.engageadssdk.ima

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import android.util.Log
import androidx.core.view.isVisible
import com.engage.engageadssdk.data.EMVASTAd

internal class AdPlayerImpl(
    context: Context,
    private val videoView: VideoView,
    private val progressBar: ProgressBar
) : EMAdPlayer, LifecycleEventObserver {

    private var emContentPlaybackListener: EMContentPlaybackListener? = null
    private var _emVastAd: EMVASTAd? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = Runnable { updateProgress() }

    init {
        val mediaController = MediaController(context)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
    }

    private fun updateProgress() {
        videoView.let { vv ->
            if (vv.isPlaying) {
                val progress = (vv.currentPosition * 100 / vv.duration)
                progressBar.progress = progress
                emContentPlaybackListener?.onProgressUpdate(
                    vv.currentPosition.toLong(),
                    vv.duration.toLong()
                )
            }
            handler.postDelayed(updateProgressAction, 1000)
        }
    }

    private fun prepareMediaSource(emVastAd: EMVASTAd) {
        videoView.setVideoURI(Uri.parse(emVastAd.adMediaFiles?.get(0)?.text))
        videoView.setOnPreparedListener { vv ->
            vv.setOnCompletionListener {
                emContentPlaybackListener?.onContentEnded()
            }
            vv.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    emContentPlaybackListener?.onContentStarted()
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    progressBar.isVisible = true
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    progressBar.isVisible = false
                }
                true
            }
            vv.start()
            updateProgress()
        }
        videoView.setOnErrorListener { _, what, extra ->
            Log.e("AdPlayerImpl", "VideoView error: what=$what, extra=$extra")
            true
        }
    }

    override val emVastAd: EMVASTAd?
        get() = _emVastAd

    override fun playAd(
        emVastAd: EMVASTAd,
        emContentPlaybackListener: EMContentPlaybackListener,
        vastUrl: String
    ) {
        _emVastAd = emVastAd
        prepareMediaSource(emVastAd)
        this.emContentPlaybackListener = emContentPlaybackListener
    }

    override fun release() {
        handler.removeCallbacks(updateProgressAction)
        videoView.stopPlayback()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                videoView.start()
                updateProgress()
            }
            Lifecycle.Event.ON_PAUSE -> {
                videoView.pause()
                handler.removeCallbacks(updateProgressAction)
            }
            Lifecycle.Event.ON_DESTROY -> release()
            else -> Unit
        }
    }
}