package com.engage.engageadssdk.ima

import android.net.Uri
import android.util.Log
import android.widget.ProgressBar
import android.widget.VideoView
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import java.util.Timer
import java.util.TimerTask
import kotlin.math.roundToInt

internal class AdProgressTracker(
    private val videoView: VideoView,
    private var onAdProgressListener: ((AdMediaInfo, VideoProgressUpdate) -> Unit)?,
    private var onAdCompleted: ((AdMediaInfo) -> Unit)? = null
) {
    private var timer: Timer? = null
    private var adDuration: Long = 0
    private var adPosition: Int = 0
    var loadedAdMediaInfo: AdMediaInfo? = null

    fun getAdProgress(): VideoProgressUpdate {
        Log.d("AdProgressTracker", "getAdProgress: ${videoView.currentPosition}")
        val adPosition: Long = videoView.currentPosition.toLong()
        return VideoProgressUpdate(adPosition, adDuration)
    }

    fun loadAd(adMediaInfo: AdMediaInfo) {
        loadedAdMediaInfo = adMediaInfo
        videoView.setVideoURI(Uri.parse(adMediaInfo.url))
        videoView.setOnPreparedListener { vv ->
            adDuration = vv.duration.toLong()
            if (adPosition > 0) {
                vv.seekTo(adPosition)
            }
            startAdTracking()
            vv.setOnCompletionListener {
                adPosition = 0
                stopAdTracking()
                onAdCompleted?.invoke(adMediaInfo)
                onAdProgressListener = null

                // Notify ad ended
            }
            vv.setOnErrorListener { _, _, _ ->
                // Notify ad error
                Log.e("AdProgressTracker", "Error loading ad")
                true
            }
            vv.start()
        }
    }

    private fun startAdTracking() {
        timer?.cancel()
        timer = Timer()
        val updateTimerTask: TimerTask = object : TimerTask() {
            override fun run() {
                val progressUpdate = getAdProgress()
                if (loadedAdMediaInfo != null) {
                    notifyAdProgress(loadedAdMediaInfo!!, progressUpdate)
                }
            }

            private fun notifyAdProgress(loadedAdMediaInfo: AdMediaInfo, adProgress: VideoProgressUpdate) {
                onAdProgressListener?.invoke(loadedAdMediaInfo, adProgress)
            }
        }
        timer?.schedule(updateTimerTask, 250L, 250L)
    }

    fun stopAdTracking() {
        timer?.cancel()
        timer = null
    }
}