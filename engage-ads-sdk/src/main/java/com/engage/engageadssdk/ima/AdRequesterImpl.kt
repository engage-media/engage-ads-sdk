package com.engage.engageadssdk.ima

import android.util.Log
import androidx.media3.ui.BuildConfig
import com.engage.engageadssdk.network.EMAdRequester
import com.engage.engageadssdk.data.EMAdMapper
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engageadssdk.network.AdNetworkService
import com.engage.engageadssdk.network.EmptyVASTResponseException
import com.engage.engageadssdk.network.VASTResponse
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

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
            VASTResponse()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                throw e
            } else {
                Log.e("AdRequesterImpl", "Error fetching VAST response", e)
                VASTResponse()
            }
        }

        val mappedResponse =
            EMAdMapper.mapToEMVASTAd(result, vastUrl)
        flow.send(mappedResponse)

    }
}