package com.engage.engageadssdk.ui

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.network.EMAdRequester
import com.engage.engageadssdk.EMVideoPlayerListener
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engageadssdk.data.EMVASTMidrollAd
import com.engage.engageadssdk.ima.AdRequesterImpl
import com.engage.engageadssdk.network.AdNetworkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@UnstableApi
internal class EMViewModel {

    private var currentAdPlayer = 0
    private var adsList: List<EMVASTAd>? = null
    private var adRequester: EMAdRequester? = null
    private val job: Job = Job()
    val scope = CoroutineScope(Dispatchers.Main + job)

    private val _onAdDataReceived: Channel<EMVASTAd> = Channel(Channel.CONFLATED)
    val onAdDataReceived: Flow<EMVASTAd>
        get() = _onAdDataReceived.receiveAsFlow()
    private val _onNoAdsReceived: Channel<Unit> = Channel(Channel.CONFLATED)
    val onNoAdsReceived: Flow<Unit>
        get() = _onNoAdsReceived.receiveAsFlow()

    private val _isLoadingAd: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    val isLoadingAd: StateFlow<Boolean>
        get() = _isLoadingAd

    var eventListener: EMVideoPlayerListener? = null
        set(value) {
            field = value
            scope.launch {
                field?.onProgressUpdateFlow?.collect {
                    val (current, total) = it
                    val progress = (current * 100 / total).toInt()
                    shouldPlayNextAd(progress)
                }

            }
        }

    private fun shouldPlayNextAd(progress: Int): Boolean {
        val localAdsList = adsList
        return if (localAdsList != null) {
            if (localAdsList.size <= 1) {
                return false
                // Preroll already played
            } else if (currentAdPlayer >= localAdsList.size - 1) {
                showAd() // // Postroll
                return true
            } else {
                // midroll ad
                val ad = localAdsList[currentAdPlayer] as EMVASTMidrollAd
                return if (ad.offset != null && ad.offset.time <= progress) {
                    currentAdPlayer++
                    showAd()
                    true
                } else {
                    false
                }
            }
        } else {
            false
        }
    }

    fun initialize(
        vastUrl: String,
        context: Context,
    ) {
        adRequester = AdRequesterImpl(vastUrl, AdNetworkService(context))
        loadAd()
    }

    fun showAd() {
        if (isLoadingAd.value) {
            return // already loading, be patient - ignored
        }
        if (adsList?.isNotEmpty() == true && currentAdPlayer < (adsList?.size ?: 0)) {
            val ad = adsList?.get(currentAdPlayer)
            scope.launch {
                ad?.let {
                    _onAdDataReceived.send(it)
                    currentAdPlayer++
                }
            }
        } else {
            loadAd()
        }
    }

    @UnstableApi
    fun loadAd() {
        if (_isLoadingAd.value) {
            return
        }


        _isLoadingAd.value = currentAdPlayer >= (adsList?.size ?: 0)
        scope.launch(Dispatchers.IO) {
            adRequester?.requestAds()
        }
        scope.launch(Dispatchers.Main) {
            adRequester?.receivedAds?.collect { ads ->
                _isLoadingAd.value = false
                if (ads.isEmpty()) {
                    _onNoAdsReceived.send(Unit)
                    return@collect
                }
                currentAdPlayer = 0
                adsList = ads
                showAd()
            }
        }
    }

    fun release() {
        eventListener = null
        job.cancel()
    }
}