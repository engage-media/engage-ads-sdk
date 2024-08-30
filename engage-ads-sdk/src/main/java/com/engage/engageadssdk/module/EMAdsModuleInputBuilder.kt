package com.engage.engageadssdk.module

import android.content.Context
import android.content.pm.PackageManager

class EMAdsModuleInputBuilder {
    private var isGdprApproved: Boolean = false
    private var userId: String = ""
    private var context: Context? = null
    private var channelId: String? = null
    private var publisherId: String? = null
    private var bundleId: String? = null
    private var isDebug: Boolean = false
    private var isAutoPlay: Boolean = true
    private var baseUrl: String = ""

    fun isGdprApproved(isGdprApproved: Boolean) = apply { this.isGdprApproved = isGdprApproved }
    fun userId(userId: String) = apply { this.userId = userId }
    fun context(context: Context) = apply { this.context = context }
    fun channelId(channelId: String) = apply { this.channelId = channelId }
    fun publisherId(publisherId: String) = apply { this.publisherId = publisherId }
    fun bundleId(bundleId: String) = apply { this.bundleId = bundleId }
    fun isDebug(isDebug: Boolean) = apply { this.isDebug = isDebug }
    fun isAutoPlay(isAutoPlay: Boolean) = apply { this.isAutoPlay = isAutoPlay }
    fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }

    fun build(): EMAdsModuleInput {
        val builder = this
        return object : EMAdsModuleInput {
            override val isAutoPlay: Boolean
                get() = builder.isAutoPlay
            override val isGdprApproved: Boolean
                get() = builder.isGdprApproved
            override val userId: String
                get() = builder.userId
            override val context: Context
                get() = builder.context ?: throw IllegalStateException("Context is not set")
            override val channelId: String
                get() = builder.channelId ?: throw IllegalStateException("Channel ID is not set")
            override val publisherId: String
                get() = builder.publisherId ?: throw IllegalStateException("Publisher ID is not set")
            override val bundleId: String
                get() = builder.bundleId ?: context.packageName
            override val isDebug: Boolean
                get() = builder.isDebug
            override val baseUrl: String
                get() = builder.baseUrl
        }
    }


}