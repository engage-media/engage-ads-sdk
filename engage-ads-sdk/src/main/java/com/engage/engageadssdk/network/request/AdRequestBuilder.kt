package com.engage.engageadssdk.network.request

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.module.EMAdsModuleInput
import java.net.NetworkInterface
import java.util.UUID

class AdRequestBuilder(
    private val packageManager: PackageManager,
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

    val isAmazonDevice: Boolean
        get() {
        val AMAZON_FEATURE_FIRE_TV = "amazon.hardware.fire_tv"
        val AMAZON_MODEL = Build.MODEL

        if (AMAZON_MODEL.matches("AFTN".toRegex())) {
            return true
        } else if (packageManager.hasSystemFeature(AMAZON_FEATURE_FIRE_TV)) {
            return true
        } else {
            return false
        }

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
            bundle = if (isAmazonDevice) {
                emAdsModuleInput.bundleId!!
            } else {
                context.packageName
            },
            storeurl =  if (isAmazonDevice) {
                "http://www.amazon.com/${context.applicationInfo.loadLabel(context.packageManager)}/dp/${emAdsModuleInput.bundleId!!}"
            } else {
                "https://play.google.com/store/apps/details?id=${context.packageName}"
            },
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
        at: Int = 2,
        tmax: Int = 1000,
        cur: List<String> = listOf("USD"),
        regs: Regs = Regs(
            gdpr = if (emAdsModuleInput.isGdprApproved) 1 else 0,
        )
    ): VastAdRequestDto {
        return VastAdRequestDto(id, imp, app, device, at, tmax, cur, regs)
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

class VastAdRequestDto(
    val id: String,
    val imp: List<Imp>,
    val app: App,
    val device: Device,
    val at: Int,
    val tmax: Int,
    val cur: List<String>,
    val regs: Regs
)

class Device(
    val ua: String,
    val geo: Nothing?,
    val dnt: Int,
    val lmt: Int,
    val ip: String?,
    val devicetype: Int,
    val model: String?,
    val os: String?,
    val js: Int,
    val ifa: String,
    val ext: DeviceExt
)

class DeviceExt(val ifaType: String)

class Video(
    val mimes: List<String>,
    val protocols: List<Int>,
    val w: Int,
    val h: Int,
    val linearity: Int,
    val boxingallowed: Int
)

class Imp(
    val id: String,
    val video: Video,
    val bidfloor: Double,
    val bidfloorcur: String,
    val secure: Int
) {

}

class Regs(val gdpr: Int)

class App(
    val name: String,
    val bundle: String?,
    val storeurl: String,
    val channelId: String,
    val publisherId: String
)
