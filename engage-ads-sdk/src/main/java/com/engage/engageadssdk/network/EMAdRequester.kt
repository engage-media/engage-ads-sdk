package com.engage.engageadssdk.network

import com.engage.engageadssdk.data.EMVASTAd
import kotlinx.coroutines.flow.Flow

internal interface EMAdRequester {
    suspend fun requestAds()
    val receivedAds: Flow<List<EMVASTAd>>
}