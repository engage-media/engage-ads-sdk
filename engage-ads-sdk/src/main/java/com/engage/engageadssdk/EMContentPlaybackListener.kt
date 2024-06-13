package com.engage.engageadssdk

interface EMContentPlaybackListener {
    fun onProgressUpdate(currentTime: Long, totalTime: Long)
    fun onContentEnded()
}