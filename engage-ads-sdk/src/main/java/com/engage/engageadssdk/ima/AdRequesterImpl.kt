package com.engage.engageadssdk.ima

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.network.EMAdRequester
import com.engage.engageadssdk.data.EMAdMapper
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engageadssdk.network.AdNetworkService
import com.engage.engageadssdk.network.EmptyVASTResponseException
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

@UnstableApi
internal class AdRequesterImpl(
    private val vastUrl: String,
    private val adNetworkService: AdNetworkService
) : EMAdRequester {

    // create a flow that will emit a list of EMAds
    private val flow: Channel<List<EMVASTAd>?> = Channel<List<EMVASTAd>?>()

    override val receivedAds: Flow<List<EMVASTAd>>
        get() {
            return flow.receiveAsFlow().filter { it != null }.map { it!! }
        }

    override suspend fun requestAds() {
        val result = try {
            adNetworkService.fetchVASTResponse(vastUrl)
        } catch (e: EmptyVASTResponseException) {
            adNetworkService.fetchVASTResponse(adNetworkService.defaultVastUrl)
        } catch (e: Exception) {
            Log.e("AdRequesterImpl", "Something horrible happened")
            adNetworkService.fetchVASTResponse(adNetworkService.defaultVastUrl)
        }

        val mappedResponse =
            EMAdMapper.mapToEMVASTAd(result, vastUrl)
        flow.send(mappedResponse)

    }
}