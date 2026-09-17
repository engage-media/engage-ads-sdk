package com.engage.ads.tv

import android.content.Context
import com.engage.ads.DeviceCategory
import com.engage.ads.EngageClient
import com.engage.ads.EngageConfiguration

object EngageTv {
    @JvmStatic
    fun create(context: Context, configuration: EngageConfiguration): EngageClient {
        require(configuration.deviceCategory == DeviceCategory.TV) { "TV artifact requires DeviceCategory.TV" }
        return EngageClient(context, configuration)
    }
}
