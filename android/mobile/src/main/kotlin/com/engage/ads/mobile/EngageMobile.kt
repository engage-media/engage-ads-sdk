package com.engage.ads.mobile

import android.content.Context
import com.engage.ads.DeviceCategory
import com.engage.ads.EngageClient
import com.engage.ads.EngageConfiguration

object EngageMobile {
    @JvmStatic
    fun create(context: Context, configuration: EngageConfiguration): EngageClient {
        require(configuration.deviceCategory == DeviceCategory.MOBILE) { "mobile artifact requires DeviceCategory.MOBILE" }
        return EngageClient(context, configuration)
    }
}
