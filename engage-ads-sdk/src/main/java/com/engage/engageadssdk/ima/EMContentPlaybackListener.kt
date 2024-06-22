package com.engage.engageadssdk.ima

internal interface EMContentPlaybackListener {
    fun onProgressUpdate(currentTime: Long, totalTime: Long)
    fun onContentEnded()
    fun onContentStarted()
}