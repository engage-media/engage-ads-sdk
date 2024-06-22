package com.engage.engageadssdk.network.request

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.module.EMAdsModuleInput
import java.net.NetworkInterface
import java.util.UUID

class AdRequestBuilder(
    private val sharedPreferences: SharedPreferences,
) {
    val isUSPrivacy: Boolean
        get() {
            return emAdsModuleInput.isGdprApproved
        }
    private val deviceId: String = getOrCreateDeviceId()

    private val emAdsModuleInput: EMAdsModuleInput by lazy {
        EMAdsModule.getInstance()
    }

    private fun getOrCreateDeviceId(): String {
        return sharedPreferences.getString("device_id", null) ?: run {
            val newDeviceId = UUID.randomUUID().toString()
            sharedPreferences.edit().putString("device_id", newDeviceId).apply()
            newDeviceId
        }
    }

    val userId: String
        get() {
            return emAdsModuleInput.userId
        }

    private val context: Context
        get() {
            return emAdsModuleInput.context
        }

    fun createVastAdRequestDto(
        id: String = UUID.randomUUID().toString(),
        imp: List<Imp> = listOf(
            Imp(
                id = "$id$deviceId",
                video = Video(
                    mimes = listOf(
                        "video/webm",
                        "video/x-ms-wmv",
                        "video/mp4",
                        "video/3gpp",
                        "application/x-mpegURL",
                        "video/quicktime",
                        "video/x-msvideo",
                        "video/x-flv",
                        "video/ogg"
                    ),
                    protocols = listOf(2),
                    w = context.resources.displayMetrics.widthPixels,
                    h = context.resources.displayMetrics.heightPixels,
                    linearity = 1,
                    boxingallowed = 1
                ),
                bidfloor = 0.0,
                bidfloorcur = "USD",
                secure = 1
            )

        ),
        app: App = App(
            name = context.applicationInfo.loadLabel(context.packageManager).toString(),
            bundle = context.packageName,
            storeurl = "http://www.amazon.com/gp/mas/dl/android?p=${context.packageName}",
            channelId = emAdsModuleInput.channelId,
            publisherId = emAdsModuleInput.publisherId
        ),
        device: Device = Device(
            ua = getUserAgent(context),
            geo = null,
            dnt = 0,
            lmt = 0,
            ip = getLocalIpAddress(),
            devicetype = 3,
            model = Build.MODEL,
            os = Build.VERSION.RELEASE,
            js = 1,
            ifa = UUID.randomUUID().toString(),
            ext = DeviceExt(
                ifaType = "idfa"
            )
        ),
        user: User = User(
            id = userId
        ),
        at: Int = 2,
        tmax: Int = 1000,
        cur: List<String> = listOf("USD"),
        regs: Regs = Regs(
            gdpr = if (emAdsModuleInput.isGdprApproved) 1 else 0,
        )
    ): VastAdRequestDto {
        return VastAdRequestDto(id, imp, app, device, user, at, tmax, cur, regs)
    }

    // create a function to generate user agent for this request using the application context
    private fun getUserAgent(context: Context): String {
        val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val deviceModel = Build.MODEL
        val osVersion = Build.VERSION.RELEASE
        val userAgent = String.format(
            "%s/%s (Linux; Android %s; %s)",
            appName,
            appVersion,
            osVersion,
            deviceModel
        )
        return userAgent
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress) {
                        return inetAddress.hostAddress?.toString()
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("IP Address", ex.toString())
        }
        return null
    }
}