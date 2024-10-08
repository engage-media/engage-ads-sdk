package com.engage.engageadssdk.module

import android.content.Context

interface EMAdsModuleInput {
    val isAutoPlay: Boolean
        get() {
        return false
    }
    val isGdprApproved: Boolean
    val context: Context
    val channelId: String
    val publisherId: String
    val bundleId: String?
    val isDebug: Boolean
        get() {
            return false
        }
    val baseUrl: String
        get() {
            return ""
        }
}