package com.engage.ads.render

import android.view.ViewGroup
import com.engage.ads.AdRequest
import com.engage.ads.LoadedCreative

internal interface CreativeRenderer {
    fun display(creative: LoadedCreative, request: AdRequest, container: ViewGroup, callback: Callback)
    fun destroy()

    interface Callback {
        fun displayed()
        fun clicked(url: String?)
        fun adCompleted()
        fun breakCompleted()
        fun dismissed()
        fun rewardEarned()
        fun failed(error: Throwable)
    }
}

interface ContentController {
    fun pauseContent()
    fun resumeContent()
}
