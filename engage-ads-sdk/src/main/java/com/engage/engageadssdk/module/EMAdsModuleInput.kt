package com.engage.engageadssdk.module

import android.content.Context

interface EMAdsModuleInput {
    val isGdprApproved: Boolean
    val userId: String
    val context: Context
    val channelId: String
    val publisherId: String
    val bundleId: String?
}